package cn.lizl.project.tool.analysis.spring.interceptor;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 把 {@link RequestMappingHandlerAdapter} 的内建参数解析器包一层捕获代理：真实解析仍由原解析器做，
 * 只在解析完成后按参数下标记录进请求属性；某方法的参数全部解析完即触发同步分析。
 *
 * <p>这是拿到 handler 方法入参的唯一低侵入通道——HandlerInterceptor 与 AOP 都拿不到实参。
 * 开关与路径过滤在捕获入口就判定，未启用的请求只多一次 volatile 读，不建任何请求级对象。
 *
 * <p><b>为什么不接触底层 ServletRequest：</b>{@code ServletRequestAttributes#getRequest()} 在 Spring 5
 * 返回 {@code javax.servlet...HttpServletRequest}、Spring 6 返回 {@code jakarta.servlet...HttpServletRequest}，
 * 字节码方法描述符带返回类型，编译期绑定任一套都会在另一套宿主上抛 NoSuchMethodError。这里只用 Spring
 * 自己的 {@link NativeWebRequest} / {@link RequestAttributes} 抽象：路径取
 * {@link HandlerMapping#PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE}，捕获容器挂在 SCOPE_REQUEST 请求属性上，
 * 同一份字节码同时适配 Boot 2.x 与 Boot 3.x，既不引用 Servlet 类型也不反射。
 *
 * <p>路径口径与 {@code request.getRequestURI()} 不同：这里是应用内 lookupPath，不含 contextPath、
 * 不含矩阵变量后缀，与 {@code include-paths} / {@code exclude-paths} 的写法更贴合。
 */
final class InterceptorArgumentCaptureInstaller implements BeanPostProcessor {

    private static final Logger LOG = Logger.getLogger(InterceptorArgumentCaptureInstaller.class.getName());

    private static final AtomicBoolean PATH_MISSING_LOGGED = new AtomicBoolean();

    private static final String CAPTURED_ARGS_ATTRIBUTE = InterceptorArgumentCaptureInstaller.class.getName() + ".capturedArgs";

    private final Supplier<InterceptorControl> controlSupplier;

    InterceptorArgumentCaptureInstaller(Supplier<InterceptorControl> controlSupplier) {
        this.controlSupplier = controlSupplier;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RequestMappingHandlerAdapter) {
            RequestMappingHandlerAdapter adapter = (RequestMappingHandlerAdapter) bean;
            List<HandlerMethodArgumentResolver> original = adapter.getArgumentResolvers();
            if (original != null && !original.isEmpty()) {
                // 同一解析器实例可能被多个 adapter 共享，按实例去重包装
                Map<HandlerMethodArgumentResolver, HandlerMethodArgumentResolver> wrapped = new HashMap<>();
                for (HandlerMethodArgumentResolver resolver : original) {
                    if (resolver instanceof CapturingResolver) {
                        // 已包装过（BPP 被重复应用到同一 adapter）直接沿用，防 CapturingResolver 双层套娃
                        wrapped.put(resolver, resolver);
                        continue;
                    }
                    if (!wrapped.containsKey(resolver)) {
                        wrapped.put(resolver, new CapturingResolver(resolver));
                    }
                }
                List<HandlerMethodArgumentResolver> replaced = new ArrayList<>(original.size());
                for (HandlerMethodArgumentResolver resolver : original) {
                    replaced.add(wrapped.get(resolver));
                }
                adapter.setArgumentResolvers(replaced);
            }
        }
        return bean;
    }

    private final class CapturingResolver implements HandlerMethodArgumentResolver {

        private final HandlerMethodArgumentResolver delegate;

        CapturingResolver(HandlerMethodArgumentResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return delegate.supportsParameter(parameter);
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
            Object value = delegate.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
            record(parameter, value, webRequest);
            return value;
        }

        private void record(MethodParameter parameter, Object value, NativeWebRequest webRequest) {
            try {
                InterceptorControl control = controlSupplier.get();
                if (control == null || !control.isActive()) {
                    return;
                }
                String requestUri = requestUri(webRequest);
                if (requestUri == null || !control.shouldAnalyze(requestUri)) {
                    return;
                }
                Map<Integer, Object> captured = capturedArgs(webRequest);
                captured.put(parameter.getParameterIndex(), value);
                Method method = parameter.getMethod();
                if (method == null || captured.size() != method.getParameterCount()) {
                    return;
                }
                String handler = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
                control.analyzer().analyze(handler, requestUri, captured);
            } catch (Exception ignored) {
                // 只隔离 Exception，捕获与分析绝不影响请求本身；Error（OOM 等）自然穿透
            }
        }
    }

    // ---------------- 纯 Spring 抽象的请求访问 ----------------

    /**
     * 应用内请求路径。Spring MVC 在 handler 匹配阶段把它写进请求属性，Spring 5 / 6 的常量字面值
     * 与值类型一致（实测四种路径场景）。缺失时记一次 WARN 并返回 null，调用方跳过该次捕获：
     * 自定义 HandlerMapping 或非 MVC 入口可能不写这个属性，静默失败会让使用者以为捕获一直开着。
     */
    static String requestUri(NativeWebRequest webRequest) {
        Object path = webRequest.getAttribute(
                HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (path instanceof String) {
            return (String) path;
        }
        if (PATH_MISSING_LOGGED.compareAndSet(false, true)) {
            LOG.warning("MVC 参数捕获取不到应用内请求路径（"
                    + HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
                    + "），本次请求跳过捕获；请确认使用的是标准 Spring MVC HandlerMapping");
        }
        return null;
    }

    /** 捕获容器挂在 SCOPE_REQUEST 上：生命周期与请求一致，且无需接触底层 ServletRequest。 */
    @SuppressWarnings("unchecked")
    static Map<Integer, Object> capturedArgs(NativeWebRequest webRequest) {
        Object existing = webRequest.getAttribute(CAPTURED_ARGS_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (existing instanceof Map) {
            return (Map<Integer, Object>) existing;
        }
        Map<Integer, Object> created = new HashMap<>();
        webRequest.setAttribute(CAPTURED_ARGS_ATTRIBUTE, created, RequestAttributes.SCOPE_REQUEST);
        return created;
    }
}
