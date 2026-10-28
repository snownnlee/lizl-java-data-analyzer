package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputFileReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
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
 * 渲染类型切换用例。
 *
 * <p>历史缺陷：{@code BaseRunningConfigurer} 只在 {@code render == null} 时创建渲染器，
 * {@code setRenderType} 与 {@code reset} 都不清缓存。于是一个 configurer 实例跑过一次后
 * 再换渲染类型，仍旧沿用第一次那个渲染器——设了 {@code PUML} 却拿到 {@code .html}。</p>
 *
 * <p>产物扩展名由渲染器自己决定（HTML/PUML_HTML 是 {@code .html}，PUML 是 {@code .puml}），
 * 所以扩展名就是「到底用了哪个渲染器」最直接的观察点。</p>
 */
class RenderTypeSwitchTest {

    @TempDir
    Path outputRootDir;

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("同一个 configurer 换渲染类型后应换渲染器，不沿用第一次的")
    void shouldSwitchRenderTypeOnSameConfigurer() {
        RunningConfigurer configurer = JavaDataAnalyzer.runningConfigurer()
                .setOutputRootDir(outputRootDir.toString())
                .setRenderType(RenderType.HTML)
                .addAnalyzingObject(new Sample());
        List<File> outputFiles = new ArrayList<>();

        configurer.run((OutputFileReceiver) outputFiles::add);
        configurer.setRenderType(RenderType.PUML);
        configurer.run((OutputFileReceiver) outputFiles::add);

        assertEquals(2, outputFiles.size(), "渲染次数与预期不符");
        assertTrue(outputFiles.get(0).getName().endsWith(".html"), "第一次应为 HTML 产物");
        assertTrue(outputFiles.get(1).getName().endsWith(".puml"),
                "换渲染类型后仍沿用旧渲染器，产物为 " + outputFiles.get(1).getName());
    }

    @Test
    @DisplayName("reset 退回全局渲染类型后，渲染器也应跟着退回")
    void shouldFollowGlobalRenderTypeAfterReset() {
        JavaDataAnalyzer.globalConfigurer().setRenderType(RenderType.PUML).apply();

        RunningConfigurer configurer = JavaDataAnalyzer.runningConfigurer()
                .setOutputRootDir(outputRootDir.toString())
                .setRenderType(RenderType.HTML)
                .addAnalyzingObject(new Sample());
        List<File> outputFiles = new ArrayList<>();

        configurer.run((OutputFileReceiver) outputFiles::add);

        // reset() 会清空待分析列表并把输出目录退回全局值，所以后面要重新配
        configurer.reset()
                .setOutputRootDir(outputRootDir.toString())
                .addAnalyzingObject(new Sample());
        configurer.run((OutputFileReceiver) outputFiles::add);

        assertEquals(2, outputFiles.size(), "渲染次数与预期不符");
        assertTrue(outputFiles.get(0).getName().endsWith(".html"), "第一次应为 HTML 产物");
        assertTrue(outputFiles.get(1).getName().endsWith(".puml"),
                "reset 退回全局 PUML 后仍沿用旧渲染器，产物为 " + outputFiles.get(1).getName());
    }

    @SuppressWarnings("unused")
    static class Sample {

        private String name = "sample";

        private int count = 1;
    }
}
