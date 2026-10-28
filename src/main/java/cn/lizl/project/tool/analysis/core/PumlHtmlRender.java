package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.util.FrontendAssets;
import cn.lizl.project.tool.analysis.util.HtmlTemplates;

import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * PUML 转 HTML：PUML 文本嵌入极简 HTML，由浏览器端 PlantUML 官方 JS 引擎渲染成 SVG。
 * 引擎以 sidecar 形式放产物同目录，不联网、双击 {@code file://} 也能出图，本库对 PlantUML 零 Java 依赖。
 */
class PumlHtmlRender extends BaseRender {

    private static final String FILE_EXTENSION = ".puml.html";

    private static final String SHELL_TEMPLATE = "puml-shell.html";

    /** 行分割正则预编译：String.split 每次调用都会重编译 */
    private static final Pattern LINE_BREAK = Pattern.compile("\\r?\\n");

    /** 与 {@code JavaDataAnalyzer.html} 的 {@code <link rel="icon">} 是同一张图，改模板图标时须同步 */
    static final String FAVICON_DATA_URI =
            "data:image/svg+xml;base64,PHN2ZyB4bWxucz0naHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmcnIHZpZXdCb3g9JzAgMC" +
            "AzMiAzMic+PHJlY3Qgd2lkdGg9JzMyJyBoZWlnaHQ9JzMyJyBmaWxsPScjMGEwZTE3Jy8+PGNpcmNsZSBjeD0nMTYnIGN5PS" +
            "c5JyByPSc0JyBmaWxsPScjMDBmZmZmJy8+PGNpcmNsZSBjeD0nOCcgY3k9JzIzJyByPSc0JyBmaWxsPScjZmYwMGZmJy8+PG" +
            "NpcmNsZSBjeD0nMjQnIGN5PScyMycgcj0nNCcgZmlsbD0nIzY0OTVlZCcvPjxwYXRoIGQ9J00xNiAxMyA4IDE5TTE2IDEzbD" +
            "ggNk04IDIzaDE2JyBzdHJva2U9JyMwMGZmZmYnIHN0cm9rZS13aWR0aD0nMS41JyBvcGFjaXR5PScuNycvPjwvc3ZnPg==";

    private final PumlRender pumlRender;

    PumlHtmlRender(RunningConfig runningConfig) {
        super(runningConfig, FILE_EXTENSION);
        pumlRender = new PumlRender(runningConfig);
    }

    @Override
    public String render(ClassModel[] classModels) {
        return HtmlTemplates.fill(HtmlTemplates.load(SHELL_TEMPLATE),
                new String[]{"${favicon}", "${title}", "${vizFile}", "${pumlFile}", "${pumlArray}"},
                new String[]{FAVICON_DATA_URI, HtmlTemplates.escapeHtml(buildTitle(classModels)),
                        FrontendAssets.VIZ_GLOBAL, FrontendAssets.PLANTUML,
                        toJsStringArray(pumlRender.render(classModels))});
    }

    private static String buildTitle(ClassModel[] classModels) {
        return Stream.of(classModels)
                .map(ClassModel::getClassSimpleName)
                .collect(Collectors.joining("、", "【", "】"));
    }

    /**
     * PUML 文本 → JS 字符串数组字面量。{@code <} 必须转 {@code \u003c}（求值后仍是 <，值不变）：
     * 用 {@code <\/} 会改变值（{@code \/} 求值后是 {@code /}），且 {@code </} 会提前闭合 script。
     */
    private static String toJsStringArray(String puml) {
        String[] lines = LINE_BREAK.split(puml, -1);
        StringBuilder result = new StringBuilder(puml.length() + 32).append('[');
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append('"').append(escapeJs(lines[i])).append('"');
        }
        return result.append(']').toString();
    }

    private static String escapeJs(String line) {
        StringBuilder result = new StringBuilder(line.length() + 8);
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            switch (c) {
                case '\\':
                    result.append("\\\\");
                    break;
                case '"':
                    result.append("\\\"");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '<':
                    result.append("\\u003c");
                    break;
                case '\u2028':
                    result.append("\\u2028");
                    break;
                case '\u2029':
                    result.append("\\u2029");
                    break;
                default:
                    result.append(c);
            }
        }
        return result.toString();
    }
}
