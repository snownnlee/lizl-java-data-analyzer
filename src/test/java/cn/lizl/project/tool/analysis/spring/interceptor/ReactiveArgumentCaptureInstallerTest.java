package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.MethodParameter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * WebFlux 通道：BPP 对真实 reactive adapter 的原位包装、参数捕获触发分析与开关短路。
 */
class ReactiveArgumentCaptureInstallerTest {

    @TempDir
    Path outputRootDir;

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("初始化后包装：requestMappingResolvers 全部变成捕获代理")
    void shouldWrapResolversInPlace() throws Exception {
        RequestMappingHandlerAdapter adapter = newAdapter();
        newInstaller(enabledProperties()).postProcessAfterInitialization(adapter, "adapter");

        List<HandlerMethodArgumentResolver> resolvers = resolverList(adapter, "requestMappingResolvers");
        assertTrue(resolvers.size() > 0, "adapter 应已初始化出内建解析器");
        for (HandlerMethodArgumentResolver resolver : resolvers) {
            assertTrue(resolver.getClass().getName().contains("CapturingResolver"),
                    "所有解析器都应是捕获代理：" + resolver.getClass().getName());
        }
    }

    @Test
    @DisplayName("参数解析完即触发分析：产物落盘且登记可见")
    void shouldTriggerAnalysisWhenAllParamsResolved() throws Exception {
        InterceptorProperties properties = enabledProperties();
        InterceptorControl control = new InterceptorControl(properties);
        try {
            RequestMappingHandlerAdapter adapter = newAdapter();
            customDelegate = sampleResolver();
            org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer configurer =
                    new org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer();
            configurer.addCustomResolver(customDelegate);
            adapter.setArgumentResolverConfigurer(configurer);
            adapter.afterPropertiesSet();
            newInstaller(control).postProcessAfterInitialization(adapter, "adapter");

            HandlerMethodArgumentResolver wrapped = findWrappedResolverFor(adapter, customDelegate);
            MethodParameter parameter = new MethodParameter(handlerMethod(), 0);
            ServerWebExchange exchange = fakeExchange("/demo/reactive?sku=X1&count=1");

            wrapped.resolveArgument(parameter, new BindingContext(), exchange).block();

            InterceptorControlTest.awaitOrFail(
                    () -> !control.interceptorOutputNames().isEmpty(), "应有产物登记");
            File output = control.getInterceptorOutput(control.interceptorOutputNames().get(0));
            assertTrue(output != null && output.isFile(), "产物文件应存在");
            assertTrue(control.interceptorOutputNames().get(0).endsWith(".html"));
        } finally {
            control.shutdown();
        }
    }

    @Test
    @DisplayName("interceptor.active=false：通道已装配，但解析后不捕获不分析")
    void shouldNotCaptureWhenInactive() throws Exception {
        InterceptorProperties properties = enabledProperties();
        properties.setActive(false);
        InterceptorControl control = new InterceptorControl(properties);
        try {
            RequestMappingHandlerAdapter adapter = newAdapter();
            customDelegate = sampleResolver();
            org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer configurer =
                    new org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer();
            configurer.addCustomResolver(customDelegate);
            adapter.setArgumentResolverConfigurer(configurer);
            adapter.afterPropertiesSet();
            newInstaller(control).postProcessAfterInitialization(adapter, "adapter");

            HandlerMethodArgumentResolver wrapped = findWrappedResolverFor(adapter, customDelegate);
            MethodParameter parameter = new MethodParameter(handlerMethod(), 0);
            ServerWebExchange exchange = fakeExchange("/demo/reactive");

            Object resolved = wrapped.resolveArgument(parameter, new BindingContext(), exchange).block();
            assertTrue(resolved instanceof Sample, "解析结果必须原样返回，业务不受影响");

            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertEquals(0, control.interceptorOutputNames().size(), "关闭状态不应产出");
        } finally {
            control.shutdown();
        }
    }

    // ---------------- 有界背压分析队列（对应 v2.7.7：满载降级调用线程，不丢任务） ----------------

    @SuppressWarnings("unchecked")
    private java.util.concurrent.ThreadPoolExecutor analysisExecutorOf(ReactiveArgumentCaptureInstaller installer)
            throws Exception {
        java.lang.reflect.Field executorField =
                ReactiveArgumentCaptureInstaller.class.getDeclaredField("analysisExecutor");
        executorField.setAccessible(true);
        return (java.util.concurrent.ThreadPoolExecutor) executorField.get(installer);
    }

    @Test
    @DisplayName("队列满载时降级为调用线程同步执行，任务一个不丢")
    void shouldNotLoseTasksWhenQueueFull() throws Exception {
        ReactiveArgumentCaptureInstaller installer = newInstaller(enabledProperties());
        java.util.concurrent.ThreadPoolExecutor executor = analysisExecutorOf(installer);
        try {
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch workerBusy = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger executed = new java.util.concurrent.atomic.AtomicInteger();

            // 占住唯一的分析线程，制造「队列被填满」的条件
            executor.execute(() -> {
                workerBusy.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerBusy.await(2, java.util.concurrent.TimeUnit.SECONDS), "worker 应已开始执行");

            for (int i = 0; i < ReactiveArgumentCaptureInstaller.ANALYSIS_QUEUE_CAPACITY; i++) {
                executor.execute(executed::incrementAndGet);
            }

            // 队列已满：这一条走拒绝策略，降级为调用线程（本测试线程）同步执行
            java.util.concurrent.atomic.AtomicBoolean callerRan = new java.util.concurrent.atomic.AtomicBoolean(false);
            long callerSubmitStart = System.nanoTime();
            executor.execute(() -> callerRan.set(true));
            assertTrue(callerRan.get(), "满载任务应降级在调用线程同步执行，不允许丢弃");
            assertTrue(System.nanoTime() - callerSubmitStart < java.util.concurrent.TimeUnit.SECONDS.toNanos(5),
                    "降级执行应同步返回，不应阻塞到队列腾空");

            // 释放 worker，排空队列后全部任务都应被执行（不丢）
            release.countDown();
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && executed.get() < ReactiveArgumentCaptureInstaller.ANALYSIS_QUEUE_CAPACITY) {
                Thread.sleep(20);
            }
            assertEquals(ReactiveArgumentCaptureInstaller.ANALYSIS_QUEUE_CAPACITY, executed.get(),
                    "队列中的任务在排空后应全部执行完毕，一个不丢");
        } finally {
            installer.shutdown();
        }
    }

