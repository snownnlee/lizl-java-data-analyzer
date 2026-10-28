package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 被排除包里的集合实例作为分析根时的成环处理。
 *
 * <p>背景：{@code java.*} 这类不可分析的类只以「值节点」形态出现（{@code [this]} + toString），
 * 建节点时会把自己从已分析节点表里注销。若先注销再建 {@code [this]} 节点，自引用的集合实例
 * 在 {@code FieldModel.analyzeSourceValue} 里就查不到自己这个节点，于是被当成新实例重建一次；
 * 而该实例同时是一个分析根时，重建走的是 {@code findClassModelByClassAndInstance} 的
 * 「按根建节点」分支，深度被写死成 1，深度上限永远拦不住 —— 一路递归到 {@code StackOverflowError}。</p>
 *
 * <p>两个用例都必须打开 {@code enableCollectionSimpleView = false}：默认的集合简化视图会在
 * 触到 {@code analyzeSourceValue} 末尾那行 {@code shouldAnalyze(value.getClass(), true)} 之前就
 * 迭代元素并返回，根本走不到结症所在的分支。</p>
 */
class ExcludedCollectionRootTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("自引用的集合根不应栈溢出")
    void shouldNotOverflowOnSelfReferencingCollectionRoot() {
        Map<String, Object> map = new HashMap<>();
        map.put("self", map);

        String html = render(map);

        assertTrue(html.contains("class='root_class_model'"), "自引用的集合仍然应该正常产出根节点");
    }

    @Test
    @DisplayName("同一个集合实例被另一个根引用时补成链接，而不是再建一个根节点")
    void shouldLinkToExcludedRootInsteadOfDuplicatingIt() {
        Map<String, Object> map = new HashMap<>();
        map.put("key", "value");

        String html = render(map, new Holder(map));

        assertEquals(2, count(html, "class='root_class_model'"), "只登记了两个分析根，不应多出重复的根节点");
        assertEquals(1, count(html, "<a class='link_class_model_container'"), "引用同一个实例的字段应补成链接");
    }

    private static String render(Object... roots) {
        StringBuilder content = new StringBuilder();
        RunningConfigurer configurer = JavaDataAnalyzer.runningConfigurer().setEnableCollectionSimpleView(false);
        for (Object root : roots) {
            configurer.addAnalyzingObject(root);
        }
        configurer.run((OutputContentReceiver) content::append);
        return content.toString();
    }

    private static int count(String text, String key) {
        int count = 0;
        int index = text.indexOf(key);
        while (index >= 0) {
            count++;
            index = text.indexOf(key, index + key.length());
        }
        return count;
    }

    static class Holder {

        private Object value;

        Holder(Object value) {
            this.value = value;
        }
    }
}
