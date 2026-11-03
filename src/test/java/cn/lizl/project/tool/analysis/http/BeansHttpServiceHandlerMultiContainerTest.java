package cn.lizl.project.tool.analysis.http;

import cn.lizl.project.tool.analysis.util.FrontendAssets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BeansHttpServiceHandler 的多容器（多 {@link AnalysisProvider}）行为：容器选择页、
 * {@code container} 参数转发、单容器完全兼容、未知容器 404、运行期增删容器。
 */
class BeansHttpServiceHandlerMultiContainerTest {

    private static final String PREFIX = HttpAnalysisServer.DEFAULT_PATH_PREFIX;

    @TempDir
    Path tempDir;

    private HttpAnalysisServer server;

    /** 挂到 server 上的 beans 处理器：容器增删直接对它操作，不再经由服务器转发 */
    private BeansHttpServiceHandler beansService;

    @BeforeEach
    void setUp() {
        server = new HttpAnalysisServer().setPort(0);
        beansService = new BeansHttpServiceHandler();
        server.register(beansService);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private StubProvider registerTwoContainers() throws IOException {
        StubProvider first = new StubProvider(tempDir, "spring", "Spring 自动采集", "alpha");
        StubProvider second = new StubProvider(tempDir, "manual", null, "beta");
        beansService.addProvider(first);
        beansService.addProvider(second);
        return first;
    }

    @Test
    @DisplayName("多容器且未指定 container：GET /beans 返回容器选择页")
    void shouldShowContainerSelectionWhenMultiple() throws IOException {
        registerTwoContainers();
        server.start();

        Response response = get(PREFIX + "/beans");

        assertEquals(200, response.status);
        assertTrue(response.contentType.startsWith("text/html"), "实际 Content-Type：" + response.contentType);
        assertTrue(response.body.contains("聚合容器"), "应为容器选择页：" + response.body);
        assertTrue(response.body.contains("container=spring"), "应有 spring 容器链接");
        assertTrue(response.body.contains("container=manual"), "应有 manual 容器链接");
        assertTrue(response.body.contains("target=\"_blank\""), "容器卡片应在新窗口打开：" + response.body);
        assertTrue(response.body.contains("2 个 Bean 聚合容器"), "页头应显示实际容器数量：" + response.body);
    }

    @Test
    @DisplayName("多容器选择页：展示名优先于注册名，bean 数量入卡片")
    void shouldPreferDisplayNameOnSelectionPage() throws IOException {
        registerTwoContainers();
        server.start();

        Response response = get(PREFIX + "/beans");

        assertTrue(response.body.contains("Spring 自动采集"), "应展示 getDisplayName");
        assertTrue(response.body.contains("spring"), "注册名仍应出现在 meta 里");
        assertTrue(response.body.contains("manual"), "无展示名的容器用注册名");
        assertTrue(response.body.contains("1 个 Bean"), "bean 数量应入卡片");
    }

    @Test
    @DisplayName("配置 token 时选择页链接携带 token，按链接跳转不被 401 挡回")
    void shouldCarryTokenOnSelectionLinks() throws IOException {
        registerTwoContainers();
        server.setToken("s3cr3t");
        server.start();

        Response page = get(PREFIX + "/beans?token=s3cr3t");
        assertTrue(page.body.contains("container=spring&amp;token=s3cr3t"),
                "选择页链接应带 token：" + page.body);

        // href 源码里的 &amp; 是 HTML 转义，浏览器发出请求前会还原成 &
        String href = extractHref(page.body, "container=spring").replace("&amp;", "&");
        Response followed = get(PREFIX + "/" + href);
        assertEquals(200, followed.status, "按选择页链接跳转应可用");
        assertTrue(followed.body.contains("searchInput"), "跳转目标应是 bean 列表页");
    }

    @Test
    @DisplayName("多容器带 container：GET /beans 返回该容器 bean 列表页")
    void shouldShowListPageForNamedContainer() throws IOException {
        registerTwoContainers();
        server.start();

        Response response = get(PREFIX + "/beans?container=manual");

        assertEquals(200, response.status);
        assertTrue(response.body.contains("searchInput"), "应为 bean 列表页（beans.html）");
        assertFalseContains(response.body, "聚合容器", "不应再是容器选择页");
    }

    @Test
    @DisplayName("多容器 POST /beans 不带 container：返回容器注册名数组")
    void shouldReturnContainerNamesWhenPostWithoutContainer() throws IOException {
        registerTwoContainers();
        server.start();

        Response response = request(PREFIX + "/beans", "POST");

        assertEquals(200, response.status);
        assertTrue(response.contentType.startsWith("application/json"));
        assertTrue(response.body.startsWith("["), "实际响应：" + response.body);
        assertTrue(response.body.contains("\"spring\""));
        assertTrue(response.body.contains("\"manual\""));
        assertFalseContains(response.body, "alpha", "容器名列表里不应混入 bean 名");
    }

    @Test
    @DisplayName("多容器 POST /beans 带 container：返回该容器名单，rebuild 只透传给目标容器")
    void shouldReturnNamedContainerBeanNames() throws IOException {
        StubProvider spring = registerTwoContainers();
        server.start();

        Response response = request(PREFIX + "/beans?container=manual&rebuild=true", "POST");

        assertEquals(200, response.status);
        assertTrue(response.body.contains("\"beta\""), "实际响应：" + response.body);
        assertFalseContains(response.body, "alpha", "不应串到 spring 容器的名单");
        assertFalse(spring.lastRebuild, "rebuild 只应传给 manual，不应波及 spring");
    }

    @Test
    @DisplayName("多容器访问不存在的容器：POST 与 GET /bean 都 404")
    void shouldReturn404ForUnknownContainer() throws IOException {
        registerTwoContainers();
        server.start();

        Response posted = request(PREFIX + "/beans?container=nope", "POST");
        assertEquals(404, posted.status);
        assertTrue(posted.body.contains("聚合容器不存在"), posted.body);

        Response detail = get(PREFIX + "/bean/alpha?container=nope");
        assertEquals(404, detail.status);
        assertTrue(detail.body.contains("聚合容器不存在"), detail.body);
    }

    @Test
    @DisplayName("多容器 bean 详情不带 container：404 并提示可选容器")
    void shouldReturn404WithHintWhenContainerMissingOnDetail() throws IOException {
        registerTwoContainers();
        server.start();

        Response response = get(PREFIX + "/bean/alpha");

        assertEquals(404, response.status);
        assertTrue(response.body.contains("container="), "应提示用 container 参数：" + response.body);
        assertTrue(response.body.contains("spring") && response.body.contains("manual"),
                "应列出可选容器：" + response.body);
    }

    @Test
    @DisplayName("多容器 bean 详情带 container：返回对应容器的分析结果")
    void shouldReturnResultFromNamedContainer() throws IOException {
        registerTwoContainers();
        server.start();

        Response alpha = get(PREFIX + "/bean/alpha?container=spring");
        assertEquals(200, alpha.status);
        assertEquals("spring:alpha", alpha.body);

        Response beta = get(PREFIX + "/bean/beta?container=manual");
        assertEquals(200, beta.status);
        assertEquals("manual:beta", beta.body);
    }

    @Test
    @DisplayName("多容器下前端引擎资源不带 container 也照常返回")
    void shouldServeFrontendAssetWithoutContainer() throws IOException {
        registerTwoContainers();
        server.start();

        Response response = get(PREFIX + "/bean/" + FrontendAssets.MERMAID);

        assertEquals(200, response.status, "资源请求没有 container 参数，必须先于容器解析拦截");
        assertTrue(response.contentType.startsWith("application/javascript"));
    }

    @Test
    @DisplayName("单容器也走同一套路径：先出选择页，API 不带 container 时给容器名而非 bean 名")
    void shouldShowSelectionEvenForSingleContainer() throws IOException {
        beansService.addProvider(new StubProvider(tempDir, "spring", "Spring 自动采集", "alpha"));
        server.start();

        Response page = get(PREFIX + "/beans");
        assertEquals(200, page.status);
        assertTrue(page.body.contains("聚合容器"), "单容器也应出容器选择页：" + page.body);
        assertTrue(page.body.contains("1 个 Bean 聚合容器"), "单容器时数量也按实际显示：" + page.body);
        assertTrue(page.body.contains("container=spring"), "选择页应有容器链接");

        Response selected = get(PREFIX + "/beans?container=spring");
        assertTrue(selected.body.contains("searchInput"), "带 container 才是 bean 列表页");

        Response names = request(PREFIX + "/beans", "POST");
        assertTrue(names.body.startsWith("["), "POST /beans 仍是 JSON 数组：" + names.body);
        assertTrue(names.body.contains("\"spring\""), "不带 container 恒返回容器名：" + names.body);
        assertFalseContains(names.body, "\"alpha\"", "不应返回 bean 名单");

        Response beanNames = request(PREFIX + "/beans?container=spring", "POST");
        assertTrue(beanNames.body.contains("\"alpha\""), "带 container 才返回该容器的 bean 名");

        Response detail = get(PREFIX + "/bean/alpha");
        assertEquals(404, detail.status, "不带 container 的详情不再兜底");
        assertTrue(detail.body.contains("container="), "应提示补 container 参数：" + detail.body);

        Response withContainer = get(PREFIX + "/bean/alpha?container=spring");
        assertEquals(200, withContainer.status);
        assertEquals("spring:alpha", withContainer.body);
    }

    @Test
    @DisplayName("运行期追加容器：选择页立即多出卡片")
    void shouldAddContainerAtRuntime() throws IOException {
        beansService.addProvider(new StubProvider(tempDir, "spring", null, "alpha"));
        server.start();

        assertTrue(get(PREFIX + "/beans").body.contains("container=spring"), "单容器时选择页也应可用");

        beansService.addProvider(new StubProvider(tempDir, "manual", null, "beta"));

        Response page = get(PREFIX + "/beans");
        assertTrue(page.body.contains("container=spring"), "追加后 spring 仍应在：" + page.body);
        assertTrue(page.body.contains("container=manual"), "追加后 manual 应出现：" + page.body);
    }

    @Test
    @DisplayName("removeProvider 移除容器：选择页少一张卡片，被移除的容器名从 API 消失")
    void shouldRemoveContainer() throws IOException {
        registerTwoContainers();
        server.start();

        beansService.removeProvider("manual");

        Response page = get(PREFIX + "/beans");
        assertTrue(page.body.contains("container=spring"), "剩下的容器仍应可选");
        assertFalseContains(page.body, "container=manual", "被移除的容器不应还在选择页");

        Response names = request(PREFIX + "/beans", "POST");
        assertTrue(names.body.contains("\"spring\""), "实际响应：" + names.body);
        assertFalseContains(names.body, "\"manual\"", "容器应已被移除");

        assertEquals(404, get(PREFIX + "/bean/alpha").status, "详情仍需 container");
        assertEquals(200, get(PREFIX + "/bean/alpha?container=spring").status);
    }

    @Test
    @DisplayName("只挂空容器表的处理器：通道不可用，返回 404 + 开启提示")
    void shouldStayUnavailableWithoutProvider() throws IOException {
        // setUp 里注册的 beansService 未登记任何容器，等价于原来"只挂 null provider"
        server.start();

        Response response = get(PREFIX + "/beans");
        assertEquals(404, response.status);
        assertTrue(response.body.contains("beans 通道未启用"), response.body);
    }

    @Test
    @DisplayName("addProvider 拒绝 null provider 与空白容器名")
    void shouldRejectBlankContainerName() throws IOException {
        BeansHttpServiceHandler service = new BeansHttpServiceHandler();
        assertThrows(IllegalArgumentException.class, () -> service.addProvider(null));
        StubProvider blankNamed = new StubProvider(tempDir, "  ", null, "alpha");
        assertThrows(IllegalArgumentException.class, () -> service.addProvider(blankNamed));
    }

    private static String extractHref(String body, String marker) {
        int from = body.indexOf(marker);
        assertTrue(from >= 0, "找不到含 " + marker + " 的链接");
        int start = body.lastIndexOf("href=\"", from);
        int hrefStart = start + "href=\"".length();
        int end = body.indexOf('"', hrefStart);
        return body.substring(hrefStart, end);
    }

    private void assertFalseContains(String body, String unexpected, String message) {
        assertFalse(body.contains(unexpected), message + "（不应包含：" + unexpected + "）");
    }

    private Response get(String path) throws IOException {
        return request(path, "GET");
    }

    private Response request(String path, String method) throws IOException {
        HttpURLConnection connection = open(path, method);
        try {
            Response response = new Response();
            response.status = connection.getResponseCode();
            response.contentType = connection.getContentType() == null ? "" : connection.getContentType();
            InputStream inputStream = response.status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            response.body = inputStream == null ? "" : read(inputStream);
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        URL url = new URL("http://127.0.0.1:" + server.getBoundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
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
    }

    /**
     * 结果走真实临时文件：{@code HttpResponses.file} 按 {@code Files.copy} 发送，
     * 不存在的路径会让请求掉进 500。文件内容 {@code tag:beanName}，用于断言没串容器。
     */
    private static final class StubProvider implements AnalysisProvider {

        private final String tag;

        private final String displayName;

        private final String beanName;

        private final File file;

        private volatile boolean lastRebuild;

        private StubProvider(Path dir, String tag, String displayName, String beanName) throws IOException {
            this.tag = tag;
            this.displayName = displayName;
            this.beanName = beanName;
            Path path = dir.resolve("stub-" + tag + "-" + beanName + ".html");
            Files.write(path, (tag + ":" + beanName).getBytes(StandardCharsets.UTF_8));
            this.file = path.toFile();
        }

        @Override
        public String getName() {
            // tag 既是结果前缀也是容器注册名，与各用例里 container= 的取值一致
            return tag;
        }

        @Override
        public List<String> getBeanNames(boolean rebuild) {
            this.lastRebuild = rebuild;
            return Arrays.asList(beanName);
        }

        @Override
        public File getAnalyzedResult(String name, boolean rebuild) {
            return beanName.equals(name) ? file : null;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }
    }
}
