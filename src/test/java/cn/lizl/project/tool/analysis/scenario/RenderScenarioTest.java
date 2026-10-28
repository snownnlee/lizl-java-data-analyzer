package cn.lizl.project.tool.analysis.scenario;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 原 {@code src/test/java/test/{html,puml}/TestClassDataAnalyzer*} 共 12 个 main() 演示类的用例化版本。
 *
 * <p>这 12 个类实际只有 6 个场景，每个场景分别跑 HTML 与 PUML 两种渲染，所以按
 * 「6 场景 × 3 渲染类型」写成参数化用例，与原文件一一对应；{@code PUML_HTML} 是后加的，
 * 细节由 {@code PumlHtmlRenderTest} 专门把关，这里只负责「换这个渲染类型也跑得通」。</p>
 *
 * <p>断言口径：只验证分析流程跑通、每次 run 都产出了非空内容，不做文本逐字比对——
 * 输出里含时间戳、对象哈希等不稳定内容，逐字比对只会变成维护负担。</p>
 */
class RenderScenarioTest {

    @BeforeEach
    void resetGlobals() {
        TestSupport.resetGlobalConfig();
    }

    /**
     * 原 test/{html,puml}/TestClassDataAnalyzer。
     *
     * <p>按类名分析：ShardingSphere 这个类不在本工程 classpath 内，顺带覆盖“类名加载失败后跳过”的分支。</p>
     */
    @ParameterizedTest(name = "按类名分析 Spring 组件 [{0}]")
    @EnumSource(value = RenderType.class, names = {"HTML", "PUML", "PUML_HTML", "MERMAID", "MERMAID_HTML"})
    void analyzingClassesByName(RenderType renderType) {
        List<String> contents = new ArrayList<>();

        assertDoesNotThrow(() -> JavaDataAnalyzer.runningConfigurer()
                .setRenderType(renderType)
                .addAnalyzingClass("org.apache.shardingsphere.driver.jdbc.core.datasource.ShardingSphereDataSource")
                .setOutputFileName("ShardingSphereDataSource数据结构图")
                .run((OutputContentReceiver) contents::add)
                .reset()
                .setRenderType(renderType)
                .addAnalyzingClass("org.springframework.web.servlet.DispatcherServlet")
                .addAnalyzingClass("org.springframework.web.multipart.support.StandardServletMultipartResolver")
                .setOutputFileName("DispatcherServlet数据结构图")
                .run((OutputContentReceiver) contents::add));

        // 只剩 1 次回调：ShardingSphere 那个类名在 classpath 中不存在，addAnalyzingClass 会告警并跳过，
        // 于是第一次 run 时待分析列表为空，run 直接返回、不回调接收器。这是既有行为，用例按实际锁定它。
        assertCaptured(contents);
    }

    /** 原 test/{html,puml}/TestClassDataAnalyzer2：集合类型与集合实例混合分析。 */
    @ParameterizedTest(name = "集合类型与集合实例 [{0}]")
    @EnumSource(value = RenderType.class, names = {"HTML", "PUML", "PUML_HTML", "MERMAID", "MERMAID_HTML"})
    void analyzingCollectionsAndInstances(RenderType renderType) {
        JavaDataAnalyzer.globalConfigurer()
                .setAnalysisMode(AnalysisMode.AUTO_BY_CONFIG)
                .setDefaultToExpand(true)
                .setShouldAnalyzeNullValue(true)
                .setAddToStringField(true)
                .apply();

        List<Object> list = new LinkedList<>();
        for (int i = 0; i < 3; i++) {
            list.add("Test" + (i + 1));
        }

        List<String> contents = new ArrayList<>();
        assertDoesNotThrow(() -> JavaDataAnalyzer.runningConfigurer()
                .setRenderType(renderType)
                .addAnalyzingClass(HashMap.class)
                .addAnalyzingClass(Hashtable.class)
                .addAnalyzingClass(ArrayList.class)
                .addAnalyzingClass(LinkedList.class)
                .addAnalyzingObject(list)
                .setOutputFileName("collections")
                .run((OutputContentReceiver) contents::add));

        assertCaptured(contents);
    }

