package cn.lizl.project.tool.analysis.spring.interceptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 纯 Spring 抽象请求访问的守护用例：路径来自 MVC 写入的请求属性，捕获容器挂在 SCOPE_REQUEST 上。
 * 不接触任何 Servlet 类型，因此同一份测试与实现可同时用于 javax / jakarta 两套宿主。
 */
class InterceptorArgumentCaptureInstallerTest {

    @Test
    @DisplayName("路径取 PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE，而不是完整 requestURI")
    void shouldReadPathWithinHandlerMapping() {
        Map<String, Object> store = new HashMap<>();
        store.put(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, "/api/orders/123");

        assertEquals("/api/orders/123",
                InterceptorArgumentCaptureInstaller.requestUri(mockWebRequest(store)));
    }

    @Test
    @DisplayName("路径属性缺失时返回 null，由调用方跳过该次捕获")
    void shouldReturnNullWhenPathAttributeMissing() {
        assertNull(InterceptorArgumentCaptureInstaller.requestUri(mockWebRequest(new HashMap<>())));
    }

    @Test
    @DisplayName("同一请求的捕获容器可复用，不重复创建")
    void shouldReuseCapturedArgsPerRequest() {
        NativeWebRequest webRequest = mockWebRequest(new HashMap<>());

        Map<Integer, Object> first = InterceptorArgumentCaptureInstaller.capturedArgs(webRequest);
        Map<Integer, Object> second = InterceptorArgumentCaptureInstaller.capturedArgs(webRequest);
        assertNotNull(first);
        assertSame(first, second, "同一请求应复用同一份捕获容器");
    }

    private static NativeWebRequest mockWebRequest(Map<String, Object> store) {
        return (NativeWebRequest) Proxy.newProxyInstance(
                InterceptorArgumentCaptureInstallerTest.class.getClassLoader(),
                new Class<?>[]{NativeWebRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAttribute":
                            return store.get(args[0]);
                        case "setAttribute":
                            store.put((String) args[0], args[1]);
                            return null;
                        case "toString":
                            return "mock-web-request";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }
}
