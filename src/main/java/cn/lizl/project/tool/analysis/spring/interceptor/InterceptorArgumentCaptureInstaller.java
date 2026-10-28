package cn.lizl.project.tool.analysis.spring.interceptor;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 把 {@link RequestMappingHandlerAdapter} 的内建参数解析器包一层捕获代理：真实解析仍由原解析器做，
 * 只在解析完成后按参数下标记录进请求属性；某方法的参数全部解析完即触发同步分析。
 *
 * <p>这是拿到 handler 方法入参的唯一低侵入通道——HandlerInterceptor 与 AOP 都拿不到实参。
 * 开关与路径过滤在捕获入口就判定，未启用的请求只多一次 volatile 读，不建任何请求级对象。
 */
final class InterceptorArgumentCaptureInstaller implements BeanPostProcessor {

    private static final String CAPTURED_ARGS_ATTRIBUTE = InterceptorArgumentCaptureInstaller.class.getName() + ".capturedArgs";

    private final Supplier<InterceptorControl> controlSupplier;

    InterceptorArgumentCaptureInstaller(Supplier<InterceptorControl> controlSupplier) {
        this.controlSupplier = controlSupplier;
    }

    @Override
    public Object postProcessAfterInitialization(@Nonnull Object bean, @Nonnull String beanName) {
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
        public boolean supportsParameter(@Nonnull MethodParameter parameter) {
            return delegate.supportsParameter(parameter);
        }

        @Override
        public Object resolveArgument(@Nonnull MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      @Nonnull NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
            Object value = delegate.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
            record(parameter, value);
            return value;
        }

        private void record(MethodParameter parameter, Object value) {
            try {
                InterceptorControl control = controlSupplier.get();
                if (control == null || !control.isActive()) {
                    return;
                }
                ServletRequestAttributes attributes =
                        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                if (attributes == null) {
                    return;
                }
                HttpServletRequest request = attributes.getRequest();
                String requestUri = request.getRequestURI();
                if (!control.shouldAnalyze(requestUri)) {
                    return;
                }
                Map<Integer, Object> captured = getOrCreateCapturedArgs(request);
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

        @SuppressWarnings("unchecked")
        private Map<Integer, Object> getOrCreateCapturedArgs(HttpServletRequest request) {
            Object existing = request.getAttribute(CAPTURED_ARGS_ATTRIBUTE);
            if (existing instanceof Map) {
                return (Map<Integer, Object>) existing;
            }
            Map<Integer, Object> created = new HashMap<>();
            request.setAttribute(CAPTURED_ARGS_ATTRIBUTE, created);
            return created;
        }
    }
}