    @Test
    @DisplayName("shutdown 后再提交不执行也不抛异常（拒绝策略静默）")
    void shouldRejectQuietlyAfterShutdown() throws Exception {
        ReactiveArgumentCaptureInstaller installer = newInstaller(enabledProperties());
        java.util.concurrent.ThreadPoolExecutor executor = analysisExecutorOf(installer);
        installer.shutdown();
        assertTrue(executor.isShutdown(), "shutdown 后线程池应进入关闭状态");

        final boolean[] shouldNotRun = {false};
        executor.execute(() -> shouldNotRun[0] = true);
        assertFalse(shouldNotRun[0], "shutdown 后提交的任务不应被执行");
    }

    private HandlerMethodArgumentResolver customDelegate;

    private InterceptorProperties enabledProperties() {
        InterceptorProperties properties = new InterceptorProperties();
        properties.setEnable(true);   // 装配开关：通道已装
        properties.setActive(true);   // 运行期开关：装了就捕获
        properties.setOutputRootDir(outputRootDir.toString());
        properties.setOutputQueueCapacity(8);
        return properties;
    }

    private ReactiveArgumentCaptureInstaller newInstaller(InterceptorProperties properties) {
        return new ReactiveArgumentCaptureInstaller(() -> new InterceptorControl(properties));
    }

    private ReactiveArgumentCaptureInstaller newInstaller(InterceptorControl control) {
        return new ReactiveArgumentCaptureInstaller(() -> control);
    }

    /** 构建 reactive adapter：注入最小 context 并完成初始化（解析器列表就位） */
    private RequestMappingHandlerAdapter newAdapter() throws Exception {
        RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
        adapter.setApplicationContext(new org.springframework.context.support.StaticApplicationContext());
        adapter.afterPropertiesSet();
        return adapter;
    }

    /** 委托解析器：只支持 Sample 参数，解析为固定值 */
    private HandlerMethodArgumentResolver sampleResolver() {
        return new HandlerMethodArgumentResolver() {

            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == Sample.class;
            }

            @Override
            public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext bindingContext,
                                                org.springframework.web.server.ServerWebExchange exchange) {
                return Mono.just(new Sample());
            }
        };
    }

    /** 按委托实例身份找包装代理——内建 ModelAttribute 解析器同样支持 POJO 参数，不能按 supports 判定 */
    private HandlerMethodArgumentResolver findWrappedResolverFor(RequestMappingHandlerAdapter adapter,
                                                                 HandlerMethodArgumentResolver delegate) throws Exception {
        List<HandlerMethodArgumentResolver> resolvers = resolverList(adapter, "requestMappingResolvers");
        for (HandlerMethodArgumentResolver resolver : resolvers) {
            Field delegateField = resolver.getClass().getDeclaredField("delegate");
            delegateField.setAccessible(true);
            if (delegateField.get(resolver) == delegate) {
                return resolver;
            }
        }
        return fail("找不到指定委托的包装解析器");
    }

    @SuppressWarnings("unchecked")
    private static List<HandlerMethodArgumentResolver> resolverList(RequestMappingHandlerAdapter adapter, String fieldName) throws Exception {
        Field methodResolverField = adapter.getClass().getDeclaredField("methodResolver");
        methodResolverField.setAccessible(true);
        Object methodResolver = methodResolverField.get(adapter);
        Field listField = methodResolver.getClass().getDeclaredField(fieldName);
        listField.setAccessible(true);
        return (List<HandlerMethodArgumentResolver>) listField.get(methodResolver);
    }

    private static Method handlerMethod() throws NoSuchMethodException {
        return ReactiveArgumentCaptureInstallerTest.class.getDeclaredMethod("handle", Sample.class);
    }

    /** 最小 ServerWebExchange 桩：只实现捕获路径真正用到的 getRequest/getAttributes，其余返回 null */
    private ServerWebExchange fakeExchange(String uri) {
        ServerHttpRequest request = (ServerHttpRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ServerHttpRequest.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getPath")) {
                        // Spring 5.3 的默认实现会把 getURI() 强转成 RequestPath，桩必须直接给 RequestPath
                        return org.springframework.http.server.RequestPath.parse(URI.create(uri), "");
                    }
                    if (method.getName().equals("getQueryParams")) {
                        return new org.springframework.util.LinkedMultiValueMap<>();
                    }
                    return null;
                });
        Map<String, Object> attributes = new HashMap<>();
        return (ServerWebExchange) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ServerWebExchange.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getRequest")) {
                        return request;
                    }
                    if (method.getName().equals("getAttributes")) {
                        return attributes;
                    }
                    return null;
                });
    }

    @SuppressWarnings("unused")
    private static String handle(Sample sample) {
        return "ok";
    }

    static class Sample {

        private String sku = "reactive";
    }
}
