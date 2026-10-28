package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML 文本转义用例（对应分析报告 P3-2、P3-10）。
 *
 * <p>历史缺陷：{@code escapeContent} 只替换了 {@code <} 与 {@code >}，
 * 值里含 {@code &} 时原样输出，其中的 {@code &lt;}、{@code &amp;} 片段会被浏览器
 * 还原成 {@code <}、{@code &}，渲染结果与真实值不符；且方法开头直接
 * {@code content.contains(...)}，传 {@code null} 会 NPE。</p>
 */
class HtmlRenderEscapeTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("字段值里的 & 要转义成 &amp;（旧实现原样输出）")
    void shouldEscapeAmpersandInFieldValue() {
        StringBuilder content = new StringBuilder();

        JavaDataAnalyzer.runningConfigurer()
                .addAnalyzingObject(new AmpersandHolder())
                .run((OutputContentReceiver) content::append);

        String html = content.toString();
        assertTrue(html.contains("P&amp;Q&lt;R&gt;"),
                "值里的 & 未被转义，浏览器会把后续片段当成 HTML 实体还原");
        assertFalse(html.contains("P&Q&lt;R&gt;"),
                "仍存在未转义的 & 组合，说明只处理了尖括号");
    }

    @Test
    @DisplayName("& 不能二次转义：值里本来是 &lt; 时应输出 &amp;lt;")
    void shouldNotDoubleEscapeExistingEntity() {
        assertEquals("&amp;lt;", HtmlRender.escapeContent("&lt;"),
                "值里的 &lt; 必须当成普通文本，转义后应是 &amp;lt;");
    }

    @Test
    @DisplayName("尖括号与 & 混合时逐个转义，顺序不影响结果")
    void shouldEscapeMixedCharacters() {
        assertEquals("&lt;T&gt; &amp; co", HtmlRender.escapeContent("<T> & co"));
        assertEquals("a&amp;b", HtmlRender.escapeContent("a&b"));
        assertEquals("&amp;amp;&amp;lt;", HtmlRender.escapeContent("&amp;&lt;"));
    }

    @Test
    @DisplayName("不含特殊字符时原样返回同一个实例")
    void shouldReturnSameContentWhenNothingToEscape() {
        String plain = "no special characters";
        assertSame(plain, HtmlRender.escapeContent(plain), "无特殊字符时应直接返回原实例，不新建字符串");
        assertEquals("", HtmlRender.escapeContent(""));
    }

    @Test
    @DisplayName("null 原样返回 null，不抛 NPE（旧实现直接 NPE）")
    void shouldReturnNullForNullContent() {
        assertNull(HtmlRender.escapeContent(null));
    }

    @SuppressWarnings("unused")
    static class AmpersandHolder {

        private String value = "P&Q<R>";
    }

    /**
     * Info 节点（上限警告、异常消息、模块封装提示）也是文本节点，必须一并转义。
     *
     * <p>历史实现直接把 {@code info} 拼进 {@code <pre>}，没有走 {@code escapeContent}：
     * 异常消息里一旦出现 {@code <}／{@code &}，结构就被打破。这里直接构造 Info 节点，
     * 因为走真实分析流程无法稳定造出含尖括号的消息。</p>
     */
    @Test
    @DisplayName("Info 类节点文本要转义")
    void shouldEscapeInfoClassModelText() {
        RunningConfig runningConfig = new RunningConfig();
        ClassModelsManager classModelsManager = new ClassModelsManager(runningConfig, Collections.emptyList());
        ClassModel.InfoClassModel infoClassModel =
                new ClassModel.InfoClassModel(runningConfig, classModelsManager, "警告：未解析类型 <T> & co", 1);

        String html = new HtmlRender(runningConfig).render(new ClassModel[]{infoClassModel});

        assertTrue(html.contains("警告：未解析类型 &lt;T&gt; &amp; co"), "Info 类节点文本未转义");
    }

    @Test
    @DisplayName("Info 字段节点文本要转义")
    void shouldEscapeInfoFieldModelText() {
        RunningConfig runningConfig = new RunningConfig();
        ClassModelsManager classModelsManager = new ClassModelsManager(runningConfig, Collections.emptyList());
        ClassModel parentClassModel = new ClassModel(runningConfig, classModelsManager, AmpersandHolder.class, null, 1);
        parentClassModel.fieldModels.add(
                new FieldModel.InfoFieldModel(runningConfig, parentClassModel, "错误：<init> 失败 & 中止"));

        String html = new HtmlRender(runningConfig).render(new ClassModel[]{parentClassModel});

        assertTrue(html.contains("错误：&lt;init&gt; 失败 &amp; 中止"), "Info 字段节点文本未转义");
    }

    // ---------------- 敏感字段脱敏（对应 v2.7.9：mask-sensitive-values 默认 true） ----------------

    @Test
    @DisplayName("敏感字段（password/token）值渲染为 ***，不落明文；普通字段不受影响")
    void shouldMaskSensitiveFieldValuesByDefault() {
        StringBuilder content = new StringBuilder();

        JavaDataAnalyzer.runningConfigurer()
                .addAnalyzingObject(new CredentialsHolder())
                .run((OutputContentReceiver) content::append);

        String html = content.toString();
        assertFalse(html.contains("plain-pass-123"), "password 明文不应出现在报告里");
        assertFalse(html.contains("bearer-token-456"), "authToken 明文不应出现在报告里");
        assertTrue(html.contains("***"), "敏感字段应渲染为 *** 占位");
        assertTrue(html.contains("张三"), "非敏感字段（userName）应照常显示真实值");
    }

    @Test
    @DisplayName("maskSensitiveValues=false 时敏感字段恢复明文显示")
    void shouldShowRawValueWhenMaskingDisabled() {
        StringBuilder content = new StringBuilder();

        JavaDataAnalyzer.globalConfigurer().setMaskSensitiveValues(false).apply();
        JavaDataAnalyzer.runningConfigurer()
                .addAnalyzingObject(new CredentialsHolder())
                .run((OutputContentReceiver) content::append);

        String html = content.toString();
        assertTrue(html.contains("plain-pass-123"), "关闭脱敏后 password 应显示明文");
        assertFalse(html.contains("***"), "关闭脱敏后不应再有占位符");
    }

    @Test
    @DisplayName("PUML 通道同样脱敏：敏感字段值渲染为 ***")
    void shouldMaskSensitiveFieldValuesInPuml() {
        StringBuilder content = new StringBuilder();

        JavaDataAnalyzer.runningConfigurer()
                .setRenderType(RenderType.PUML)
                .addAnalyzingObject(new CredentialsHolder())
                .run((OutputContentReceiver) content::append);

        String puml = content.toString();
        assertFalse(puml.contains("plain-pass-123"), "PUML 报告不应落 password 明文");
        assertTrue(puml.contains("***"), "PUML 敏感字段应渲染为 *** 占位");
    }

    @SuppressWarnings("unused")
    static class CredentialsHolder {

        private String userName = "张三";

        private String password = "plain-pass-123";

        private String authToken = "bearer-token-456";
    }
}
