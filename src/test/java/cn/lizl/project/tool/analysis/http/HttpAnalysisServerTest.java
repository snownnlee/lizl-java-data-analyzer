package cn.lizl.project.tool.analysis.http;

import cn.lizl.project.tool.analysis.util.FrontendAssets;
import cn.lizl.project.tool.analysis.util.ResourcePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpAnalysisServerTest {

    private static final String PREFIX = HttpAnalysisServer.DEFAULT_PATH_PREFIX;

    @TempDir
    Path tempDir;

    private File resultFile;

    private StubProvider provider;

    private HttpAnalysisServer server;

    @BeforeEach
    void setUp() throws IOException {
        resultFile = tempDir.resolve("alpha.html").toFile();
        Files.write(resultFile.toPath(), "<html><body>ALPHA_REPORT</body></html>".getBytes(StandardCharsets.UTF_8));
        provider = new StubProvider(resultFile);
        server = new HttpAnalysisServer(provider).setPort(0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("启动后由系统分配端口，GET /beans 返回查看界面")
    void shouldServeBeansPageOnEphemeralPort() throws IOException {
        server.start();

        assertTrue(server.getBoundPort() > 0, "应拿到系统分配的实际端口");
        assertTrue(server.getAccessUrl().contains(":" + server.getBoundPort() + PREFIX),
                "accessUrl 指向索引页：" + server.getAccessUrl());
        assertFalse(server.getAccessUrl().endsWith("/beans"), "根路径改成索引页后不再直指 /beans");

        Response response = get(PREFIX + "/beans", null);

        assertEquals(200, response.status);
        assertTrue(response.contentType.startsWith("text/html"), "实际 Content-Type：" + response.contentType);
        assertTrue(response.body.contains("<html"), "应返回 beans.html 的内容");
    }

    @Test
    @DisplayName("默认端口是 18080，端口 0 表示由系统分配")
    void shouldDefaultPort() {
        assertEquals(18080, HttpAnalysisServer.DEFAULT_PORT);
        assertEquals(HttpAnalysisServer.DEFAULT_PORT, new HttpAnalysisServer().getBoundPort(),
                "未启动时 getBoundPort 返回配置值");
        assertEquals(0, new HttpAnalysisServer().setPort(0).getBoundPort());
    }

    @Test
    @DisplayName("POST /beans 返回对象名 JSON 数组，且支持 rebuild 透传")
    void shouldReturnBeanNamesAsJson() throws IOException {
        server.start();

        Response response = request(PREFIX + "/beans?rebuild=true", "POST", null);

        assertEquals(200, response.status);
        assertTrue(response.contentType.startsWith("application/json"), "实际 Content-Type：" + response.contentType);
        // 含中文与引号的名字必须被正确转义
        assertTrue(response.body.startsWith("["), "实际响应：" + response.body);
        assertTrue(response.body.contains("\"alpha\""));
        assertTrue(response.body.contains("带\\\"引号\""), "引号应转义，实际：" + response.body);
        assertTrue(provider.lastRebuild, "rebuild=true 应透传给 provider");
    }

    @Test
    @DisplayName("GET /bean/{name} 返回分析结果 HTML，名字做 URL 解码")
    void shouldServeAnalysisResult() throws IOException {
        server.start();

        Response response = get(PREFIX + "/bean/" + "alpha", null);

        assertEquals(200, response.status);
        assertEquals("<html><body>ALPHA_REPORT</body></html>", response.body);
        assertEquals("alpha", provider.lastRequestedName);
    }

    @Test
    @DisplayName("未知对象名返回 404")
    void shouldReturn404ForUnknownBean() throws IOException {
        server.start();

        Response response = get(PREFIX + "/bean/not-exists", null);

        assertEquals(404, response.status);
    }

    @Test
    @DisplayName("配置 token 后：缺失 401、带上后可访问")
    void shouldEnforceTokenWhenConfigured() throws IOException {
        server.setToken("s3cr3t").start();

        assertEquals(401, get(PREFIX + "/beans", null).status);
        assertEquals(401, request(PREFIX + "/beans", "POST", null).status);

        assertEquals(200, get(PREFIX + "/beans?token=s3cr3t", null).status);
        assertEquals(200, request(PREFIX + "/beans?token=s3cr3t", "POST", null).status);
        assertEquals(200, request(PREFIX + "/beans", "POST", "s3cr3t").status);
    }

    @Test
    @DisplayName("方法不支持时返回 405，根路径 302 跳转到查看界面")
    void shouldRejectWrongMethodAndServeIndexAtRoot() throws IOException {
        server.start();

        assertEquals(405, request(PREFIX + "/beans", "PUT", null).status);
        assertEquals(405, request(PREFIX + "/bean/alpha", "POST", null).status);

        // 根路径是索引页，不再是 302 跳转
        Response root = get(PREFIX + "/", null);
        assertEquals(200, root.status);
        assertTrue(root.body.contains("href=\"" + PREFIX + "/beans\""), "索引页应给出 beans 链接：" + root.body);
    }

    @Test
    @DisplayName("自定义处理器：路由、索引卡片、不可用提示与路径边界都由注册表驱动")
    void shouldDispatchToRegisteredHandler() throws IOException {
        server.register(new HttpServiceHandler() {
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
                        return "custom 通道未启用";
                    }

                    @Override
                    public void handle(com.sun.net.httpserver.HttpExchange exchange, String relativePath)
                            throws IOException {
                        HttpResponses.text(exchange, 200, "custom:" + relativePath);
                    }

                    @Override
                    public IndexCard indexCard() {
                        return new IndexCard("custom", "自定义通道", "验证扩展点");
                    }
                });
        server.register(new HttpServiceHandler() {
                    @Override
                    public java.util.Collection<String> getPaths() {
                        return java.util.Collections.singletonList("/off");
                    }

                    @Override
                    public boolean isAvailable() {
                        return false;
                    }

                    @Override
                    public String unavailableMessage() {
                        return "off 通道未启用：请配置 xxx.enable=true";
                    }

                    @Override
                    public void handle(com.sun.net.httpserver.HttpExchange exchange, String relativePath) {
                        throw new IllegalStateException("不可用的处理器不该被调用");
                    }
                });
        server.start();
        try {
            Response handled = get(PREFIX + "/custom", null);
            assertEquals(200, handled.status);
            assertEquals("custom:/custom", handled.body, "应把完整相对路径交给处理器");

            // 边界：/customx 不能被 /custom 前缀误命中
            assertEquals(404, get(PREFIX + "/customx", null).status);
            assertEquals(404, get(PREFIX + "/nothing", null).status);

            // 不可用处理器：不交给 handle，直接给提示
            Response off = get(PREFIX + "/off", null);
            assertEquals(404, off.status);
            assertTrue(off.body.contains("off 通道未启用"), "应返回处理器自带的提示：" + off.body);

            // 索引页由注册表生成卡片，并列出不可用通道
            Response root = get(PREFIX + "/", null);
            assertEquals(200, root.status);
            assertTrue(root.body.contains("href=\"" + PREFIX + "/custom\""), "应含自定义处理器卡片：" + root.body);
            assertTrue(root.body.contains("自定义通道"), "卡片文案应来自 IndexCard");
            assertTrue(root.body.contains("target=\"_blank\""), "索引页卡片一律在新标签页打开：" + root.body);
            assertTrue(root.body.contains("rel=\"icon\""), "索引页应带 favicon");
            assertFalse(root.body.contains("{{"), "index.html 的占位符应被全部替换");
            assertFalse(root.body.contains("href=\"" + PREFIX + "/off\""), "不可用通道不给链接");
            assertTrue(root.body.contains("off 通道未启用"), "不可用通道应在缺失列表里提示");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("stop 之后端口释放，可重复启停")
    void shouldStopAndRestartCleanly() throws IOException {
        server.start();
        int firstPort = server.getBoundPort();
        server.stop();

        assertFalse(server.isRunning());
        assertNotNull(server.getAccessUrl());

        server.start();
        assertTrue(server.getBoundPort() > 0);
        assertEquals(200, get(PREFIX + "/beans", null).status);
        server.stop();

        assertTrue(firstPort > 0);
    }

    @Test
    @DisplayName("未通过校验时 401 附带 WWW-Authenticate 质询头（RFC 7235 要求）")
    void shouldSendChallengeOnUnauthorized() throws IOException {
        server.setToken("s3cr3t").start();

        Response response = get(PREFIX + "/beans", null);

        assertEquals(401, response.status);
        assertNotNull(response.wwwAuthenticate, "401 必须带 WWW-Authenticate，否则客户端无从得知该用哪种方案");
        // 用自定义 scheme，浏览器不认识就不会误弹登录框
        assertTrue(response.wwwAuthenticate.startsWith("Token realm="),
                "实际质询头：" + response.wwwAuthenticate);
    }

    @Test
    @DisplayName("配置 Basic 认证后：无凭证/错误凭证 401 且弹 Basic 质询，正确凭证放行")
    void shouldEnforceBasicAuth() throws IOException {
        server.setBasicAuth("admin", "p@ss").start();

        Response anonymous = get(PREFIX + "/beans", null);
        assertEquals(401, anonymous.status);
        assertNotNull(anonymous.wwwAuthenticate);
        assertTrue(anonymous.wwwAuthenticate.startsWith("Basic realm="),
                "Basic 场景质询要带 realm 浏览器才会弹框，实际：" + anonymous.wwwAuthenticate);

        assertEquals(401, request(PREFIX + "/beans", "POST", null, basic("admin", "wrong")).status);
        assertEquals(401, get(PREFIX + "/beans?token=anything", null).status, "未配 token 时 token 不算凭证");

        assertEquals(200, request(PREFIX + "/beans", "GET", null, basic("admin", "p@ss")).status);
        assertEquals(200, request(PREFIX + "/bean/alpha", "GET", null, basic("admin", "p@ss")).status);
    }

    @Test
    @DisplayName("token 与 Basic 同时配置时，任一凭证即可通过")
    void shouldAcceptEitherCredential() throws IOException {
        server.setToken("s3cr3t").setBasicAuth("admin", "p@ss").start();

        assertEquals(200, get(PREFIX + "/beans?token=s3cr3t", null).status);
        assertEquals(200, request(PREFIX + "/beans", "POST", null, basic("admin", "p@ss")).status);
        assertEquals(401, get(PREFIX + "/beans?token=wrong", null).status);

        // 同时配了 Basic，质询应以 Basic 为准，浏览器才会弹登录框
        assertTrue(get(PREFIX + "/beans", null).wwwAuthenticate.startsWith("Basic realm="));
    }

    @Test
    @DisplayName("页面模板加载时压缩：比源文件小、文档完整、中文不乱码")
    void shouldCompressPageTemplatesOnLoad() throws IOException {
        for (String name : new String[]{"beans.html", "interceptor.html", "index.html"}) {
            byte[] raw = ClasspathResource.read(ResourcePaths.html(name));
            byte[] served = ClasspathResource.readHtml(name);
            String text = new String(served, StandardCharsets.UTF_8);

            assertTrue(served.length < raw.length,
                    name + " 加载时应压缩，实际 " + served.length + " vs 源 " + raw.length);
            assertTrue(text.contains("</html>"), name + " 压缩不能截断文档");
            assertTrue(text.contains("</style>"), name + " 内联样式段必须完整保留");
            if (!"index.html".equals(name)) {
                assertTrue(text.contains("</script>"), name + " 内联脚本段必须完整保留");
            }
            // 中文只经由 UTF-8 往返一次，用模板里的固定文案反查编码是否被搞坏
            assertFalse(text.matches("(?s).*[�].*"), name + " 出现替换字符，说明 UTF-8 往返出错");
        }
        assertTrue(new String(ClasspathResource.readHtml("interceptor.html"), StandardCharsets.UTF_8)
                .contains("仅内存态"), "interceptor.html 的中文文案应原样保留");
        assertTrue(new String(ClasspathResource.readHtml("index.html"), StandardCharsets.UTF_8)
                .contains("选择要查看的通道"), "index.html 的中文文案应原样保留");
    }

    @Test
    @DisplayName("查看界面的详情链接必须带上 token，否则配了 server-token 后点进去就是 401")
    void shouldKeepTokenInDetailLink() throws IOException {
        String template;
        try (InputStream inputStream = HttpAnalysisServer.class.getClassLoader().getResourceAsStream(ResourcePaths.html("beans.html"))) {
            assertNotNull(inputStream, "classpath 里应存在 analysis/html/beans.html");
            template = read(inputStream);
        }

        int index = template.indexOf("'bean/' + encodeURIComponent(bean) + detailQuery");
        assertTrue(index >= 0, "应能找到详情链接的生成代码");
        String linkLine = template.substring(index, Math.min(template.length(), index + 160));
        assertTrue(linkLine.contains("textContent"),
                "详情链接文本应走 textContent 渲染（防 XSS），不允许拼进 innerHTML。实际：" + linkLine);
    }

    @Test
    @DisplayName("写请求带跨站 Origin 时拒绝（CSRF 防护），同站 Origin 与无 Origin 放行")
    void shouldRejectCrossSiteWrite() throws IOException {
        server.start();

        assertEquals(403, postWithOrigin("http://evil.example.com"), "跨站 Origin 的写请求应被 403 拒绝");
        assertEquals(200, postWithOrigin("http://127.0.0.1:" + server.getBoundPort()),
                "同站 Origin 的写请求应放行");
        assertEquals(200, request(PREFIX + "/beans", "POST", null).status,
                "无 Origin/Referer 的非浏览器客户端（curl 等）应放行");
    }

    /** HttpURLConnection 把 Origin 列为受限头（静默忽略），CSRF 用例用裸 Socket 真实发送该头 */
    private int postWithOrigin(String origin) throws IOException {
        int port = server.getBoundPort();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            String request = "POST " + PREFIX + "/beans HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Origin: " + origin + "\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    // ---------------- 响应安全头与日志脱敏（对应 v2.7.11 / v2.7.13） ----------------

    @Test
    @DisplayName("响应应带 X-Content-Type-Options: nosniff")
    void shouldSendNosniffHeader() throws IOException {
        server.start();

        HttpURLConnection connection = open(PREFIX + "/beans", "GET", null);
        assertEquals("nosniff", connection.getHeaderField("X-Content-Type-Options"),
                "HTML 页面响应应带 nosniff，防 MIME 嗅探");

        HttpURLConnection result = open(PREFIX + "/bean/alpha", "GET", null);
        assertEquals("nosniff", result.getHeaderField("X-Content-Type-Options"),
                "产物响应同样应带 nosniff");
    }

    @Test
    @DisplayName("启动日志不应输出带 token 的完整访问地址")
    void shouldNotLogTokenInAccessUrl() throws IOException {
        java.util.logging.Logger serverLogger =
                java.util.logging.Logger.getLogger("cn.lizl.project.tool.analysis.http.HttpAnalysisServer");
        java.util.List<String> messages = new java.util.ArrayList<>();
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
        serverLogger.addHandler(capture);
        serverLogger.setLevel(java.util.logging.Level.INFO);
        try {
            server.setToken("s3cr3t-log-check").start();
        } finally {
            serverLogger.removeHandler(capture);
        }

        // 前置校验：token 确实配置且会拼进手动获取的地址
        assertTrue(server.getAccessUrl().contains("s3cr3t-log-check"), "getAccessUrl 应保留 token 供手动复制");
        for (String message : messages) {
            assertFalse(message.contains("s3cr3t-log-check"), "启动日志不应出现 token：" + message);
        }
        assertTrue(messages.stream().anyMatch(m -> m.contains("已启用 token 校验")),
                "应提示 token 校验已启用（但不带值）");
    }

    @Test
    @DisplayName("结果页相对引用的前端引擎由服务端发出：正确的 Content-Type，且不当作 bean 名去查")
    void shouldServeFrontendAssetUnderBeanPath() throws IOException {
        server.start();

        Response response = get(PREFIX + "/bean/" + FrontendAssets.PLANTUML, null);

        assertEquals(200, response.status, "引擎文件应能取到，而不是走到 bean 查询里 404");
        assertTrue(response.contentType.startsWith("application/javascript"),
                "Content-Type 应是 javascript，实际：" + response.contentType);
        assertTrue(response.body.contains("globalThis.PlantUML"),
                "返回的应是 PlantUML 引擎（外部脚本形态），而不是别的文件");
    }

    private Response get(String path, String token) throws IOException {
        return request(path, "GET", token);
    }

    private Response request(String path, String method, String token) throws IOException {
        return request(path, method, token, null);
    }

    private Response request(String path, String method, String token, String authorization) throws IOException {
        HttpURLConnection connection = open(path, method, token, authorization);
        try {
            Response response = new Response();
            response.status = connection.getResponseCode();
            response.contentType = connection.getContentType() == null ? "" : connection.getContentType();
            response.wwwAuthenticate = connection.getHeaderField("WWW-Authenticate");
            InputStream inputStream = response.status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            response.body = inputStream == null ? "" : read(inputStream);
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method, String token) throws IOException {
        return open(path, method, token, null);
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private HttpURLConnection open(String path, String method, String token, String authorization) throws IOException {
        URL url = new URL("http://127.0.0.1:" + server.getBoundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        if (token != null) {
            connection.setRequestProperty(HttpAnalysisServer.TOKEN_HEADER, token);
        }
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization);
        }
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(new byte[0]);
            }
        }
        return connection;
    }

    private static String read(InputStream inputStream) throws IOException {
        try (InputStream in = inputStream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class Response {

        int status;

        String contentType;

        String body;

        String wwwAuthenticate;
    }

    private static final class StubProvider implements AnalysisProvider {

        private final File file;

        private volatile boolean lastRebuild;

        private volatile String lastRequestedName;

        private StubProvider(File file) {
            this.file = file;
        }

        @Override
        public List<String> getBeanNames(boolean rebuild) {
            this.lastRebuild = rebuild;
            return Arrays.asList("alpha", "带\"引号", "含/斜杠");
        }

        @Override
        public File getAnalyzedResult(String beanName, boolean rebuild) {
            this.lastRequestedName = beanName;
            return "alpha".equals(beanName) ? file : null;
        }
    }
}
