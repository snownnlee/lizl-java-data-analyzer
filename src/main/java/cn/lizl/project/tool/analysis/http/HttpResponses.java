package cn.lizl.project.tool.analysis.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * 响应写出工具：{@link HttpAnalysisServer} 与各 {@link HttpServiceHandler} 共用。
 *
 * <p>统一在这里设置 {@code Content-Type}/{@code Cache-Control}/{@code X-Content-Type-Options}，
 * 处理器就不必各自拼响应头，也不会出现某个端点漏设安全头。
 */
@SuppressWarnings("JavadocReference")
public final class HttpResponses {

    public static final String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";

    public static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";

    public static final String CONTENT_TYPE_TEXT = "text/plain; charset=UTF-8";

    private HttpResponses() {
    }

    public static void text(HttpExchange exchange, int status, String message) throws IOException {
        write(exchange, status, CONTENT_TYPE_TEXT, message.getBytes(StandardCharsets.UTF_8));
    }

    public static void html(HttpExchange exchange, int status, String html) throws IOException {
        write(exchange, status, CONTENT_TYPE_HTML, html.getBytes(StandardCharsets.UTF_8));
    }

    public static void html(HttpExchange exchange, int status, byte[] html) throws IOException {
        write(exchange, status, CONTENT_TYPE_HTML, html);
    }

    public static void json(HttpExchange exchange, int status, String json) throws IOException {
        write(exchange, status, CONTENT_TYPE_JSON, json.getBytes(StandardCharsets.UTF_8));
    }

    /** 把字符串列表序列化为 JSON 数组（{@code /beans} 名单、产物名列表等用） */
    public static void jsonArray(HttpExchange exchange, int status, java.util.List<String> values) throws IOException {
        json(exchange, status, Json.toArray(values));
    }

    /** 把 {@code Map} 状态对象序列化为 JSON 返回（拦截器 {@code /state} 等用） */
    public static void jsonState(HttpExchange exchange, Map<String, Object> state) throws IOException {
        json(exchange, 200, Json.toObject(state));
    }

    /**
     * 流式发送文件：响应头带 Content-Length 后直接 {@link Files#copy} 到响应流，
     * 不把整个产物读进内存（大报告整读会在多线程并发时内存翻倍）。
     * <p>
     *  405 必须带 Allow 头（RFC 7231），客户端才能自适应重试 */
    public static void methodNotAllowed(HttpExchange exchange, String... allowedMethods) throws IOException {
        exchange.getResponseHeaders().set("Allow", String.join(", ", allowedMethods));
        text(exchange, 405, "method not allowed");
    }

    public static void file(HttpExchange exchange, String contentType, File file) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        long length = file.length();
        exchange.sendResponseHeaders(200, length == 0 ? -1 : length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            Files.copy(file.toPath(), outputStream);
        }
    }

    public static void write(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        // 204/304 不允许带 body：长度固定给 -1，body 忽略（write 是公共方法，调用方可能传这两个状态码）
        if (status == 204 || status == 304) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
        }
    }

    /**
     * 前端引擎（产物同目录 sidecar 的 js）。文件名自带版本、内容永不变，所以给一年强缓存：
     * 同一台机器上的多个产物共用一份，浏览器只真正下载一次。
     */
    public static void javascript(HttpExchange exchange, byte[] content) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/javascript; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(content);
        }
    }

    public static String contentTypeOf(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".html") ? CONTENT_TYPE_HTML : CONTENT_TYPE_TEXT;
    }

    /** 动态内容（前缀、token、产物名）进 HTML 前一律转义，避免引号或尖括号破坏页面 */
    public static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }
}
