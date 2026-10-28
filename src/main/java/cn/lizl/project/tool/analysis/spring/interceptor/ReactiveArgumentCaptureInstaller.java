package cn.lizl.project.tool.analysis.spring.interceptor;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodParameter;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.function.Supplier;

/**
 * WebFlux 通道的参数捕获安装器，语义与 MVC 的 {@link InterceptorArgumentCaptureInstaller} 同构。
 *
 * <p>WebFlux 版 adapter 没有可用的解析器列表访问口：解析器在初始化时被装进包私有的
 * {@code ControllerMethodResolver} 内部的可变 List（{@code requestMappingResolvers} /
 * {@code modelAttributeResolvers}），请求时原序遍历。因此这里用反射在初始化完成后把两个 List
 * 里的元素原位替换成捕获包装——List 元素可变，final 引用不受影响。
 *
 * <p>与 MVC 的另一差异：常规路径分析不在事件循环线程上同步执行（会阻塞同一线程上的所有连接），
 * 触发后派发到专用单守护线程串行执行；但队列满载的降级**有意**回到调用线程同步执行——
 * 分析必须先于业务代码，晚了被捕获对象就可能被业务改掉而失去分析意义，宁可短暂阻塞事件循环。
 */
final class ReactiveArgumentCaptureInstaller implements BeanPostProcessor {

    private static final String CAPTURED_ARGS_ATTRIBUTE = ReactiveArgumentCaptureInstaller.class.getName() + ".capturedArgs";

    private static final String[] RESOLVER_LIST_FIELDS = {"requestMappingResolvers", "modelAttributeResolvers"};

    /**
     * 分析队列容量：积压超限时降级为调用线程同步分析（背压，保证先于业务执行），不丢任务
     */
    static final int ANALYSIS_QUEUE_CAPACITY = 256;

    private final Logger log = Logger.getLogger(getClass().getName());

    private final Supplier<InterceptorControl> controlSupplier;

    /**
     * 反射钩子失败只报一次，之后静默跳过，避免每次请求刷日志
     */
    private final AtomicBoolean hookFailureLogged = new AtomicBoolean(false);

    /**
     * 队列满载降级为调用线程同步分析的累计次数
     */
    private final AtomicLong callerRunCount = new AtomicLong();

