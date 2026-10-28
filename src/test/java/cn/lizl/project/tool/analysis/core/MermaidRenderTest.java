package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mermaid 双通道用例：枚举口径与 {@code PumlEnumRenderTest} 对齐；HTML 通道校验
 * CDN 引用、锁定版本、产物体积 KB 级（不内联脚本）。
 */
class MermaidRenderTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("实例分析：Mermaid 文本不应为枚举建节点，字段行保留枚举值")
    void shouldNotEmitEnumNodeInInstanceAnalysis() {
        String mmd = renderContent(RenderType.MERMAID);

        assertFalse(mmd.contains("<<enumeration>>"), "Mermaid 里出现了枚举节点：\n" + mmd);
        assertFalse(mmd.contains("CREATED"), "Mermaid 里出现了枚举常量：\n" + mmd);
        assertTrue(mmd.contains("= PAID"), "字段行应保留枚举值 = PAID：\n" + mmd);
        // 泛型应转成波浪线写法，不能把尖括号漏给 Mermaid 解析器
        assertFalse(mmd.contains("<"), "Mermaid 文本不应残留尖括号：\n" + mmd);
        assertTrue(mmd.contains("classDiagram"), "缺少 classDiagram 声明：\n" + mmd);
    }

    @Test
    @DisplayName("纯类型分析：枚举节点与常量清单应保留")
    void shouldKeepEnumNodeInTypeOnlyAnalysis() {
        String mmd = renderTypeOnly();

        assertTrue(mmd.contains("<<enumeration>>"), "纯类型分析下枚举节点不应被去掉：\n" + mmd);
        assertTrue(mmd.contains("CREATED"), "纯类型分析下应保留枚举常量：\n" + mmd);
        assertTrue(mmd.contains("-->"), "应保留指向枚举节点的连线：\n" + mmd);
    }

    @Test
    @DisplayName("直接分析枚举本身时仍应渲染，否则产物是空图")
    void shouldStillRenderEnumAsRoot() {
        String mmd = renderAsRoot(Status.class);

        assertTrue(mmd.contains("<<enumeration>>"), "根节点是枚举时仍应输出枚举节点：\n" + mmd);
        assertTrue(mmd.contains("CREATED"), "根节点是枚举时应保留常量清单：\n" + mmd);
    }

    @Test
    @DisplayName("MERMAID_HTML 应引用锁定版本的 mermaid 脚本，图文放在 pre.mermaid 里")
    void shouldReferenceMermaidScript() {
        String html = renderContent(RenderType.MERMAID_HTML);

        assertTrue(html.contains("mermaid.initialize"), "缺少 mermaid 初始化脚本");
        assertTrue(html.contains("<script src=\"./" + cn.lizl.project.tool.analysis.util.FrontendAssets.MERMAID + "\">"),
                "mermaid 脚本应引用同目录的引擎（文件名带版本）");
        assertFalse(html.contains("cdn.jsdelivr"), "产物不允许有外部请求，离线也要能看");
        assertTrue(html.contains("<pre class=\"mermaid\">"), "图文没有放进 pre.mermaid");
        assertTrue(html.contains("PAID"), "图文里应保留枚举值 PAID");
        assertTrue(html.contains("rel=\"icon\""), "缺少 favicon");
        // 产物不再内联 3.5 MB 脚本，体积必须回落到 KB 级
        assertTrue(html.length() < 64 * 1024, "产物体积异常，疑似又把脚本内联了：" + html.length());
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
                .setRenderType(RenderType.MERMAID)
                .setAnalysisMode(AnalysisMode.ONLY_CLASS)
                .addAnalyzingClass(Holder.class)
                .run((OutputContentReceiver) contents::add);

        assertEquals(1, contents.size(), "应恰好回调一次");
        return contents.get(0);
    }

    private String renderAsRoot(Class<?> type) {
        List<String> contents = new ArrayList<>();
        JavaDataAnalyzer.runningConfigurer()
                .setRenderType(RenderType.MERMAID)
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
