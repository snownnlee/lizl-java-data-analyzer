package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputFileReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出文件名用例（对应分析报告 P3-3）。
 *
 * <p>历史缺陷：{@code BaseRender.output} 直接拿 {@code runningConfig.outputFileName} 那个数组
 * 本体补扩展名、改非法字符，而这个数组就是调用方 {@code setOutputFileName(...)} 传进来的引用。
 * 于是渲染一次之后，调用方手里的数组被悄悄改掉了——把同一个数组再交给另一种渲染类型，
 * 就会得到 {@code a.html.puml}。</p>
 */
class OutputFileNameTest {

    @TempDir
    Path outputRootDir;

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("补扩展名不应改写调用方传入的数组")
    void shouldNotMutateCallerArrayWhenAppendingExtension() {
        String[] outputFileName = {"keepMe"};

        File outputFile = renderOnce(outputFileName, RenderType.HTML);

        assertEquals("keepMe", outputFileName[0], "调用方数组被补上了扩展名");
        assertEquals("keepMe.html", outputFile.getName());
        assertTrue(outputFile.isFile(), "产物文件不存在");
    }

    @Test
    @DisplayName("替换非法字符不应改写调用方传入的数组")
    void shouldNotMutateCallerArrayWhenSanitizing() {
        String[] outputFileName = {"bad/name:1"};

        File outputFile = renderOnce(outputFileName, RenderType.HTML);

        assertEquals("bad/name:1", outputFileName[0], "调用方数组被替换了非法字符");
        assertEquals("bad？name？1.html", outputFile.getName());
        assertTrue(outputFile.isFile(), "产物文件不存在");
    }

    @Test
    @DisplayName("同一个数组复用给另一种渲染类型时，扩展名应各自独立")
    void shouldKeepExtensionOfEachRenderTypeWhenArrayReused() {
        String[] outputFileName = {"reuseName"};

        File htmlFile = renderOnce(outputFileName, RenderType.HTML);
        File pumlFile = renderOnce(outputFileName, RenderType.PUML);

        assertEquals("reuseName.html", htmlFile.getName());
        assertEquals("reuseName.puml", pumlFile.getName(), "上一次渲染把扩展名写进了调用方的数组");
    }

    @Test
    @DisplayName("cmd 元字符（& ^ % !）应被清洗（防文件名进入 shell 命令行被二次解释）")
    void shouldSanitizeShellMetacharacters() {
        File outputFile = renderOnce(new String[]{"a&b^c%d!e.html"}, RenderType.HTML);

        assertEquals("a？b？c？d？e.html", outputFile.getName());
        assertTrue(outputFile.isFile(), "产物文件不存在");
    }

    @Test
    @DisplayName("多段文件名各段都清洗；中间段为 .. 时不得逃出输出根目录")
    void shouldSanitizeEverySegmentAndBlockDotDotTraversal() {
        // 中间段整段为 ..：若不清洗，Paths.get(root, "..", "x.html") 会把产物写到输出根目录之外
        File outputFile = renderOnce(new String[]{"..", "escaped.html"}, RenderType.HTML);

        String rootPath = outputRootDir.toAbsolutePath().toString();
        String outputPath = outputFile.getAbsoluteFile().getParent();
        assertTrue(outputPath.startsWith(rootPath),
                "产物被写到输出根目录之外（目录穿越未防住）：" + outputFile.getAbsolutePath());
        assertTrue(outputFile.isFile(), "产物文件不存在");
    }

    private File renderOnce(String[] outputFileName, RenderType renderType) {
        List<File> outputFiles = new ArrayList<>();

        JavaDataAnalyzer.runningConfigurer()
                .setOutputRootDir(outputRootDir.toString())
                .setRenderType(renderType)
                .setOutputFileName(outputFileName)
                .addAnalyzingObject(new Sample())
                .run((OutputFileReceiver) outputFiles::add);

        assertEquals(1, outputFiles.size(), "渲染次数与预期不符");
        return outputFiles.get(0);
    }

    @SuppressWarnings("unused")
    static class Sample {

        private String name = "sample";

        private int count = 1;
    }
}
