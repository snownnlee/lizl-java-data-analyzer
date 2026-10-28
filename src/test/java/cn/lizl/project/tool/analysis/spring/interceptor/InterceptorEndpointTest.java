package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.http.AnalysisProvider;
import cn.lizl.project.tool.analysis.http.HttpAnalysisServer;
import cn.lizl.project.tool.analysis.http.HttpServiceHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /interceptor} 端点组：GET 页面、state/list/output JSON、POST 热更新、未挂载 404。
 */
class InterceptorEndpointTest {

    @Test
    @DisplayName("GET 页面 + state/list JSON + POST 按参数热更新")
    void shouldReadAndUpdateViaEndpoint() throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("active", Boolean.TRUE);
        state.put("includePaths", Collections.emptyList());
        state.put("excludePaths", Collections.emptyList());
        state.put("submitted", 0L);
        RecordingProvider provider = new RecordingProvider(state);
        provider.outputs.put("a.html", new File("a.html"));

        HttpAnalysisServer server = new HttpAnalysisServer(new EmptyProvider())
                .register(new InterceptorHttpService(provider));
        server.setPort(0);
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getBoundPort() + HttpAnalysisServer.DEFAULT_PATH_PREFIX;

            String page = bodyOf(baseUrl + "/interceptor", "GET");
            assertTrue(page.contains("Interceptor"), "GET /interceptor 应返回管理页");

            String body = bodyOf(baseUrl + "/interceptor/state", "GET");
            assertTrue(body.contains("\"active\":true"), "state 应返回状态 JSON：" + body);

            String names = bodyOf(baseUrl + "/interceptor/list", "GET");
            assertTrue(names.contains("a.html"), "list 应返回产物名：" + names);

            provider.outputs.put("20261026/x.html", new File("x.html"));
            provider.outputs.put("20261025/y.html", new File("y.html"));
            String todayOnly = bodyOf(baseUrl + "/interceptor/list?date=20261026", "GET");
            assertTrue(todayOnly.contains("20261026/x.html"), "date 过滤应命中当天：" + todayOnly);
            assertFalse(todayOnly.contains("20261025/y.html"), "date 过滤应排除历史日期：" + todayOnly);
            String limited = bodyOf(baseUrl + "/interceptor/list?limit=1", "GET");
            assertTrue(limited.startsWith("["), "limit 应生效：" + limited);
            assertTrue(limited.length() < names.length() + provider.outputs.size() * 20,
                    "limit=1 应明显短于全量：" + limited);

            String updated = bodyOf(baseUrl + "/interceptor?active=false&include=/api/**&exclude=", "POST");
            assertTrue(updated.contains("\"active\":false"), "POST 后应返回新状态：" + updated);
            assertTrue(updated.contains("\"includePaths\":[\"/api/**\"]"), "include 应生效：" + updated);
            assertTrue(updated.contains("\"excludePaths\":[]"), "exclude 传空串应表示清空：" + updated);
            assertEquals(Boolean.FALSE, provider.lastActive);
            assertEquals(Collections.singletonList("/api/**"), provider.lastInclude);
            assertEquals(Collections.emptyList(), provider.lastExclude);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("未挂载控制实现时 /interceptor 整组 404，不暴露幽灵端点")
    void shouldReturn404WithoutProvider() throws IOException {
        HttpAnalysisServer server = new HttpAnalysisServer(new EmptyProvider());
        server.setPort(0);
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getBoundPort() + HttpAnalysisServer.DEFAULT_PATH_PREFIX;
            assertEquals(404, statusOf(baseUrl + "/interceptor", "GET"));
            assertEquals(404, statusOf(baseUrl + "/interceptor", "POST"));
            assertEquals(404, statusOf(baseUrl + "/interceptor/state", "GET"));
            assertEquals(404, statusOf(baseUrl + "/interceptor/list", "GET"));
        } finally {
            server.stop();
        }
    }

    private int statusOf(String url, String method) throws IOException {
        HttpURLConnection connection = open(url, method);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private String bodyOf(String url, String method) throws IOException {
        HttpURLConnection connection = open(url, method);
        try (InputStream inputStream = connection.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.getOutputStream().close();
        }
        return connection;
    }

    private static final class EmptyProvider implements AnalysisProvider {

        @Override
        public java.util.List<String> getBeanNames(boolean rebuild) {
            return Collections.emptyList();
        }

        @Override
        public java.io.File getAnalyzedResult(String beanName, boolean rebuild) {
            return null;
        }
    }

    private static final class RecordingProvider implements InterceptorControlProvider {

        private final Map<String, Object> state;

        final Map<String, File> outputs = new LinkedHashMap<>();

        Boolean lastActive;

        List<String> lastInclude;

        List<String> lastExclude;

        RecordingProvider(Map<String, Object> state) {
            this.state = state;
        }

        @Override
        public Map<String, Object> interceptorState() {
            return state;
        }

        @Override
        public Map<String, Object> updateInterceptorState(Boolean active, List<String> includePaths, List<String> excludePaths) {
            this.lastActive = active;
            this.lastInclude = includePaths;
            this.lastExclude = excludePaths;
            if (active != null) {
                state.put("active", active);
            }
            if (includePaths != null) {
                state.put("includePaths", includePaths);
            }
            if (excludePaths != null) {
                state.put("excludePaths", excludePaths);
            }
            return state;
        }

        @Override
        public List<String> interceptorOutputNames() {
            return new ArrayList<>(outputs.keySet());
        }

        @Override
        public File getInterceptorOutput(String name) {
            return outputs.get(name);
        }
    }
}
