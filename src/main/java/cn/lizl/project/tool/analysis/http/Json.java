package cn.lizl.project.tool.analysis.http;

import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 输出，只服务「字符串数组」与「扁平状态对象」两个场景。运行期零依赖，不为一个接口引入 Jackson。
 */
final class Json {

    private Json() {
    }

    static String toArray(List<String> values) {
        StringBuilder builder = new StringBuilder(16 + values.size() * 24);
        builder.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            appendString(builder, values.get(i));
        }
        return builder.append(']').toString();
    }

    /**
     * 扁平对象：值为 Boolean / Number / String / List&lt;String&gt;，其他类型按字符串输出。
     */
    static String toObject(Map<String, Object> values) {
        StringBuilder builder = new StringBuilder(64 + values.size() * 32);
        builder.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            appendString(builder, entry.getKey());
            builder.append(':');
            Object value = entry.getValue();
            if (value instanceof Boolean || (value instanceof Number && isFinite((Number) value))) {
                builder.append(value);
            } else if (value instanceof List) {
                builder.append('[');
                List<?> list = (List<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        builder.append(',');
                    }
                    appendString(builder, String.valueOf(list.get(i)));
                }
                builder.append(']');
            } else {
                appendString(builder, value == null ? null : String.valueOf(value));
            }
        }
        return builder.append('}').toString();
    }

    /** NaN / Infinity 不是合法 JSON 字面量，调用方会降级按字符串输出 */
    private static boolean isFinite(Number number) {
        if (number instanceof Double) {
            return Double.isFinite((Double) number);
        }
        if (number instanceof Float) {
            return Float.isFinite((Float) number);
        }
        return true;
    }

    private static void appendString(StringBuilder builder, String value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                default:
                    // 控制字符与 U+2028/U+2029 一律转义，避免前端脚本解析异常
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
            }
        }
        builder.append('"');
    }
}
