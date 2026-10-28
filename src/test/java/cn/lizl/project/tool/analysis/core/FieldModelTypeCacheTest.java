package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型判定缓存的回归用例。
 *
 * <p>历史缺陷：缓存是静态字段、只以 {@code Type} 为键且永不失效，而判定结果依赖包过滤配置。
 * 于是第一次排除某包后，第二次即使改了配置也拿不到该类，字段被静默跳过且不报错。</p>
 */
class FieldModelTypeCacheTest {

    /** 类节点标题里的类名（全限定名），用于区分「类节点」与「字段上的类型描述」 */
    private static final Pattern CLASS_NODE_NAME = Pattern.compile("class_model_title_display_name'>[^<]*");

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("同一类型换一份包过滤配置后重新判定，不会沿用上一次的排除结论")
    void shouldNotReuseTypeCacheAcrossConfigurations() {
        String excluded = analyze(configurer -> configurer.setCommonExcludePackages(Target.class.getName()));
        assertFalse(hasClassNode(excluded, "Target"), "已排除 Target，不应为它生成类节点");

        String included = analyze(configurer -> configurer.setCommonExcludePackages());
        assertTrue(hasClassNode(included, "Target"),
                "第二次没有排除 Target，必须重新判定并生成它的类节点（缓存串味时这里会失败）");
    }

    @Test
    @DisplayName("同一份配置连续分析两次，结果一致")
    void shouldBeStableForSameConfiguration() {
        assertTrue(hasClassNode(analyze(configurer -> configurer.setCommonExcludePackages()), "Target"));
        assertTrue(hasClassNode(analyze(configurer -> configurer.setCommonExcludePackages()), "Target"));
    }

    /**
     * 判断产物里是否出现了某个类的<b>类节点</b>。
     * <p>
     * 不能直接 {@code html.contains(className)}：字段的类型描述里也会写出类名，
     * 而被排除的类只会失去「自己的类节点」，字段上的类型名照旧渲染。
     */
    private static boolean hasClassNode(String html, String className) {
        Matcher matcher = CLASS_NODE_NAME.matcher(html);
        while (matcher.find()) {
            if (matcher.group().contains(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 以 ONLY_CLASS 模式分析 {@link Holder}：该模式下字段一律走类型判定（{@code findAnalyzableClasses}），
     * 正是缓存所在的那条路径。
     */
    private String analyze(Consumer<RunningConfigurer> customizer) {
        RunningConfigurer configurer = JavaDataAnalyzer.runningConfigurer()
                .setOutputRootDir(tempDir.toString())
                .setAnalysisMode(AnalysisMode.ONLY_CLASS);
        customizer.accept(configurer);

        StringBuilder content = new StringBuilder();
        // run 的两个重载都是函数式接口，裸 lambda 编译歧义，必须显式转型
        configurer.addAnalyzingObject(new Holder()).run((OutputContentReceiver) content::append);
        return content.toString();
    }

    @SuppressWarnings("unused")
    static class Holder {

        private Target target;
    }

    @SuppressWarnings("unused")
    static class Target {

        private String value = "x";
    }
}
