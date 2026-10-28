package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 枚举在 PUML 系产物里的展开口径用例。
 *
 * <p>口径：**实例分析**时不展开枚举——字段行里的 {@code = PAID} 已表达同一事实，
 * 枚举的常量清单与 {@code $VALUES} 只会把静态图撑长；**纯类型分析**时字段行只有类型名、没有值，
 * 常量清单正是要看的信息，枚举节点必须保留。另外「直接分析一个枚举」时它是根节点，始终渲染。</p>
 *
 * <p>HTML 通道不受影响：那里枚举一直是可折叠节点，用户可以按需展开。</p>
 */
class PumlEnumRenderTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("实例分析：PUML 不应为枚举建节点，也不应连出边")
    void shouldNotEmitEnumNodeInInstanceAnalysis() {
        String puml = renderContent(RenderType.PUML);

        assertFalse(puml.contains("enum \""), "PUML 里出现了枚举节点：\n" + puml);
        assertFalse(puml.contains("CREATED"), "PUML 里出现了枚举常量：\n" + puml);
        assertFalse(puml.contains("-->"), "不应有指向枚举的连线：\n" + puml);
        assertTrue(puml.contains("PAID"), "字段行应保留枚举值 = PAID：\n" + puml);
    }

    @Test
    @DisplayName("纯类型分析：枚举节点与常量清单应保留")
    void shouldKeepEnumNodeInTypeOnlyAnalysis() {
        String puml = renderTypeOnly();

        assertTrue(puml.contains("enum \""), "纯类型分析下枚举节点不应被去掉：\n" + puml);
        assertTrue(puml.contains("CREATED"), "纯类型分析下应保留枚举常量：\n" + puml);
        assertTrue(puml.contains("-->"), "应保留指向枚举节点的连线：\n" + puml);
    }

    @Test
    @DisplayName("PUML_HTML 实例分析结果里同样不应出现枚举节点")
    void shouldNotEmitEnumNodeInPumlHtml() {
        String html = renderContent(RenderType.PUML_HTML);

        // 图由浏览器端引擎渲染，能断言的是嵌进页面的 PUML 文本
        int start = html.indexOf("PlantUML.render([");
        assertTrue(start >= 0, "外壳没有把 PUML 行数组交给渲染引擎");
        String puml = html.substring(start);

        assertFalse(puml.contains("SHIPPED"), "PUML 里出现了枚举常量，说明枚举仍被展开成节点");
        assertTrue(puml.contains("PAID"), "字段行应保留枚举值 PAID");
    }

    @Test
    @DisplayName("直接分析枚举本身时仍应渲染，否则产物是空图")
    void shouldStillRenderEnumAsRoot() {
        String puml = renderAsRoot(Status.class);

        assertTrue(puml.contains("enum \""), "根节点是枚举时仍应输出 enum 节点：\n" + puml);
        assertTrue(puml.contains("CREATED"), "根节点是枚举时应保留常量清单：\n" + puml);
    }

    private String renderContent(RenderType renderType) {
        List<String> contents = new ArrayList<>();
        JavaDataAnalyzer.runningConfigurer()
                .setRenderType(renderType)
                .addAnalyzingObject(new Holder())
                .run((OutputContentReceiver) contents::add);

        assertEquals(1, contents.size(), "应恰好回调一次");
        return contents.get(0);
    }

    /** 纯类型分析：只给类型不给实例，走 {@code ONLY_CLASS} 分支 */
    private String renderTypeOnly() {
        List<String> contents = new ArrayList<>();
        JavaDataAnalyzer.runningConfigurer()
                .setRenderType(RenderType.PUML)
                .setAnalysisMode(AnalysisMode.ONLY_CLASS)
                .addAnalyzingClass(Holder.class)
                .run((OutputContentReceiver) contents::add);

        assertEquals(1, contents.size(), "应恰好回调一次");
        return contents.get(0);
    }

    private String renderAsRoot(Class<?> type) {
        List<String> contents = new ArrayList<>();
        JavaDataAnalyzer.runningConfigurer()
                .setRenderType(RenderType.PUML)
                .addAnalyzingClass(type)
                .run((OutputContentReceiver) contents::add);

        assertEquals(1, contents.size(), "应恰好回调一次");
        return contents.get(0);
    }

    enum Status {
        CREATED, PAID, SHIPPED
    }

    @SuppressWarnings("unused")
    static class Holder {

        private Status status = Status.PAID;

        /** 集合里的枚举走「包装层折叠」这条独立路径，一并覆盖：包装层不建节点，元素也不能冒出节点 */
        private List<Status> history = Arrays.asList(Status.CREATED, Status.PAID);
    }
}
