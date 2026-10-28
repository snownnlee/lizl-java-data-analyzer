package cn.lizl.project.tool.analysis.spring.server;

import cn.lizl.project.tool.analysis.http.HttpAnalysisServer;
import cn.lizl.project.tool.analysis.http.HttpResponses;
import cn.lizl.project.tool.analysis.http.HttpServiceHandler;
import cn.lizl.project.tool.analysis.spring.beans.BeansAutoConfiguration;
import cn.lizl.project.tool.analysis.spring.beans.JavaDataAnalyzerLifecycleHandler;
import cn.lizl.project.tool.analysis.spring.interceptor.InterceptorAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 服务的装配与端点组隔离：一台 server、一个端口，端点组按各自通道是否装配来决定挂不挂。
 */
class ServerAutoConfigurationTest {

    private static final String BEANS_ENABLE = "cn.lizl.java-data-analyzer.beans.enable";
    private static final String INTERCEPTOR_ENABLE = "cn.lizl.java-data-analyzer.interceptor.enable";
    private static final String SERVER_ENABLE = "cn.lizl.java-data-analyzer.server.enable";
    private static final String SERVER_PORT = "cn.lizl.java-data-analyzer.server.port";

    @Test
    @DisplayName("server 与 beans 都开：非 Web 上下文也能起端口，/beans 组可用，关闭时释放")
    void shouldServeBeansGroup(@TempDir Path tempDir) throws IOException {
        AnnotationConfigApplicationContext context = context(tempDir,
                SERVER_ENABLE, "true", SERVER_PORT, "0", BEANS_ENABLE, "true");
        HttpAnalysisServer server;
        try {
            server = context.getBean(HttpAnalysisServer.class);
            assertNotNull(server);
            assertTrue(server.isRunning(), "上下文刷新后服务应已启动");
            assertTrue(server.getBoundPort() > 0, "应拿到系统分配的实际端口");

            String baseUrl = baseUrl(server);
            assertEquals(200, statusOf(baseUrl + "/beans", "GET"), "beans 通道开着，查看界面应可访问");
            assertTrue(bodyOf(baseUrl + "/beans", "POST").startsWith("["), "应返回 JSON 数组");
            assertEquals(404, statusOf(baseUrl + "/interceptor", "GET"), "拦截器通道没开，该组应 404");
            assertTrue(bodyOfAllowError(baseUrl + "/interceptor", "GET").contains("interceptor 通道未启用"),
                    "应明确提示是通道没启用，而不是干巴巴的 not found");
        } finally {
            context.close();
        }
        assertFalse(server.isRunning(), "上下文关闭后服务应被销毁");
    }

