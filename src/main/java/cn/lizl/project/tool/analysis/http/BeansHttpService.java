package cn.lizl.project.tool.analysis.http;

import cn.lizl.project.tool.analysis.util.FrontendAssets;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * beans 端点的 HTTP 处理器：{@code GET /beans} 页面、{@code POST /beans} 名单、
 * {@code GET /bean/{name}} 单个 Bean 的分析结果。
 *
 * <p>只依赖 {@link AnalysisProvider} 接口，不依赖任何框架，也不碰服务器内部状态。
 * 公开给 Spring 集成侧复用（由 {@code BeansAutoConfiguration} 注册成 {@link HttpServiceHandler} Bean）。
 */
public final class BeansHttpService implements HttpServiceHandler {

    static final String BEANS_PATH = "/beans";

    static final String BEAN_PATH_PREFIX = "/bean/";

    private static final String TEMPLATE_NAME = "beans.html";

    private static final Logger LOG = Logger.getLogger(BeansHttpService.class.getName());

    private final AnalysisProvider provider;

    private volatile byte[] templateBytes;

    public BeansHttpService(AnalysisProvider provider) {
        this.provider = provider;
    }

    @Override
    public Collection<String> getPaths() {
        return java.util.Arrays.asList(BEANS_PATH, BEAN_PATH_PREFIX);
    }

    @Override
    public boolean isAvailable() {
        return provider != null;
    }

    @Override
    public String unavailableMessage() {
        return "beans 通道未启用：本进程没有装配 bean 采集，请配置 cn.lizl.java-data-analyzer.beans.enable=true 后重启";
    }

    @Override
    public IndexCard indexCard() {
        return new IndexCard("beans", "Bean 采集通道", "浏览容器里的 Bean，点名称查看对象结构与当前取值");
    }

    @Override
    public void handle(HttpExchange exchange, String relativePath) throws IOException {
        String method = exchange.getRequestMethod();
        if (BEANS_PATH.equals(relativePath)) {
            if ("GET".equalsIgnoreCase(method)) {
                HttpResponses.html(exchange, 200, loadTemplate());
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

    private void sendBeanNames(HttpExchange exchange) throws IOException {
        List<String> beanNames = provider.getBeanNames(HttpRequests.isRebuildRequested(exchange));
        if (beanNames == null) {
            beanNames = Collections.emptyList();
        }
        HttpResponses.jsonArray(exchange, 200, beanNames);
    }

    private void sendBeanResult(HttpExchange exchange, String rawBeanName) throws IOException {
        String beanName;
        try {
            beanName = URLDecoder.decode(rawBeanName, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException e) {
            // 畸形 % 序列按未命中处理，而不是 500
            HttpResponses.text(exchange, 404, "not found");
            return;
        }
        if (beanName.isEmpty()) {
            HttpResponses.text(exchange, 404, "not found");
            return;
        }
        // 结果页用相对路径引用同目录的引擎（./xxx.js），浏览器会把请求打到 /bean/xxx.js
        if (FrontendAssets.isAsset(beanName)) {
            HttpResponses.javascript(exchange, FrontendAssets.read(beanName));
            return;
        }
        File htmlFile = provider.getAnalyzedResult(beanName, HttpRequests.isRebuildRequested(exchange));
        if (htmlFile == null || !htmlFile.isFile()) {
            HttpResponses.text(exchange, 404, "not found");
            return;
        }
        HttpResponses.file(exchange, HttpResponses.CONTENT_TYPE_HTML, htmlFile);
    }

    /** 页面模板读一次后缓存：轮询与反复打开时不必每次走 IO */
    private byte[] loadTemplate() throws IOException {
        byte[] cached = templateBytes;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (templateBytes == null) {
                LOG.fine("加载 beans 页面模板：" + TEMPLATE_NAME);
                templateBytes = ClasspathResource.readHtml(TEMPLATE_NAME);
            }
            return templateBytes;
        }
    }
}
