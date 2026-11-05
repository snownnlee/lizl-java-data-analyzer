package cn.lizl.project.tool.analysis.http;

import cn.lizl.project.tool.analysis.util.FrontendAssets;
import cn.lizl.project.tool.analysis.util.HtmlTemplates;
import cn.lizl.project.tool.analysis.util.HtmlTreeAssets;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * beans 端点的 HTTP 处理器：{@code GET /beans} 页面、{@code POST /beans} 名单、
 * {@code GET /bean/{name}} 单个 Bean 的分析结果。
 *
 * <p>内部持有一个或多个 {@link AnalysisProvider}，每个是一个具名的 bean 聚合容器。浏览路径与容器数量无关：
 * {@code GET /beans} 永远先出容器选择页，之后各请求用 query 参数 {@code container=} 指定容器；
 * 不带 {@code container} 的 {@code POST /beans} 返回容器名列表，{@code GET /bean/{name}} 则 404 并提示补参数。
 *
 * <p>只依赖 {@link AnalysisProvider} 接口，不依赖任何框架，也不碰服务器内部状态。
 * 公开给 Spring 集成侧复用（由 {@code BeansAutoConfiguration} 注册成 {@link HttpServiceHandler} Bean）。
 */
public final class BeansHttpServiceHandler implements HttpServiceHandler {

    static final String BEANS_PATH = "/beans";

    static final String BEAN_PATH_PREFIX = "/bean/";

    static final String CONTAINER_QUERY_PARAM = "container";

    private static final String LIST_TEMPLATE_NAME = "beans.html";

    private static final String CONTAINERS_TEMPLATE_NAME = "beans-containers.html";

    private static final String CARDS_PLACEHOLDER = "{{cards}}";

    private static final String CONTAINER_COUNT_PLACEHOLDER = "{{count}}";

    /**
     * 路由前缀：入口页 {@code /beans} 与详情前缀 {@code /bean/}。常量固定复用——默认 {@code supports}
     * 每个请求都会遍历它，不能每次调用现造一个列表。
     */
    private static final List<String> PATH_PREFIXES =
            Collections.unmodifiableList(Arrays.asList(BEANS_PATH, BEAN_PATH_PREFIX));

    private static final Logger LOG = Logger.getLogger(BeansHttpServiceHandler.class.getName());

    /**
     * 容器注册名 → provider，保持注册顺序。handler 可能被运行期 {@code addProvider} 追加容器，
     * 而读取来自 HTTP 线程，必须用同步包装；遍历一律先拷贝快照再做。
     */
    private final Map<String, AnalysisProvider> providers = Collections.synchronizedMap(new LinkedHashMap<>());

    private volatile byte[] listTemplateBytes;

    private volatile byte[] containersTemplateBytes;

    /**
     * 容器表构造：容器注册名取自各 provider 的 {@link AnalysisProvider#getName()}。
     *
     * <p>不传、传 {@code null} 或空数组时容器表为空，{@code isAvailable()} 返回 false：访问该组得到
     * 404 + {@link #unavailableMessage()} 的开启指引（body 不是干巴巴的 not found）；
     * 数组里的 {@code null} 元素直接跳过，便于上游「按条件可能没有 provider」时透传。
     */
    public BeansHttpServiceHandler(AnalysisProvider... providers) {
        if (providers == null) {
            return;
        }
        for (AnalysisProvider provider : providers) {
            if (provider != null) {
                addProvider(provider);
            }
        }
    }

