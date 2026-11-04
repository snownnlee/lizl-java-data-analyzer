package cn.lizl.project.tool.analysis.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 请求读取工具：查询串解析与 {@code rebuild} 判定。
 *
 * <p>服务端只认 query string（表单体一律不读），这里集中实现，避免每个处理器各写一份解码逻辑。
 */
public final class HttpRequests {

    private HttpRequests() {
    }

    /** 解析查询串；同名键后者覆盖前者，解码失败时保留原始文本 */
    public static Map<String, String> queryParameters(HttpExchange exchange) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> parameters = new HashMap<>(4);
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int index = pair.indexOf('=');
            String key = index < 0 ? pair : pair.substring(0, index);
            String value = index < 0 ? "" : pair.substring(index + 1);
            try {
                parameters.put(URLDecoder.decode(key, StandardCharsets.UTF_8.name()),
                        URLDecoder.decode(value, StandardCharsets.UTF_8.name()));
            } catch (IOException | IllegalArgumentException ignored) {
                // UnsupportedEncodingException（理论不可达）与畸形 % 序列的 IAE 都保留原始文本
                parameters.put(key, value);
            }
        }
        return parameters;
    }

    /**
     * 解码路径段。与 query 不同，路径里的 {@code +} 是字面量：不能按
     * {@code application/x-www-form-urlencoded} 解成空格，否则 {@code /bean/a+b} 会查成 {@code a b}。
     *
     * @throws IllegalArgumentException 畸形 {@code %} 序列（调用方按未命中处理）
     */
    public static String decodePathSegment(String rawSegment) {
        try {
            return URLDecoder.decode(rawSegment.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 是 JVM 必须支持的编码，理论不可达
            throw new IllegalStateException("UTF-8 编码不可用", e);
        }
    }

    /** {@code rebuild=true} 表示本次请求强制重新分析 */
    public static boolean isRebuildRequested(HttpExchange exchange) {
        String value = queryParameters(exchange).get("rebuild");
        return value != null && "true".equalsIgnoreCase(value.trim());
    }
}