    /**
     * 原 test/{html,puml}/TestClassDataAnalyzer3：线程池实例 + 自引用对象 + 成员类型互为环的局部内部类。
     *
     * <p>其中“把一个正在配置中的 configurer 当成分析对象”是原演示的写法，正好构成天然的自引用场景。</p>
     */
    @ParameterizedTest(name = "线程池与环引用 [{0}]")
    @EnumSource(value = RenderType.class, names = {"HTML", "PUML", "PUML_HTML", "MERMAID", "MERMAID_HTML"})
    void analyzingThreadPoolWithCircularReference(RenderType renderType) throws Exception {
        JavaDataAnalyzer.globalConfigurer()
                .setCommonExcludePackages("java.lang")
                .setShouldAnalyzeNullValue(true)
                .setDefaultToExpand(true)
                .apply();

        ExecutorService executor = Executors.newCachedThreadPool();
        List<String> contents = new ArrayList<>();
        try {
            executor.submit(() -> "ran").get(5, TimeUnit.SECONDS);

            @SuppressWarnings("unused")
            class InnerTestClass {
                Map<InnerTestClass, RenderScenarioTest> complexField;
            }

            assertDoesNotThrow(() -> JavaDataAnalyzer.runningConfigurer()
                    .setRenderType(renderType)
                    .setOutputFileName("threadPoolAndCircularReference")
                    .setCommonExcludePackages("java.lang", "java.security", "java.util.HashSet",
                            "java.util.logging", "java.util.concurrent.atomic")
                    .addAnalyzingObject(executor)
                    .addAnalyzingObject(JavaDataAnalyzer.runningConfigurer().addAnalyzingObject("aaa"))
                    .addAnalyzingObject(InnerTestClass.class)
                    .run((OutputContentReceiver) contents::add));
        } finally {
            executor.shutdownNow();
        }

        assertCaptured(contents);
    }

    /** 原 test/{html,puml}/TestClassDataAnalyzer4：枚举数组。 */
    @ParameterizedTest(name = "枚举数组 [{0}]")
    @EnumSource(value = RenderType.class, names = {"HTML", "PUML", "PUML_HTML", "MERMAID", "MERMAID_HTML"})
    void analyzingEnumArray(RenderType renderType) {
        JavaDataAnalyzer.globalConfigurer()
                .setDefaultToExpand(true)
                .apply();

        List<String> contents = new ArrayList<>();
        assertDoesNotThrow(() -> JavaDataAnalyzer.runningConfigurer()
                .setRenderType(renderType)
                .addAnalyzingObject(Arrays.asList(TestEnum.values()))
                .setOutputFileName("enumArray")
                .run((OutputContentReceiver) contents::add));

        assertCaptured(contents);
    }

    /** 原 test/{html,puml}/TestClassDataAnalyzer5：自定义 Map 子类，字段与集合键值混合。 */
    @ParameterizedTest(name = "自定义 Map 子类 [{0}]")
    @EnumSource(value = RenderType.class, names = {"HTML", "PUML", "PUML_HTML", "MERMAID", "MERMAID_HTML"})
    void analyzingCustomMapSubclass(RenderType renderType) {
        @SuppressWarnings({"unused"})
        class DiyMap extends HashMap<String, Object> {

            private final String name = "DiyMap";

            DiyMap() {
                put("k1", "v1");
                put("k2", "v2");
                put("k3", "v3");
            }
        }

        List<String> contents = new ArrayList<>();
        assertDoesNotThrow(() -> JavaDataAnalyzer.runningConfigurer()
                .setRenderType(renderType)
                .setOutputFileName("customMapSubclass")
                .setDefaultToExpand(true)
                .setAddToStringField(true)
                .setEnableCollectionSimpleView(true)
                .addAnalyzingObject(new DiyMap())
                .run((OutputContentReceiver) contents::add));

        assertCaptured(contents);
    }

    /** 原 test/{html,puml}/TestClassDataAnalyzer6：纳入包配置为空字符串时的 HashMap 分析。 */
    @ParameterizedTest(name = "纳入包为空时的 HashMap [{0}]")
    @EnumSource(value = RenderType.class, names = {"HTML", "PUML", "PUML_HTML", "MERMAID", "MERMAID_HTML"})
    void analyzingHashMapWithEmptyIncludePackages(RenderType renderType) {
        JavaDataAnalyzer.globalConfigurer()
                .setCommonIncludePackages("")
                .apply();

        List<String> contents = new ArrayList<>();
        assertDoesNotThrow(() -> JavaDataAnalyzer.runningConfigurer()
                .setRenderType(renderType)
                .addAnalyzingObject(HashMap.class)
                .setOutputFileName("hashMapWithEmptyIncludePackages")
                .setDefaultToExpand(true)
                .run((OutputContentReceiver) contents::add));

        assertCaptured(contents);
    }

    private static void assertCaptured(List<String> contents) {
        assertEquals(1, contents.size(), "渲染次数与预期不符，说明流程没有走到渲染环节");
        for (String content : contents) {
            assertNotNull(content, "渲染内容为 null");
            assertFalse(content.trim().isEmpty(), "渲染内容为空");
        }
    }

    enum TestEnum {
        AA, BB
    }
}