    /**
     * 追加或替换一个聚合容器，注册名取自 {@link AnalysisProvider#getName()}；同名后登记者覆盖先登记者。
     *
     * @param provider 容器的数据提供者，不能为 null（运行期下线用 {@link #removeProvider(String)}）
     * @return this，便于链式调用
     */
    public BeansHttpServiceHandler addProvider(AnalysisProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider 不能为 null，下线容器请用 removeProvider");
        }
        String name = provider.getName();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("容器名不能为空：" + provider.getClass().getName());
        }
        providers.put(name.trim(), provider);
        return this;
    }

    /**
     * 移除一个聚合容器（运行期下线）；名字不存在或为空白时不做任何事。
     *
     * @param name 容器注册名，即 {@link AnalysisProvider#getName()}
     * @return this，便于链式调用
     */
    public BeansHttpServiceHandler removeProvider(String name) {
        if (name != null && !name.trim().isEmpty()) {
            providers.remove(name.trim());
        }
        return this;
    }

    @Override
    public String getEntryPath() {
        return BEANS_PATH;
    }

    @Override
    public Collection<String> getPathPrefixes() {
        return PATH_PREFIXES;
    }

    @Override
    public boolean isAvailable() {
        return !providers.isEmpty();
    }

    @Override
    public String unavailableMessage() {
        return "beans 通道未启用：本进程没有装配 bean 采集，请配置 cn.lizl.java-data-analyzer.beans.enable=true 后重启";
    }

    @Override
    public IndexCard indexCard() {
        return new IndexCard("beans", "Bean 采集通道",
                "选择聚合容器后浏览其中的 Bean，点名称查看对象结构与当前取值");
    }

    @Override
    public void handle(HttpExchange exchange, String relativePath) throws IOException {
        String method = exchange.getRequestMethod();
        if (BEANS_PATH.equals(relativePath)) {
            if ("GET".equalsIgnoreCase(method)) {
                sendBeansPage(exchange);
                return;
            }
            if ("POST".equalsIgnoreCase(method)) {
                sendBeanNames(exchange);
                return;
            }
            HttpResponses.methodNotAllowed(exchange, "GET", "POST");
            return;
        }
        if (relativePath.startsWith(BEAN_PATH_PREFIX)) {
            if (!"GET".equalsIgnoreCase(method)) {
                HttpResponses.methodNotAllowed(exchange, "GET");
                return;
            }
            sendBeanResult(exchange, relativePath.substring(BEAN_PATH_PREFIX.length()));
            return;
        }
        HttpResponses.text(exchange, 404, "not found");
    }

    /**
     * {@code GET /beans}：不带 {@code container} 恒出容器选择页，哪怕只有一个容器——浏览路径统一，
     * 从选择页进到列表页后所有地址都自带 {@code container}，不会出现「有时带、有时不带」的分叉。
     */
    private void sendBeansPage(HttpExchange exchange) throws IOException {
        if (requestedContainer(exchange) == null) {
            sendContainerSelection(exchange);
            return;
        }
        HttpResponses.html(exchange, 200, loadListTemplate());
    }

    /**
     * {@code POST /beans}：带 {@code container} 出该容器名单；不带时恒出容器注册名列表
     * ——与容器数量无关，前端拿到的结构始终是「未指定容器 → 容器名；指定容器 → bean 名」。
     */
    private void sendBeanNames(HttpExchange exchange) throws IOException {
        String container = requestedContainer(exchange);
        Map<String, AnalysisProvider> snapshot = providersSnapshot();
        if (container == null) {
            HttpResponses.jsonArray(exchange, 200, new ArrayList<>(snapshot.keySet()));
            return;
        }
        AnalysisProvider target = snapshot.get(container);
        if (target == null) {
            HttpResponses.text(exchange, 404, "聚合容器不存在：" + container);
            return;
        }
        List<String> beanNames = target.getBeanNames(HttpRequests.isRebuildRequested(exchange));
        if (beanNames == null) {
            beanNames = Collections.emptyList();
        }
        HttpResponses.jsonArray(exchange, 200, beanNames);
    }

    private void sendBeanResult(HttpExchange exchange, String rawBeanName) throws IOException {
        String beanName;
        try {
            beanName = HttpRequests.decodePathSegment(rawBeanName);
        } catch (IllegalArgumentException e) {
            // 畸形 % 序列按未命中处理，而不是 500
            HttpResponses.text(exchange, 404, "not found");
            return;
        }
        if (beanName.isEmpty()) {
            HttpResponses.text(exchange, 404, "not found");
            return;
        }
        // 外链模式的结果页用相对路径引用同目录的引擎与样式/脚本（./xxx.js、./xxx.css），浏览器会把请求打到
        // /bean/xxx.js，这类请求不带 container 参数，必须赶在容器解析之前拦下
        if (FrontendAssets.isAsset(beanName)) {
            HttpResponses.javascript(exchange, FrontendAssets.read(beanName));
            return;
        }
        if (HtmlTreeAssets.isAsset(beanName)) {
            HttpResponses.immutableAsset(exchange, HtmlTreeAssets.contentTypeOf(beanName),
                    HtmlTreeAssets.read(beanName));
            return;
        }
        Map<String, AnalysisProvider> snapshot = providersSnapshot();
        String container = requestedContainer(exchange);
        if (container == null) {
            HttpResponses.text(exchange, 404,
                    "存在聚合容器（" + String.join("、", snapshot.keySet())
                            + "），请用 ?container= 指定后访问");
            return;
        }
        AnalysisProvider target = snapshot.get(container);
        if (target == null) {
            HttpResponses.text(exchange, 404, "聚合容器不存在：" + container);
            return;
        }
        File htmlFile = target.getAnalyzedResult(beanName, HttpRequests.isRebuildRequested(exchange));
        if (htmlFile == null || !htmlFile.isFile()) {
            HttpResponses.text(exchange, 404, "not found");
            return;
        }
        HttpResponses.file(exchange, HttpResponses.CONTENT_TYPE_HTML, htmlFile);
    }

    /**
     * 容器选择页：服务端渲染卡片（展示名 + 注册名 + bean 数量），链接带 container 与当前 token；
     * 页头提示里的容器数量同样按实际登记数填充。
     */
    private void sendContainerSelection(HttpExchange exchange) throws IOException {
        Map<String, AnalysisProvider> snapshot = providersSnapshot();
        String token = HttpRequests.queryParameters(exchange).get("token");
        StringBuilder cards = new StringBuilder();
        for (Map.Entry<String, AnalysisProvider> entry : snapshot.entrySet()) {
            cards.append(renderContainerCard(entry.getKey(), entry.getValue(), token));
        }
        // 先替换数量再插入卡片：卡片里的展示名由 provider 提供，晚替换会把其中的占位符也解析掉
        String html = new String(loadContainersTemplate(), StandardCharsets.UTF_8)
                .replace(CONTAINER_COUNT_PLACEHOLDER, String.valueOf(snapshot.size()))
                .replace(CARDS_PLACEHOLDER, cards.toString());
        HttpResponses.html(exchange, 200, html);
    }

    private String renderContainerCard(String registeredName, AnalysisProvider provider, String token) {
        String displayName = provider.getDisplayName();
        String title = displayName != null && !displayName.trim().isEmpty() ? displayName.trim() : registeredName;
        int count = beanCount(provider);
        String countText = count < 0 ? "Bean 数量未知" : count + " 个 Bean";
        StringBuilder href = new StringBuilder("beans?container=").append(urlEncode(registeredName));
        if (token != null && !token.isEmpty()) {
            href.append("&token=").append(urlEncode(token));
        }
        // 新窗口打开：选容器只是换视角，别把选择页顶掉（与容器内的 bean 链接行为一致）
        return "<a class=\"container-card\" target=\"_blank\" rel=\"noopener\" href=\""
                + HtmlTemplates.escapeHtml(href.toString()) + "\">"
                + "<span class=\"container-name\">" + HtmlTemplates.escapeHtml(title) + "</span>"
                + "<span class=\"container-meta\">" + HtmlTemplates.escapeHtml(registeredName)
                + " · " + HtmlTemplates.escapeHtml(countText) + "</span>"
                + "</a>";
    }

    /** 卡片上的 bean 数量只是提示信息，provider 实现异常时显示「未知」而不是整个页面 500 */
    private static int beanCount(AnalysisProvider provider) {
        try {
            List<String> names = provider.getBeanNames(false);
            return names == null ? 0 : names.size();
        } catch (RuntimeException e) {
            LOG.warning("统计容器 Bean 数量失败：" + e);
            return -1;
        }
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 必然可用，理论不可达
            throw new IllegalStateException(e);
        }
    }

    private String requestedContainer(HttpExchange exchange) {
        String value = HttpRequests.queryParameters(exchange).get(CONTAINER_QUERY_PARAM);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    /** 锁内只拷贝引用，遍历与 IO 都在锁外 */
    private Map<String, AnalysisProvider> providersSnapshot() {
        synchronized (providers) {
            return new LinkedHashMap<>(providers);
        }
    }

    /** 模板读一次后缓存：轮询与反复打开时不必每次走 IO */
    private synchronized byte[] loadListTemplate() throws IOException {
        if (listTemplateBytes == null) {
            LOG.fine("加载 beans 页面模板：" + LIST_TEMPLATE_NAME);
            listTemplateBytes = ClasspathResource.readHtml(LIST_TEMPLATE_NAME);
        }
        return listTemplateBytes;
    }

    private synchronized byte[] loadContainersTemplate() throws IOException {
        if (containersTemplateBytes == null) {
            LOG.fine("加载 beans 容器选择页模板：" + CONTAINERS_TEMPLATE_NAME);
            containersTemplateBytes = ClasspathResource.readHtml(CONTAINERS_TEMPLATE_NAME);
        }
        return containersTemplateBytes;
    }
}
