package cn.lizl.project.tool.analysis;

import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原 {@code src/test/java/UsageExample.java} 的用例化版本。
 *
 * <p>该示例原本放在默认包里，作用是演示“全部配置项 + 三种 run 重载”的串联用法。
 * 转成用例后仍然完整走一遍这条链路，但输出目录改到 {@link TempDir}，不再往用户目录写文件。</p>
 */
class ApiUsageTest {

    @BeforeEach
    void resetGlobals() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("全量配置项与三种 run 重载串联执行")
    void fullConfigurationChain(@TempDir Path outputDir) {
        List<String> contents = new ArrayList<>();
        List<File> files = new ArrayList<>();

        assertDoesNotThrow(() -> {
            JavaDataAnalyzer.globalConfigurer()
                    .setCommonExcludePackagesToDefault()
                    .setGlobalOutputRootDir(outputDir.toString())
                    .setThrowExceptionWhenStringClassNotFound(false)
                    .setClassLoader(ApiUsageTest.class.getClassLoader())
                    .setCommonIncludePackages("inc1.", "inc2.")
                    .addCommonIncludePackages("inc3.", "inc4.", "inc5.")
                    .setCommonExcludePackages("exc1.", "exc2.")
                    .addCommonExcludePackages("exc3.", "exc4.", "exc5.")
                    .setAnalyzeStaticField(false)
                    .setValueDisplayLengthLimit(500)
                    .setCollectionsAnalyzingCount(20)
                    .setShouldAnalyzeNullValue(false)
                    .setAnalyzingMaxDepth(10)
                    .setDefaultToExpand(false)
                    .setAnalysisMode(AnalysisMode.AUTO_BY_COMPUTE)
                    .setEnableCollectionSimpleView(true)
                    .setAddToStringField(false)
                    .setRenderType(RenderType.HTML)
                    .apply();

            JavaDataAnalyzer.runningConfigurer()
                    .reset(false)
                    .clear()
                    .setOutputRootDir(outputDir.toString())
                    .setOutputFileName("today", "XXX分析")
                    .addAnalyzingClass("org.springframework.web.servlet.DispatcherServlet")
                    .addAnalyzingClass(Thread.class)
                    .addAnalyzingObject(new Object())
                    .run((OutputContentReceiver) contents::add)
                    .run((OutputContentReceiver) contents::add)
                    .run((OutputFileReceiver) files::add);
        });

        assertEquals(2, contents.size(), "两次内容输出的结果数不符");
        for (String content : contents) {
            assertFalse(content.trim().isEmpty(), "渲染内容为空");
        }

        assertEquals(1, files.size(), "文件输出未回调");
        File outputFile = files.get(0);
        assertTrue(outputFile.isFile(), "输出文件不存在：" + outputFile);
        assertTrue(outputFile.getName().endsWith(".html"), "未按渲染类型补齐扩展名：" + outputFile.getName());
    }
}
