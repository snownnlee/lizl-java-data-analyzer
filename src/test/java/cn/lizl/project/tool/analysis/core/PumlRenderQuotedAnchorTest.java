package cn.lizl.project.tool.analysis.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PUML 边的成员锚定 {@code C::field} 对特殊字符成员名的引号处理。
 *
 * <p>背景（2026-10-26 testbed 实测）：值节点的 {@code [this]} 成员出现在边起点时，
 * 生成的 {@code C295::[this] --> C343} 是 PlantUML 非法语法——{@code ClassName::成员}
 * 中成员名以方括号开头会被当成数组记号，PlantUML 不画图而是输出
 * 「Syntax Error? (Assumed diagram type: class)」的错误告示图，被 PUML_HTML 原样内联。
 * 修复：成员名含锚定语法不接受的字符时，把 {@code C::field} 整体加引号（实测保留成员锚定语义；
 * 而 {@code C::"field"} 非法，不能只引成员名）。</p>
 */
class PumlRenderQuotedAnchorTest {

    @Test
    @DisplayName("合成成员名 [this]/[toString()] 需要整体加引号")
    void shouldQuoteSyntheticMembers() {
        assertTrue(PumlRender.needsQuotedAnchor("[this]"), "[this] 裸写会让 PlantUML 报语法错误");
        assertTrue(PumlRender.needsQuotedAnchor("[toString()]"), "[toString()] 含方括号与圆括号，同样需要加引号");
    }

    @Test
    @DisplayName("常规标识符与 this$0、点号不需要加引号")
    void shouldNotQuotePlainMembers() {
        assertFalse(PumlRender.needsQuotedAnchor("segments"));
        assertFalse(PumlRender.needsQuotedAnchor("this$0"), "实测 this$0 裸锚定合法（1.2025.0/1.2026.0）");
        assertFalse(PumlRender.needsQuotedAnchor("a.b"));
        assertFalse(PumlRender.needsQuotedAnchor("_x9$"));
    }
}