    @Test
    @DisplayName("只开 interceptor：同一台 server 上 /interceptor 可用，/beans 组 404")
    void shouldServeInterceptorGroupOnly(@TempDir Path tempDir) throws IOException {
        AnnotationConfigApplicationContext context = context(tempDir,
                SERVER_ENABLE, "true", SERVER_PORT, "0", INTERCEPTOR_ENABLE, "true",
                "cn.lizl.java-data-analyzer.interceptor.active", "true");
        try {
            String baseUrl = baseUrl(context.getBean(HttpAnalysisServer.class));
            assertEquals(200, statusOf(baseUrl + "/interceptor", "GET"), "拦截器通道开着，管理页应可访问");
            assertEquals(404, statusOf(baseUrl + "/beans", "GET"), "beans 通道没开，该组应 404");
            assertTrue(bodyOfAllowError(baseUrl + "/beans", "GET").contains("beans 通道未启用"),
                    "应明确提示是通道没启用");
            assertEquals(404, statusOf(baseUrl + "/bean/anyBean", "GET"), "单个 Bean 结果也应 404");
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("server 开但两个通道都关：服务照起，全部端点 404")
    void shouldServeNothingWhenNoChannelEnabled(@TempDir Path tempDir) throws IOException {
        AnnotationConfigApplicationContext context = context(tempDir,
                SERVER_ENABLE, "true", SERVER_PORT, "0");
        try {
            HttpAnalysisServer server = context.getBean(HttpAnalysisServer.class);
            String baseUrl = baseUrl(server);
            assertEquals(404, statusOf(baseUrl + "/beans", "GET"));
            assertEquals(404, statusOf(baseUrl + "/interceptor", "GET"));
            assertEquals(404, statusOf(baseUrl, "GET"), "根路径也不该跳到一个必然 404 的页面");
            assertTrue(bodyOfAllowError(baseUrl, "GET").contains("都未启用"), "根路径应提示两个通道都没启用");
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("根路径：只列出已装配的通道，点了就能进；两个都装配时给两个链接")
    void shouldListEnabledChannelsOnRoot(@TempDir Path tempDir) throws IOException {
        // 只开 beans
        chkRoot(tempDir, new String[]{BEANS_ENABLE, "true"},
                "/lizl-java-data-analyzer/beans", "/lizl-java-data-analyzer/interceptor");
        // 只开 interceptor
        chkRoot(tempDir, new String[]{INTERCEPTOR_ENABLE, "true"},
                "/lizl-java-data-analyzer/interceptor", "/lizl-java-data-analyzer/beans");
        // 两个都开
        AnnotationConfigApplicationContext context = context(tempDir,
                SERVER_ENABLE, "true", SERVER_PORT, "0", BEANS_ENABLE, "true", INTERCEPTOR_ENABLE, "true");
        try {
            String body = bodyOf(baseUrl(context.getBean(HttpAnalysisServer.class)), "GET");
            assertTrue(body.contains("href=\"/lizl-java-data-analyzer/beans\""), "应含 beans 链接：" + body);
            assertTrue(body.contains("href=\"/lizl-java-data-analyzer/interceptor\""), "应含 interceptor 链接：" + body);
            assertTrue(body.contains("rel=\"icon\""), "索引页应有 favicon");
            assertEquals(2, countOf(body, "target=\"_blank\""), "两张卡片都应在新的标签页打开：" + body);
            assertFalse(body.contains("未装配的通道"), "两个都装配了就不该出现缺失提示");
            assertFalse(body.contains("{{"), "模板占位符应被全部替换：" + body);
        } finally {
            context.close();
        }
    }

    /** 根路径断言：必须给出 enabledHref，且不能出现 disabledHref 作为链接 */
    private void chkRoot(Path tempDir, String[] channel, String enabledHref, String disabledHref) throws IOException {
        String[] pairs = new String[channel.length + 4];
        pairs[0] = SERVER_ENABLE;
        pairs[1] = "true";
        pairs[2] = SERVER_PORT;
        pairs[3] = "0";
        System.arraycopy(channel, 0, pairs, 4, channel.length);
        AnnotationConfigApplicationContext context = context(tempDir, pairs);
        try {
            String baseUrl = baseUrl(context.getBean(HttpAnalysisServer.class));
            assertEquals(200, statusOf(baseUrl, "GET"), "有通道装配时根路径应返回索引页");
            String body = bodyOf(baseUrl, "GET");
            assertTrue(body.contains("href=\"" + enabledHref + "\""), "应含已装配通道的链接：" + body);
            assertFalse(body.contains("href=\"" + disabledHref + "\""), "未装配通道不应给链接：" + body);
            assertTrue(body.contains("未装配的通道"), "应提示另一个通道未装配");
            assertFalse(body.contains("{{"), "模板占位符应被全部替换：" + body);
            assertTrue(body.contains("rel=\"icon\""), "索引页应有 favicon：与 beans/interceptor 页同一条内联 svg");
            assertTrue(body.contains("target=\"_blank\""), "通道卡片应在新标签页打开：" + body);
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("端口被占用时宿主启动一起失败（配上 server.enable 就必须真的起来）")
    void shouldFailContextWhenPortOccupied(@TempDir Path tempDir) throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int occupied = socket.getLocalPort();
            AnnotationConfigApplicationContext context = contextWithoutRefresh(tempDir, new Class<?>[0],
                    SERVER_ENABLE, "true", SERVER_PORT, String.valueOf(occupied), BEANS_ENABLE, "true");
            try {
                Throwable failure = assertThrows(Throwable.class, context::refresh,
                        "端口被占用时不应静默降级，必须让上下文启动失败");
                String message = fullMessage(failure);
                assertTrue(message.contains("独立 HTTP 服务启动失败"), "异常应点明是哪个服务失败：" + message);
                assertTrue(message.contains(String.valueOf(occupied)), "异常应带上冲突端口：" + message);
                assertTrue(message.contains("server.port"), "异常应给出可操作建议（换端口）：" + message);
            } finally {
                context.close();
            }
        }
    }

    @Test
    @DisplayName("三个开关都不配时完全不装配，不建 server、不建采集器")
    void shouldStayInertByDefault() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(BeansAutoConfiguration.class, InterceptorAutoConfiguration.class,
                ServerAutoConfiguration.class);
        context.refresh();
        try {
            assertEquals(0, context.getBeanNamesForType(HttpAnalysisServer.class).length, "默认不应创建 HTTP 服务");
            assertEquals(0, context.getBeanNamesForType(JavaDataAnalyzerLifecycleHandler.class).length, "默认不应装配 beans 通道");
            assertEquals(0, context.getBeanNamesForType(HttpServiceHandler.class).length,
                    "没有 HTTP 服务就不该有处理器 Bean");
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("容器里任意 HttpServiceHandler Bean 都会被自动挂载，服务器配置不认识具体通道")
    void shouldMountAnyHandlerBean(@TempDir Path tempDir) throws IOException {
        AnnotationConfigApplicationContext context = contextWith(tempDir,
                new Class<?>[]{SampleHandlerConfiguration.class},
                SERVER_ENABLE, "true", SERVER_PORT, "0");
        try {
            String baseUrl = baseUrl(context.getBean(HttpAnalysisServer.class));
            Response custom = get(baseUrl + "/custom", "GET");
            assertEquals(200, custom.status, "自定义 handler 应被挂载");
            assertEquals("sample:/custom", custom.body);
            assertTrue(bodyOf(baseUrl, "GET").contains("/custom"), "索引页应自动给出它的卡片");
        } finally {
            context.close();
        }
    }

    /** 自定义处理器：只实现契约，不碰任何通道配置 */
    static final class SampleHandler implements HttpServiceHandler {
        @Override
        public java.util.Collection<String> getPaths() {
            return java.util.Collections.singletonList("/custom");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String unavailableMessage() {
            return "sample 未启用";
        }

        @Override
        public void handle(com.sun.net.httpserver.HttpExchange exchange, String relativePath) throws IOException {
            HttpResponses.text(exchange, 200, "sample:" + relativePath);
        }

        @Override
        public IndexCard indexCard() {
            return new IndexCard("sample", "示例通道", "验证处理器收集");
        }
    }

    @org.springframework.context.annotation.Configuration
    static class SampleHandlerConfiguration {
        @Bean
        HttpServiceHandler sampleHandler() {
            return new SampleHandler();
        }
    }

    @Test
    @DisplayName("处理器 Bean 的存在只取决于 HTTP 服务：服务在、通道关时仍注册，用于返回「通道未启用」")
    void shouldRegisterHandlersWheneverServerEnabled(@TempDir Path tempDir) {
        AnnotationConfigApplicationContext context = context(tempDir, SERVER_ENABLE, "true", SERVER_PORT, "0");
        try {
            // 两个通道都没开，但服务在：两个处理器仍要注册，否则访问该组只剩 not found
            assertEquals(2, context.getBeanNamesForType(HttpServiceHandler.class).length,
                    "服务启用时通道处理器都应注册，用于给出「通道未启用」提示");
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("通道开着但 server 关着：不起服务，但必须给出明确的启动提示")
    void shouldWarnWhenServerDisabledButChannelsEnabled(@TempDir Path tempDir) {
        java.util.List<String> messages = new java.util.ArrayList<>();
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(BeansAutoConfiguration.class.getName());
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                messages.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        AnnotationConfigApplicationContext context = context(tempDir, BEANS_ENABLE, "true");
        try {
            assertEquals(0, context.getBeanNamesForType(HttpAnalysisServer.class).length,
                    "server 没开就不该建服务");
            assertEquals(0, context.getBeanNamesForType(HttpServiceHandler.class).length,
                    "server 没开时处理器 Bean 没有消费者，不该注册");
            assertTrue(messages.stream().anyMatch(m -> m.contains("HTTP 服务未开启")),
                    "应提示 HTTP 服务未开启：" + messages);
            assertTrue(messages.stream().anyMatch(m -> m.contains("lazy=true")),
                    "应点名 lazy=true 时永远不会产生产物：" + messages);
        } finally {
            logger.removeHandler(capture);
            context.close();
        }
    }

    @Test
    @DisplayName("两个通道都没开时不打这条提示，避免噪声")
    void shouldNotWarnWhenNoChannelEnabled(@TempDir Path tempDir) {
        java.util.List<String> messages = new java.util.ArrayList<>();
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(BeansAutoConfiguration.class.getName());
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                messages.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        AnnotationConfigApplicationContext context = context(tempDir);
        try {
            assertTrue(messages.isEmpty(), "没有通道启用就不该提示：" + messages);
        } finally {
            logger.removeHandler(capture);
            context.close();
        }
    }

    @Test
    @DisplayName("server.enable 的 enable/enabled 两种拼写都认（Binder 松散绑定）")
    void shouldAcceptBothSpellings(@TempDir Path tempDir) {
        for (String key : new String[]{"cn.lizl.java-data-analyzer.server.enable",
                "cn.lizl.java-data-analyzer.server.enabled"}) {
            AnnotationConfigApplicationContext context = context(tempDir, key, "true", SERVER_PORT, "0");
            try {
                assertEquals(1, context.getBeanNamesForType(HttpAnalysisServer.class).length,
                        key + " 应能开启服务");
            } finally {
                context.close();
            }
        }
    }

    private AnnotationConfigApplicationContext context(Path tempDir, String... pairs) {
        return contextWith(tempDir, new Class<?>[0], pairs);
    }

    private AnnotationConfigApplicationContext contextWith(Path tempDir, Class<?>[] extraConfigs, String... pairs) {
        AnnotationConfigApplicationContext context = contextWithoutRefresh(tempDir, extraConfigs, pairs);
        context.refresh();
        return context;
    }

    /** 只登记不 refresh：用于断言 refresh 阶段就失败（如端口被占用） */
    private AnnotationConfigApplicationContext contextWithoutRefresh(Path tempDir, Class<?>[] extraConfigs,
                                                                    String... pairs) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("cn.lizl.java-data-analyzer.beans.output-root-dir", tempDir.toString());
        properties.put("cn.lizl.java-data-analyzer.beans.eager-to-analyze", "true");
        properties.put("cn.lizl.java-data-analyzer.beans.lazy", "true");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            properties.put(pairs[i], pairs[i + 1]);
        }
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(BeansAutoConfiguration.class, InterceptorAutoConfiguration.class,
                ServerAutoConfiguration.class);
        for (Class<?> extra : extraConfigs) {
            context.register(extra);
        }
        return context;
    }

    /** 沿异常链把消息拼起来：Spring 会把 Bean 创建失败层层包装 */
    private static String fullMessage(Throwable throwable) {
        StringBuilder text = new StringBuilder();
        Throwable current = throwable;
        while (current != null && current != current.getCause()) {
            text.append(current.getMessage()).append(" | ");
            current = current.getCause();
        }
        return text.toString();
    }

    /** 简单响应包装：状态码 + body */
    static final class Response {
        int status;
        String body;
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }

    private Response get(String url, String method) throws IOException {
        HttpURLConnection connection = open(url, method);
        try {
            Response response = new Response();
            response.status = connection.getResponseCode();
            InputStream stream = response.status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (stream != null) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                stream.close();
            }
            response.body = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private String baseUrl(HttpAnalysisServer server) {
        return "http://127.0.0.1:" + server.getBoundPort() + HttpAnalysisServer.DEFAULT_PATH_PREFIX;
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

    /** 4xx 响应没有正常输入流，改读 errorStream */
    private String bodyOfAllowError(String url, String method) throws IOException {
        HttpURLConnection connection = open(url, method);
        try {
            InputStream stream = connection.getResponseCode() >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) {
                return "";
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            stream.close();
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
}