    /**
     * 分析专用线程：串行化 + 隔离事件循环。队列有界（{@value #ANALYSIS_QUEUE_CAPACITY}），
     * 满载降级为在调用线程同步执行（背压）——**有意设计**：分析必须先于业务代码执行，
     * 挪到别的线程会让被捕获对象先被业务改掉而失去分析意义；极端积压时由请求线程
     * 短暂分担分析（受 analysisTimeoutMillis 兜底），限频告警提示容量不够用。
     */
    private final ThreadPoolExecutor analysisExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(ANALYSIS_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "lizl-java-data-analyzer-reactive-analysis");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, executor) -> {
                long callerRuns = callerRunCount.incrementAndGet();
                if (callerRuns == 1 || callerRuns % 100 == 0) {
                    log.warning("WebFlux 请求分析队列已满，累计 " + callerRuns + " 次降级为调用线程同步分析（不丢任务）");
                }
                if (!executor.isShutdown()) {
                    runnable.run();
                }
            });

    ReactiveArgumentCaptureInstaller(Supplier<InterceptorControl> controlSupplier) {
        this.controlSupplier = controlSupplier;
    }

    /**
     * Spring {@code @Bean} 推断销毁方法：关闭分析线程池
     */
    @SuppressWarnings("unused")
    public void shutdown() {
        analysisExecutor.shutdownNow();
    }

    @Override
    public Object postProcessAfterInitialization(@Nonnull Object bean, @Nonnull String beanName) {
        if (bean instanceof RequestMappingHandlerAdapter) {
            wrapResolversInPlace(bean);
        }
        return bean;
    }

    private void wrapResolversInPlace(Object adapter) {
        for (String fieldName : RESOLVER_LIST_FIELDS) {
            List<HandlerMethodArgumentResolver> resolvers = readResolverList(adapter, fieldName);
            if (resolvers == null) {
                return;
            }
            Map<HandlerMethodArgumentResolver, HandlerMethodArgumentResolver> wrapped = new HashMap<>();
            for (int i = 0; i < resolvers.size(); i++) {
                HandlerMethodArgumentResolver resolver = resolvers.get(i);
                if (!wrapped.containsKey(resolver)) {
                    wrapped.put(resolver, new CapturingResolver(resolver));
                }
                resolvers.set(i, wrapped.get(resolver));
            }
        }
    }

    /**
     * 反射读取 adapter → methodResolver → 指定名称的解析器 List；任一环失败返回 null 并记一次警告
     */
    private List<HandlerMethodArgumentResolver> readResolverList(Object adapter, String listFieldName) {
        try {
            Field methodResolverField = adapter.getClass().getDeclaredField("methodResolver");
            methodResolverField.setAccessible(true);
            Object methodResolver = methodResolverField.get(adapter);
            if (methodResolver == null) {
                return null;
            }
            Field listField = methodResolver.getClass().getDeclaredField(listFieldName);
            listField.setAccessible(true);
            Object list = listField.get(methodResolver);
            @SuppressWarnings("unchecked")
            List<HandlerMethodArgumentResolver> resolvers = (List<HandlerMethodArgumentResolver>) list;
            return resolvers;
        } catch (Throwable throwable) {
            if (hookFailureLogged.compareAndSet(false, true)) {
                log.warning("WebFlux 参数捕获钩子装配失败，拦截器通道在本宿主不可用：" + throwable);
            }
            return null;
        }
    }

    private final class CapturingResolver implements HandlerMethodArgumentResolver {

        private final HandlerMethodArgumentResolver delegate;

        CapturingResolver(HandlerMethodArgumentResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supportsParameter(@NonNull MethodParameter parameter) {
            return delegate.supportsParameter(parameter);
        }

        @Override
        public @NonNull Mono<Object> resolveArgument(@NonNull MethodParameter parameter, org.springframework.web.reactive.@NonNull BindingContext bindingContext,
                                                     @NonNull ServerWebExchange exchange) {
            AtomicReference<Object> valueRef = new AtomicReference<>();
            // 可空参数的 Mono 可能是空的，doOnFinally 保证「已解析」这个事实本身被记录
            return delegate.resolveArgument(parameter, bindingContext, exchange)
                    .doOnNext(valueRef::set)
                    .doFinally(signal -> record(parameter, valueRef.get(), exchange));
        }

        private void record(MethodParameter parameter, Object value, ServerWebExchange exchange) {
            try {
                InterceptorControl control = controlSupplier.get();
                if (control == null || !control.isActive()) {
                    return;
                }
                String requestUri = exchange.getRequest().getPath().value();
                if (!control.shouldAnalyze(requestUri)) {
                    return;
                }
                Map<Integer, Object> captured = getOrCreateCapturedArgs(exchange);
                captured.put(parameter.getParameterIndex(), value);
                Method method = parameter.getMethod();
                if (method == null || captured.size() != method.getParameterCount()) {
                    return;
                }
                String handler = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
                InterceptorRequestAnalyzer analyzer = control.analyzer();
                analysisExecutor.execute(() -> analyzer.analyze(handler, requestUri, new HashMap<>(captured)));
            } catch (Exception ignored) {
                // 只隔离 Exception，捕获与分析绝不影响请求本身；Error（OOM 等）自然穿透
            }
        }

        @SuppressWarnings("unchecked")
        private Map<Integer, Object> getOrCreateCapturedArgs(ServerWebExchange exchange) {
            Object existing = exchange.getAttributes().get(CAPTURED_ARGS_ATTRIBUTE);
            if (existing instanceof Map) {
                return (Map<Integer, Object>) existing;
            }
            Map<Integer, Object> created = new HashMap<>();
            exchange.getAttributes().put(CAPTURED_ARGS_ATTRIBUTE, created);
            return created;
        }
    }
}
