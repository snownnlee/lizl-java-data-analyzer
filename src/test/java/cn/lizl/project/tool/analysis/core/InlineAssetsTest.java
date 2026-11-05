package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.OutputFileReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.support.TestSupport;
import cn.lizl.project.tool.analysis.util.FrontendAssets;
import cn.lizl.project.tool.analysis.util.HtmlTreeAssets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code inlineAssets} 开关：开启后 HTML 产物是单文件自包含页面，不再落同目录 sidecar。
 */
class InlineAssetsTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("默认关闭：仍以相对路径引用同目录 sidecar")
    void shouldReferenceSidecarByDefault() {
        String html = renderWithoutInlineFlag();

        assertTrue(html.contains("<link rel=\"stylesheet\" href=\"./"
                + HtmlTreeAssets.servedName(HtmlTreeAssets.STYLE) + "\">"), "默认应引用同目录样式表");
        assertTrue(html.contains("<script src=\"./"
                + HtmlTreeAssets.servedName(HtmlTreeAssets.INTERACTION) + "\"></script>"), "默认应引用同目录脚本");
    }

    @Test
    @DisplayName("HTML：样式与交互脚本内联进 <style> / <script>")
    void htmlShouldBeSelfContained() {
        String html = render(RenderType.HTML, true);

        assertTrue(html.contains("<style>" + HtmlTreeAssets.text(HtmlTreeAssets.STYLE) + "</style>"),
                "样式表未内联");
        assertTrue(html.contains("<script>" + HtmlTreeAssets.text(HtmlTreeAssets.INTERACTION) + "</script>"),
                "交互脚本未内联");
        assertFalse(html.contains("./tree-style-"), "不应残留样式表引用");
        assertFalse(html.contains("./tree-interaction-"), "不应残留脚本引用");
    }

    @Test
    @DisplayName("HTML2：建树脚本内联，JSON 数据块保持原样")
    void html2ShouldInlineBuilder() {
        String html = render(RenderType.HTML2, true);

        assertTrue(html.contains("<script>" + HtmlTreeAssets.text(HtmlTreeAssets.BUILDER) + "</script>"),
                "建树脚本未内联，页面会空白");
        assertFalse(html.contains("./tree-builder-"), "不应残留建树脚本引用");
        assertTrue(html.contains("<script type=\"application/json\" id=\"analysis-data\">"),
                "数据块被误改，前端取不到骨架");
    }

    @Test
    @DisplayName("PUML_HTML：两个引擎内联，且 viz-global 仍在 plantuml 之前")
    void pumlHtmlShouldInlineEnginesInOrder() {
        String html = render(RenderType.PUML_HTML, true);

        assertFalse(html.contains("src=\"./"), "引擎不应再以外部文件引用");
        int vizIndex = html.indexOf(head(FrontendAssets.readAsText(FrontendAssets.VIZ_GLOBAL)));
        int pumlIndex = html.indexOf(head(FrontendAssets.readAsText(FrontendAssets.PLANTUML)));
        assertTrue(vizIndex >= 0, "viz-global 引擎未内联");
        assertTrue(pumlIndex >= 0, "plantuml 引擎未内联");
        assertTrue(vizIndex < pumlIndex, "viz-global 必须先于 plantuml 加载");
    }

    @Test
    @DisplayName("MERMAID_HTML：引擎内联，不再引用外部 js")
    void mermaidHtmlShouldInlineEngine() {
        String html = render(RenderType.MERMAID_HTML, true);

        assertFalse(html.contains("src=\"./"), "引擎不应再以外部文件引用");
        assertTrue(html.indexOf(head(FrontendAssets.readAsText(FrontendAssets.MERMAID))) >= 0,
                "mermaid 引擎未内联");
    }

    @Test
    @DisplayName("内联后落盘只剩产物文件，不再写 sidecar")
    void shouldNotWriteSidecarFiles(@TempDir Path outputRoot) {
        File file = runToFile(outputRoot, RenderType.MERMAID_HTML, true);

        String[] names = file.getParentFile().list();
        assertNotNull(names, "输出目录不可读");
        assertEquals(1, names.length, "内联后不应再写 sidecar：" + Arrays.toString(names));
        assertEquals(file.getName(), names[0], "目录里应只剩产物自身");
    }

    @Test
    @DisplayName("纯文本产物不受影响")
    void pureTextRenderShouldIgnoreInlineAssets() {
        String puml = render(RenderType.PUML, true);

        assertTrue(puml.contains("@startuml"), "PUML 源码应原样输出");
        assertFalse(puml.contains("<style>"), "纯文本产物不应被内联逻辑改动");
    }

    @Test
    @DisplayName("资源里不得出现提前闭合标签的序列：JS 不含 </script，CSS 不含 </style")
    void assetsShouldNotContainEarlyClosingTags() {
        String[] scripts = {
                HtmlTreeAssets.text(HtmlTreeAssets.INTERACTION),
                HtmlTreeAssets.text(HtmlTreeAssets.BUILDER),
                FrontendAssets.readAsText(FrontendAssets.VIZ_GLOBAL),
                FrontendAssets.readAsText(FrontendAssets.PLANTUML),
                FrontendAssets.readAsText(FrontendAssets.MERMAID)};
        for (String script : scripts) {
            assertFalse(script.toLowerCase(Locale.ROOT).contains("</script"),
                    "脚本里出现 </script，内联后产物会被截断");
        }
        assertFalse(HtmlTreeAssets.text(HtmlTreeAssets.STYLE).toLowerCase(Locale.ROOT).contains("</style"),
                "样式里出现 </style，内联后样式会被截断");
    }

    private static String render(RenderType renderType, boolean inlineAssets) {
        List<String> contents = new ArrayList<>();
        JavaDataAnalyzer.runningConfigurer()
                .setRenderType(renderType)
                .setInlineAssets(inlineAssets)
                .addAnalyzingObject(new Sample())
                .run((OutputContentReceiver) contents::add);

        assertEquals(1, contents.size(), "应恰好回调一次");
        return contents.get(0);
    }

    private static String renderWithoutInlineFlag() {
        List<String> contents = new ArrayList<>();
        JavaDataAnalyzer.runningConfigurer()
                .addAnalyzingObject(new Sample())
                .run((OutputContentReceiver) contents::add);

        assertEquals(1, contents.size(), "应恰好回调一次");
        return contents.get(0);
    }

    private static File runToFile(Path outputRoot, RenderType renderType, boolean inlineAssets) {
        List<File> files = new ArrayList<>();
        JavaDataAnalyzer.runningConfigurer()
                .setRenderType(renderType)
                .setInlineAssets(inlineAssets)
                .setOutputRootDir(outputRoot.toString())
                .setOutputFileName("diagram")
                .addAnalyzingObject(new Sample())
                .run((OutputFileReceiver) files::add);

        assertEquals(1, files.size(), "应恰好产出一个文件");
        return files.get(0);
    }

    /** 引擎首段文本：整段比对会让断言里出现 MB 级字符串字面量，取前 64 字符足以定位 */
    private static String head(String text) {
        return text.substring(0, 64);
    }

    @SuppressWarnings("unused")
    static class Sample {

        private String name = "sample";

        private int count = 1;
    }
}
