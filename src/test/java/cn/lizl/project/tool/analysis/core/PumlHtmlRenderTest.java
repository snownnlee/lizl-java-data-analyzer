package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.OutputFileReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.support.TestSupport;
import cn.lizl.project.tool.analysis.util.FrontendAssets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RenderType#PUML_HTML} 的专用用例。
 *
 * <p>PUML_HTML 已改为<b>浏览器端渲染</b>：产物是一张极简外壳（PUML 文本按 JS 数组嵌入 + 两次 script 引用），
 * 同目录的 {@code viz-global} 与 {@code plantuml} 引擎负责出图。所以这里断言的是外壳契约与 sidecar 落地，
 * 不再断言 SVG 内容（那要真跑浏览器）。
 */
class PumlHtmlRenderTest {

    private static final String BASE64_IMG_PREFIX = "data:image/svg+xml;base64,";

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("外壳引用同目录引擎：viz-global 必须先于 plantuml，且不出现任何 CDN")
    void shouldReferenceSidecarScriptsInOrder() {
        String html = renderSample();

        String vizTag = "<script src=\"./" + FrontendAssets.VIZ_GLOBAL + "\"></script>";
        String pumlTag = "<script src=\"./" + FrontendAssets.PLANTUML + "\"></script>";
        int vizIndex = html.indexOf(vizTag);
        int pumlIndex = html.indexOf(pumlTag);

        assertTrue(vizIndex >= 0, "缺少 viz-global 引用（Graphviz 布局引擎，必须显式加载）");
        assertTrue(pumlIndex >= 0, "缺少 plantuml 引擎引用");
        assertTrue(vizIndex < pumlIndex, "viz-global 必须先于 plantuml 加载，否则布局引擎缺失");
        assertFalse(html.contains("cdn.jsdelivr"), "外壳不应再引用 CDN，离线产物不允许有外部请求");
        assertFalse(html.contains("<svg"), "PUML_HTML 不再由服务端出 SVG，图由浏览器渲染");
    }

    @Test
    @DisplayName("PUML 文本按 JS 字符串数组嵌入，交给 PlantUML.render 渲染")
    void shouldEmbedPumlAsJsArray() {
        String html = renderSample();

        assertTrue(html.contains("PlantUML.render(["), "外壳应把行数组交给 PlantUML.render");
        assertTrue(html.contains("\"@startuml\""), "PUML 起始标记应作为带引号的字符串出现在数组里");
        assertTrue(html.contains("\"@enduml\""), "PUML 结束标记应作为带引号的字符串出现在数组里");
        assertTrue(html.contains("\"diagram\""), "render 的第二个参数应是容器 id");
        assertTrue(html.contains("id=\"diagram\""), "容器元素缺失，渲染没有落点");
        // 数组里若出现裸 < 且紧跟 / ，浏览器会提前闭合 script 标签
        int arrayStart = html.indexOf("PlantUML.render([");
        String scriptTail = html.substring(arrayStart, html.indexOf("</script>", arrayStart));
        assertFalse(scriptTail.contains("</"), "JS 字符串里的 </ 必须转义，否则会提前闭合 script");
    }

    @Test
    @DisplayName("外壳应带内联 favicon，且与纯 HTML 产物是同一张图")
    void shouldCarryInlineFavicon() {
        String html = renderSample();

        int index = html.indexOf("<link rel=\"icon\" href=\"" + BASE64_IMG_PREFIX);
        assertTrue(index >= 0, "缺少内联 favicon，标签页会显示默认空白图标");

        String remainder = html.substring(index + "<link rel=\"icon\" href=\"".length()
                + BASE64_IMG_PREFIX.length());
        String icon = new String(Base64.getDecoder().decode(remainder.substring(0, remainder.indexOf('"'))),
                StandardCharsets.UTF_8);
        assertTrue(icon.contains("<svg"), "favicon 解码后不是 SVG：" + head(icon));
    }

    @Test
    @DisplayName("外壳保持深色配色与标题，且不掺入 HtmlRender 的折叠控件")
    void shouldKeepShellMinimal() {
        String html = renderSample();

        assertTrue(html.contains("color-scheme:dark"), "缺少深色配色声明");
        assertTrue(html.contains("【Sample】"), "标题应包含根类型的简单类名");
        assertFalse(html.contains("changeDisplayForClassDiv"),
                "产物里出现了 HtmlRender 的折叠控件，说明链路跑偏了");
    }

    @Test
    @DisplayName("落盘时把引用的引擎复制到产物同目录，且第二次不重复写")
    void shouldEmitAssetsNextToOutputFile(@TempDir Path outputRoot) {
        File html = runToFile(outputRoot);
        File dir = html.getParentFile();

        for (String asset : new String[]{FrontendAssets.VIZ_GLOBAL, FrontendAssets.PLANTUML}) {
            File copied = new File(dir, asset);
            assertTrue(copied.isFile(), "产物同目录缺少引擎：" + asset);
            assertEquals(FrontendAssets.sizeOf(asset), copied.length(),
                    "落盘的引擎大小应与 classpath 中的一致：" + asset);
        }

        long stamp = new File(dir, FrontendAssets.PLANTUML).lastModified();
        runToFile(outputRoot);
        assertEquals(stamp, new File(dir, FrontendAssets.PLANTUML).lastModified(),
                "引擎已存在时不应重写（否则每次分析都要落 4 MB）");
    }

    @Test
    @DisplayName("MERMAID_HTML 同样走同目录引擎，不再引 CDN")
    void shouldUseSidecarForMermaid(@TempDir Path outputRoot) {
        List<File> files = new ArrayList<>();
        JavaDataAnalyzer.runningConfigurer()
                .setRenderType(RenderType.MERMAID_HTML)
                .setOutputRootDir(outputRoot.toString())
                .setOutputFileName("diagram")
                .addAnalyzingObject(new Sample())
                .run((OutputFileReceiver) files::add);

        String html = read(files.get(0));
        assertTrue(html.contains("<script src=\"./" + FrontendAssets.MERMAID + "\"></script>"),
                "MERMAID_HTML 应引用同目录的 mermaid 引擎");
        assertFalse(html.contains("cdn.jsdelivr"), "不应再引用 CDN");

        assertTrue(new File(outputRoot.toFile(), FrontendAssets.MERMAID).isFile(),
                "落盘时应在产物同目录补一份 mermaid 引擎");
    }

    private String renderSample() {
        List<String> contents = new ArrayList<>();
        JavaDataAnalyzer.runningConfigurer()
                .setRenderType(RenderType.PUML_HTML)
                .addAnalyzingObject(new Sample())
                .run((OutputContentReceiver) contents::add);

        assertEquals(1, contents.size(), "应恰好回调一次");
        return contents.get(0);
    }

    private File runToFile(Path outputRoot) {
        @SuppressWarnings("unchecked")
        List<File> files = new ArrayList<>();
        JavaDataAnalyzer.runningConfigurer()
                .setRenderType(RenderType.PUML_HTML)
                .setOutputRootDir(outputRoot.toString())
                .setOutputFileName("diagram")
                .addAnalyzingObject(new Sample())
                .run((OutputFileReceiver) files::add);
        assertEquals(1, files.size(), "应恰好产出一个文件");
        return files.get(0);
    }

    private static String read(File file) {
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String head(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    @SuppressWarnings("unused")
    static class Sample {

        private String name = "sample";

        private int count = 1;
    }
}
