package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简单视图下被包排除的集合元素值必须在字段行内联可见：tags : List&lt;String&gt; [2] = [alpha, beta]。
 * 分析不建节点是设计行为，渲染不能把值吞掉。
 */
class CollectionSimpleViewInlineTest {

    @TempDir
    Path outputRootDir;

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("PUML 通道：简单视图下被排除元素的值在字段行可见")
    void shouldInlineSimpleViewValuesInPuml() {
        String content = renderOnce(RenderType.PUML);
        String plain = content.replaceAll("<[^>]+>", " ").replaceAll("&#160;|\\s+", " ");

        assertTrue(plain.contains("tags : List") && plain.contains("= [alpha, beta]"),
                "字段行应展示元素值，实际：" + plain);
        assertTrue(plain.contains("scores : Map") && plain.contains("= {chinese=90, math=95}"),
                "Map 应展示 k=v，实际：" + plain);
        assertTrue(plain.contains("items : List") && plain.contains("Item"),
                "业务对象元素仍走节点路径");
    }

    @Test
    @DisplayName("HTML 通道：字段行同步展示元素值")
    void shouldInlineSimpleViewValuesInHtml() {
        String content = renderOnce(RenderType.HTML);

        assertTrue(content.contains("[alpha, beta]"), "HTML 字段行应包含元素值 [alpha, beta]");
        assertTrue(content.contains("{chinese=90, math=95}"), "HTML Map 应展示 k=v");
    }

    @Test
    @DisplayName("数组元素与集合同口径：被排除元素的值在字段行可见")
    void shouldInlineSimpleViewArrayValues() {
        String content = renderOnce(RenderType.PUML);
        String plain = content.replaceAll("<[^>]+>", " ").replaceAll("&#160;|\\s+", " ");

        assertTrue(plain.contains("codes : String[]") && plain.contains("= [a1, a2]"),
                "数组字段行应展示元素值 [a1, a2]，实际：" + plain);
    }

    @Test
    @DisplayName("业务对象元素不受影响：仍走节点展开路径")
    void shouldKeepBusinessElementsAsNodes() {
        String content = renderOnce(RenderType.PUML);

        assertTrue(content.contains("Item") || content.contains("items"),
                "业务对象元素应仍以节点/字段存在");
    }

    private String renderOnce(RenderType renderType) {
        Sample sample = new Sample();
        sample.tags.add("alpha");
        sample.tags.add("beta");
        sample.scores.put("chinese", 90);
        sample.scores.put("math", 95);

        AtomicReference<String> contentRef = new AtomicReference<>();
        JavaDataAnalyzer.runningConfigurer()
                .setOutputRootDir(outputRootDir.toString())
                .setRenderType(renderType)
                .addAnalyzingObject(sample)
                .run((OutputContentReceiver) contentRef::set);
        return contentRef.get();
    }

    @SuppressWarnings("unused")
    static class Sample {

        private String name = "sample";

        private List<String> tags = new ArrayList<>();

        private Map<String, Integer> scores = new LinkedHashMap<>();

        private String[] codes = {"a1", "a2"};

        private List<Item> items = new ArrayList<>();

        Sample() {
            items.add(new Item());
        }

        static class Item {

            private String label = "item-1";
        }
    }
}
