package cn.lizl.project.tool.analysis.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTML 模板的统一加载与占位符填充：模板统一放 {@link ResourcePaths#HTML_DIR}，
 * 加载时压缩一次、进程内缓存复用；页面模板（http）与产物外壳（core）共用同一份缓存。
 */
public final class HtmlTemplates {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private HtmlTemplates() {
    }

    /** 按文件名读取 {@code analysis/html/} 下的模板（压缩并缓存）；找不到抛 {@link IllegalStateException} */
    public static String load(String fileName) {
        return CACHE.computeIfAbsent(fileName, HtmlTemplates::readAndCompact);
    }

    private static String readAndCompact(String fileName) {
        InputStream stream = HtmlTemplates.class.getClassLoader().getResourceAsStream(ResourcePaths.html(fileName));
        if (stream == null) {
            throw new IllegalStateException("错误：模板资源读取为空！！！");
        }
        return TemplateCompressor.compact(CommonUtil.read(stream, StandardCharsets.UTF_8, true));
    }

    /**
     * 占位符填充：单次扫描找最近的占位符（同位置取更长者，防前缀互咬），替换值不参与后续扫描。
     *
     * <p>{@code placeholders} 与 {@code values} 按下标一一对应。
     */
    public static String fill(String template, String[] placeholders, String[] values) {
        int templateLength = template.length();
        StringBuilder result = new StringBuilder(templateLength + 128);
        int cursor = 0;
        while (true) {
            int bestIndex = -1;
            int bestPlaceholder = -1;
            for (int i = 0; i < placeholders.length; i++) {
                int index = template.indexOf(placeholders[i], cursor);
                if (index < 0) {
                    continue;
                }
                boolean longer = index == bestIndex && placeholders[i].length() > placeholders[bestPlaceholder].length();
                if (bestIndex < 0 || index < bestIndex || longer) {
                    bestIndex = index;
                    bestPlaceholder = i;
                }
            }
            if (bestIndex < 0) {
                return result.append(template, cursor, templateLength).toString();
            }
            result.append(template, cursor, bestIndex).append(values[bestPlaceholder]);
            cursor = bestIndex + placeholders[bestPlaceholder].length();
        }
    }

    /**
     * 动态内容进 HTML 前一律转义：{@code & < > " '} 全转，元素内容位与属性值位（{@code href="..."}、
     * {@code title='...'}）都可直接用，不必再单独处理引号；{@code null} 按空串处理。
     *
     * <p>元素内容位多转出的 {@code &quot;}/{@code &#39;} 由浏览器解析回原字符，渲染结果与被转义前一致。
     */
    public static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    result.append("&amp;");
                    break;
                case '<':
                    result.append("&lt;");
                    break;
                case '>':
                    result.append("&gt;");
                    break;
                case '"':
                    result.append("&quot;");
                    break;
                case '\'':
                    result.append("&#39;");
                    break;
                default:
                    result.append(c);
            }
        }
        return result.toString();
    }
}
