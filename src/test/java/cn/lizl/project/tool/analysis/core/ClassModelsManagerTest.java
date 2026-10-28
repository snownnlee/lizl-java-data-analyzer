package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已分析节点表的回归用例。
 *
 * <p>历史缺陷：{@code classModelsMap} 用普通 {@code HashMap} 以对象为键，即 equals 语义；
 * 而 {@code findClassModelByClassAndInstance} 却用 {@code ==} 语义。两套口径并存导致
 * 「值相等但不同实例」被当成同一节点，且会调用对象的 {@code hashCode()}。</p>
 */
class ClassModelsManagerTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("equals 相等但不同实例应视为两个节点，而不是复用同一个")
    void shouldDistinguishEqualButDistinctInstances() {
        RunningConfig runningConfig = new RunningConfig();
        ClassModelsManager manager = new ClassModelsManager(runningConfig, Collections.emptyList());

        // 不能用 String：java. 默认被排除，被排除的类会转成「自值节点」而不进节点表
        EqualValue first = new EqualValue("same");
        EqualValue second = new EqualValue("same");
        assertEquals(first, second, "前提：两者 equals 相等");
        assertNotSame(first, second, "前提：两者不是同一个对象");

        ClassModel firstModel = new ClassModel(runningConfig, manager, EqualValue.class, first, 1);
        ClassModel secondModel = new ClassModel(runningConfig, manager, EqualValue.class, second, 1);

        assertSame(firstModel, manager.findClassModelByInstance(first));
        assertSame(secondModel, manager.findClassModelByInstance(second),
                "两个不同实例被当成同一个节点，会生成错误的链接");
    }

    @Test
    @DisplayName("hashCode 抛异常的对象也能正常分析（不再以对象为 HashMap 键）")
    void shouldNotCallHashCodeOfAnalyzedObject() {
        RunningConfigurer configurer = JavaDataAnalyzer.runningConfigurer();
        StringBuilder content = new StringBuilder();

        // run 的两个重载都是函数式接口，裸 lambda 编译歧义，必须显式转型
        configurer.addAnalyzingObject(new BadHash()).run((OutputContentReceiver) content::append);

        assertTrue(content.length() > 0, "产物不应为空");
        assertTrue(content.toString().contains("BadHash"), "产物应包含被分析类名");
    }

    @SuppressWarnings("unused")
    static class BadHash {

        private String value = "x";

        @Override
        public int hashCode() {
            throw new IllegalStateException("分析过程不应调用被分析对象的 hashCode()");
        }
    }

    static class EqualValue {

        private final String value;

        EqualValue(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualValue && this.value.equals(((EqualValue) other).value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }
}
