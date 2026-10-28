package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.http.ClasspathResource;
import cn.lizl.project.tool.analysis.util.FrontendAssets;
import cn.lizl.project.tool.analysis.http.HttpRequests;
import cn.lizl.project.tool.analysis.http.HttpResponses;
import cn.lizl.project.tool.analysis.http.HttpServiceHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * interceptor 通道的 HTTP 处理器。
 *
 * <p>端点：{@code GET /interceptor} 管理页、{@code GET /interceptor/state} 状态 JSON、
 * {@code GET /interceptor/list} 产物名列表、{@code GET /interceptor/output/{name}} 产物内容、
 * {@code POST /interceptor?active=&include=&exclude=&renderType=} 热更新（缺省参数表示不变，
 * include/exclude 传空串表示清空规则，多个规则用逗号分隔）。
 *
 * <p>本类属于拦截器通道（Spring 特性），因此放在 {@code spring.interceptor} 而<b>不是</b>通用 http 包；
 * 它只依赖 {@link HttpServiceHandler} 等通用契约与 {@link InterceptorControlProvider}，不直接依赖 Spring API。
 */
public final class InterceptorHttpService implements HttpServiceHandler {

    static final String INTERCEPTOR_PATH = "/interceptor";

    static final String INTERCEPTOR_STATE_PATH = "/interceptor/state";

    static final String INTERCEPTOR_LIST_PATH = "/interceptor/list";

    static final String INTERCEPTOR_OUTPUT_PREFIX = "/interceptor/output/";

    private static final String TEMPLATE_NAME = "interceptor.html";

    private final InterceptorControlProvider control;

    private volatile byte[] templateBytes;

    public InterceptorHttpService(InterceptorControlProvider control) {
        this.control = control;
    }

    @Override
    public Collection<String> getPaths() {
        return Collections.singletonList(INTERCEPTOR_PATH);
    }

    @Override
    public boolean isAvailable() {
        return control != null;
    }

    @Override
    public String unavailableMessage() {
        return "interceptor 通道未启用：本进程没有装配请求拦截器，请配置 "
                + "cn.lizl.java-data-analyzer.interceptor.enable=true 后重启";
    }

    @Override
    public IndexCard indexCard() {
        return new IndexCard("interceptor", "请求拦截器通道", "查看按路径捕获的请求入参产物，可暂停/恢复捕获");
    }

    @Override
    public void handle(HttpExchange exchange, String relativePath) throws IOException {
        String method = exchange.getRequestMethod();
        boolean isGet = "GET".equalsIgnoreCase(method);
        if (isGet && INTERCEPTOR_PATH.equals(relativePath)) {
            HttpResponses.html(exchange, 200, loadTemplate());
            return;
        }
        if (isGet && INTERCEPTOR_STATE_PATH.equals(relativePath)) {
            HttpResponses.jsonState(exchange, control.interceptorState());
            return;
        }
        if (isGet && INTERCEPTOR_LIST_PATH.equals(relativePath)) {
            List<String> names = control.interceptorOutputNames();
            names = filterOutputNames(names == null ? Collections.emptyList() : names, exchange);
            HttpResponses.jsonArray(exchange, 200, names);
            return;
        }
        if (isGet && relativePath.startsWith(INTERCEPTOR_OUTPUT_PREFIX)) {
            sendOutput(exchange, relativePath.substring(INTERCEPTOR_OUTPUT_PREFIX.length()));
            return;
        }
        if ("POST".equalsIgnoreCase(method) && INTERCEPTOR_PATH.equals(relativePath)) {
            updateState(exchange);
            return;
        }
        HttpResponses.methodNotAllowed(exchange, "GET", "POST");
    }

    private void updateState(HttpExchange exchange) throws IOException {
        Map<String, String> parameters = HttpRequests.queryParameters(exchange);
        Boolean active = parameters.containsKey("active")
                ? Boolean.parseBoolean(parameters.get("active"))
                : null;
        List<String> include = parsePathRules(parameters, "include");
        List<String> exclude = parsePathRules(parameters, "exclude");
        String renderType = parameters.get("renderType");
        try {
            HttpResponses.jsonState(exchange, control.updateInterceptorState(active, include, exclude, renderType));
        } catch (IllegalArgumentException e) {
            HttpResponses.text(exchange, 400, String.valueOf(e.getMessage()));
        }
    }

    private void sendOutput(HttpExchange exchange, String rawName) throws IOException {
        String name;
        try {
            name = URLDecoder.decode(rawName, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException e) {
            // 畸形 % 序列按未命中处理，而不是 500
            HttpResponses.text(exchange, 404, "not found");
            return;
        }
        // 产物页用相对路径引用同目录的引擎（./xxx.js），浏览器会把请求打到 /interceptor/output/xxx.js
        if (FrontendAssets.isAsset(name)) {
            HttpResponses.javascript(exchange, FrontendAssets.read(name));
            return;
        }
        File outputFile = name.isEmpty() ? null : control.getInterceptorOutput(name);
        if (outputFile == null || !outputFile.isFile()) {
            HttpResponses.text(exchange, 404, "not found");
            return;
        }
        HttpResponses.file(exchange, HttpResponses.contentTypeOf(outputFile.getName()), outputFile);
    }

    /**
     * 产物名列表的可选过滤：{@code date=yyyyMMdd} 只取该日期目录（产物名以 {@code date/} 开头），
     * {@code limit=N} 只取最近 N 条。参数缺省即全量。
     */
    private List<String> filterOutputNames(List<String> names, HttpExchange exchange) {
        Map<String, String> parameters = HttpRequests.queryParameters(exchange);
        String date = parameters.get("date");
        if (date != null && !date.trim().isEmpty()) {
            String prefix = date.trim() + "/";
            List<String> filtered = new ArrayList<>();
            for (String name : names) {
                if (name.startsWith(prefix)) {
                    filtered.add(name);
                }
            }
            names = filtered;
        }
        String rawLimit = parameters.get("limit");
        if (rawLimit != null) {
            try {
                int limit = Integer.parseInt(rawLimit.trim());
                if (limit > 0 && names.size() > limit) {
                    names = names.subList(0, limit);
                }
            } catch (NumberFormatException ignored) {
                // 非法 limit 视为未提供
            }
        }
        return names;
    }

    /** null = 未提供（不变）；空列表 = 显式清空；否则按逗号拆分并去空白 */
    private static List<String> parsePathRules(Map<String, String> parameters, String key) {
        if (!parameters.containsKey(key)) {
            return null;
        }
        String raw = parameters.get(key);
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> rules = new ArrayList<>(Arrays.asList(raw.split(",")));
        rules.removeIf(rule -> rule == null || rule.trim().isEmpty());
        List<String> trimmed = new ArrayList<>(rules.size());
        for (String rule : rules) {
            trimmed.add(rule.trim());
        }
        return trimmed;
    }

    /** 管理页轮询/打开频繁，模板读一次后缓存 */
    private byte[] loadTemplate() throws IOException {
        byte[] cached = templateBytes;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (templateBytes == null) {
                templateBytes = ClasspathResource.readHtml(TEMPLATE_NAME);
            }
            return templateBytes;
        }
    }
}
