package cn.lizl.project.tool.analysis.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产物同目录样式/脚本的分发规则。
 *
 * <p>这套东西的失效方式很隐蔽：名字对不上就是 404，页面只是没样式或点了没反应；文件名丢了指纹，
 * 升级后旧文件不会被替换，用户看到的还是上一版的行为。所以名字形态、内容、落盘幂等都要断言。
 */
class HtmlTreeAssetsTest {

    @TempDir
    Path outputDir;

    @Test
    @DisplayName("引用名带内容指纹，同名即同内容，可长期强缓存")
    void servedNameCarriesFingerprint() {
        assertTrue(HtmlTreeAssets.servedName(HtmlTreeAssets.STYLE).matches("tree-style-[0-9a-f]{8}\\.css"),
                "样式资源名没带指纹：" + HtmlTreeAssets.servedName(HtmlTreeAssets.STYLE));
        assertTrue(HtmlTreeAssets.servedName(HtmlTreeAssets.INTERACTION).matches("tree-interaction-[0-9a-f]{8}\\.js"),
                "交互脚本名没带指纹：" + HtmlTreeAssets.servedName(HtmlTreeAssets.INTERACTION));
        assertTrue(HtmlTreeAssets.servedName(HtmlTreeAssets.BUILDER).matches("tree-builder-[0-9a-f]{8}\\.js"),
                "建树脚本名没带指纹：" + HtmlTreeAssets.servedName(HtmlTreeAssets.BUILDER));
    }

    @Test
    @DisplayName("落盘的是压缩后的文本，注释已去掉")
    void contentIsCompacted() {
        String style = HtmlTreeAssets.text(HtmlTreeAssets.STYLE);
        String script = HtmlTreeAssets.text(HtmlTreeAssets.INTERACTION);

        assertFalse(style.contains("/*"), "样式没压缩，注释还在产物里");
        assertTrue(style.contains("class_model_title_control_button"), "样式里应有渲染端的类名");
        assertFalse(script.contains("\n\n"), "脚本没做逐行修剪");
        assertTrue(script.contains("function changeDisplayForClassDiv"), "交互脚本的关键函数缺失");
    }

    @Test
    @DisplayName("产物内容里出现的资源名必须能被识别，否则 HTTP 通道会 404")
    void servedNamesShouldBeRecognized() {
        for (String fileName : new String[]{HtmlTreeAssets.STYLE, HtmlTreeAssets.INTERACTION,
                HtmlTreeAssets.BUILDER}) {
            String servedName = HtmlTreeAssets.servedName(fileName);
            assertTrue(HtmlTreeAssets.isAsset(servedName), "未识别出资源名：" + servedName);
            assertEquals(HtmlTreeAssets.text(fileName),
                    new String(HtmlTreeAssets.read(servedName), StandardCharsets.UTF_8),
                    "按引用名读回的内容与资源不一致");
        }
        assertFalse(HtmlTreeAssets.isAsset("mermaid-11.17.2.min.js"), "不应把前端引擎当成树资源");
        assertFalse(HtmlTreeAssets.isAsset("tree-style.css"), "未带指纹的名字不应被识别（会拿到 404）");
    }

    @Test
    @DisplayName("落盘补齐：只补产物实际引用到的资源，重复调用幂等")
    void ensureReferencedWritesOnlyReferencedAssets() throws Exception {
        String html = "<link rel=\"stylesheet\" href=\"./" + HtmlTreeAssets.servedName(HtmlTreeAssets.STYLE) + "\">"
                + "<script src=\"./" + HtmlTreeAssets.servedName(HtmlTreeAssets.INTERACTION) + "\"></script>";

        HtmlTreeAssets.ensureReferenced(html, outputDir.toFile());

        File styleFile = new File(outputDir.toFile(), HtmlTreeAssets.servedName(HtmlTreeAssets.STYLE));
        File scriptFile = new File(outputDir.toFile(), HtmlTreeAssets.servedName(HtmlTreeAssets.INTERACTION));
        File builderFile = new File(outputDir.toFile(), HtmlTreeAssets.servedName(HtmlTreeAssets.BUILDER));
        assertTrue(styleFile.isFile(), "样式没有补写到产物同目录");
        assertTrue(scriptFile.isFile(), "交互脚本没有补写到产物同目录");
        assertFalse(builderFile.exists(), "产物没引用建树脚本，不应多写一份");

        assertEquals(HtmlTreeAssets.text(HtmlTreeAssets.STYLE),
                new String(Files.readAllBytes(styleFile.toPath()), StandardCharsets.UTF_8),
                "落盘内容与内存中的资源不一致");

        // 幂等：再次调用不报错、不重写内容
        HtmlTreeAssets.ensureReferenced(html, outputDir.toFile());
        assertEquals(HtmlTreeAssets.text(HtmlTreeAssets.STYLE),
                new String(Files.readAllBytes(styleFile.toPath()), StandardCharsets.UTF_8),
                "重复调用把已有文件写坏了");
    }

    @Test
    @DisplayName("没引用任何资源的产物不会凭空多出文件")
    void ensureReferencedIgnoresUnrelatedContent() {
        HtmlTreeAssets.ensureReferenced("<html><body>nothing</body></html>", outputDir.toFile());

        assertEquals(0, outputDir.toFile().listFiles().length, "无关产物也写入了资源");
    }
}
