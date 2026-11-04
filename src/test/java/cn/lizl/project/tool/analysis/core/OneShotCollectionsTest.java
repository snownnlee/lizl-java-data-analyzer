package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.support.TestSupport;
import cn.lizl.project.tool.analysis.util.CommonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一次性容器（Iterator/Enumeration）默认不消费：分析不得改变对象状态，报告只显示占位提示；
 * 显式开启 analyzeOneShotCollections 后恢复 2.1.1 的消费行为。
 */
class OneShotCollectionsTest {

    @TempDir
    Path outputRootDir;

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("默认跳过：分析后迭代器未被消费，报告显示未消费占位")
    void shouldSkipOneShotByDefault() {
        Sample sample = new Sample();

        String content = render(sample, false);

        assertTrue(sample.numbers.hasNext(), "默认配置下分析不应消耗一次性迭代器");
        assertTrue(sample.enumeration.hasMoreElements(), "Enumeration 同样不应被消耗");
        assertTrue(content.contains("一次性容器，未消费"), "报告应显示未消费占位，实际：" + content);
    }

    @Test
    @DisplayName("显式开启：next()/nextElement() 被真实调用，两种一次性容器均被耗尽")
    void shouldConsumeWhenEnabled() {
        Sample sample = new Sample();

        render(sample, true);

        assertFalse(sample.numbers.hasNext(), "开启后 Iterator 应被耗尽");
        assertFalse(sample.enumeration.hasMoreElements(), "开启后 Enumeration 应被耗尽");
    }

    @Test
    @DisplayName("CommonUtil 四参入口：开关关闭时返回占位且不消耗迭代器")
    void shouldFormatWithoutConsuming() {
        Iterator<String> iterator = Arrays.asList("a", "b").iterator();

        String text = CommonUtil.getStringFrom(iterator, 100, false, false);

        assertTrue(iterator.hasNext(), "格式化不应消耗迭代器");
        assertTrue(text.contains("一次性容器，未消费"), "应返回未消费占位，实际：" + text);
    }

    @Test
    @DisplayName("CommonUtil 三参入口：保持原语义，照常消费并展开元素")
    void shouldKeepLegacySemanticsForThreeArgEntry() {
        Iterator<String> iterator = Arrays.asList("a", "b").iterator();

        String text = CommonUtil.getStringFrom(iterator, 100, false);

        assertFalse(iterator.hasNext(), "三参入口保持旧行为：真实消费迭代器");
        assertTrue(text.contains("[a -> b]"), "应展开元素值，实际：" + text);
    }

    private String render(Sample sample, boolean consumeOneShot) {
        AtomicReference<String> contentRef = new AtomicReference<>();
        JavaDataAnalyzer.runningConfigurer()
                .setOutputRootDir(outputRootDir.toString())
                .setRenderType(RenderType.HTML)
                .setAnalyzeOneShotCollections(consumeOneShot)
                .addAnalyzingObject(sample)
                .run((OutputContentReceiver) contentRef::set);
        return contentRef.get();
    }

    static class Sample {

        Iterator<String> numbers = Arrays.asList("one", "two").iterator();

        Enumeration<String> enumeration = Collections.enumeration(Arrays.asList("e1", "e2"));
    }
}
