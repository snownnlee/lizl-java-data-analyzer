package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.util.FrontendAssets;
import cn.lizl.project.tool.analysis.util.HtmlTemplates;

import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * PUML 转 HTML：PUML 文本嵌入极简 HTML，由浏览器端 PlantUML 官方 JS 引擎渲染成 SVG。
 * 引擎默认以 sidecar 放产物同目录，开启 {@code inlineAssets} 时内联；两种形态都不联网、
 * 双击 {@code file://} 可出图，本库对 PlantUML 零 Java 依赖。
 *
 * <p>模板调用写成 {@code PlantUML.render(lines, "diagram", options)}，第三个参数把引擎默认的 8192px
 * 尺寸检查关掉（{@code maxSvgSize} 传 0）：对象图摊平成类图很容易超宽，超限时引擎抛
 * {@code RuntimeException}（{@code Diagram too large for browser rendering}）且整张图都不出。
 * 产物总量交给分析侧的深度 / 节点数 / 排除包控制，而不是让页面停在渲染这一步。
 */
class PumlHtmlRender extends BaseRender {

    private static final String FILE_EXTENSION = ".puml.html";

    private static final String SHELL_TEMPLATE = "puml-shell.html";

    private static final String[] PLACEHOLDERS = {"${favicon}", "${title}", "${vizTag}", "${pumlTag}",
            "${pumlName}", "${pumlArray}"};

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
                PLACEHOLDERS,
                new String[]{FAVICON_DATA_URI, HtmlTemplates.escapeHtml(buildTitle(classModels)),
                        engineScriptTag(FrontendAssets.VIZ_GLOBAL), engineScriptTag(FrontendAssets.PLANTUML),
                        // 引擎缺失时的提示文案仍写文件名，内联模式下同样可读
                        FrontendAssets.PLANTUML,
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
