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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字段名不含敏感词、但容器 entry key 命中敏感名单时，凭证不得借内联值或整值格式化明文落报告。
 *
 * <p>覆盖两条取值路径（简单视图内联 / renderValue 整值格式化）与 6 种产物形态。
 */
class SensitiveCollectionValueTest {

    private static final String CREDENTIAL = "Bearer secret-token-9527";

    @TempDir
    Path outputRootDir;

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("6 种产物：简单视图内联路径不得泄露敏感 key 的凭证值")
    void shouldNotLeakCredentialInSimpleViewInline() {
        for (RenderType renderType : RenderType.values()) {
            String content = render(renderType, true, true);
            assertFalse(content.contains(CREDENTIAL), renderType + " 的简单视图内联值含凭证明文");
        }
    }

    @Test
    @DisplayName("关闭简单视图后走整值格式化，同样不得泄露凭证明文")
    void shouldNotLeakCredentialInWholeValueFormatting() {
        for (RenderType renderType : RenderType.values()) {
            String content = render(renderType, false, true);
            assertFalse(content.contains(CREDENTIAL), renderType + " 的整值格式化结果含凭证明文");
        }
    }

    @Test
    @DisplayName("脱敏开关关闭时仍输出明文，开关语义不被绑死")
    void shouldKeepPlaintextWhenMaskingDisabled() {
        assertTrue(render(RenderType.HTML, true, false).contains(CREDENTIAL),
                "关闭 maskSensitiveValues 后应照常输出明文");
    }

    @Test
    @DisplayName("逐项打码：同容器内非敏感 entry 仍原样展示")
    void shouldKeepOtherEntriesVisible() {
        String content = render(RenderType.HTML, true, true);
        assertTrue(content.contains("Authorization=***"), "敏感项应显示脱敏占位");
        assertTrue(content.contains("Accept=application/json"), "非敏感项不应被整段打码");
    }

    @Test
    @DisplayName("格式化入口：敏感 key 逐项打码，两参入口行为不变")
    void shouldMaskSensitiveEntryInFormatValue() {
        Map<String, String> headers = new Holder().headers;
        assertEquals("{Authorization=***, Accept=application/json}",
                CommonUtil.getStringFrom(headers, -1, true));
        assertTrue(CommonUtil.getStringFrom(headers, -1).contains(CREDENTIAL),
                "两参入口不脱敏，避免影响文件名/日志等非报告场景");
    }

    @Test
    @DisplayName("嵌套容器：脱敏标志沿递归传递到内层 Map")
    void shouldMaskSensitiveEntryInNestedContainer() {
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("payload", new Holder().headers);
        assertEquals("{payload={Authorization=***, Accept=application/json}}",
                CommonUtil.getStringFrom(outer, -1, true));
    }

    private String render(RenderType renderType, boolean simpleView, boolean masking) {
        AtomicReference<String> ref = new AtomicReference<>();
        JavaDataAnalyzer.runningConfigurer()
                .setOutputRootDir(outputRootDir.toString())
                .setRenderType(renderType)
                .setEnableCollectionSimpleView(simpleView)
                .setMaskSensitiveValues(masking)
                .addAnalyzingObject(new Holder())
                .run((OutputContentReceiver) ref::set);
        return ref.get();
    }

    static class Holder {

        private final Map<String, String> headers = new LinkedHashMap<>();

        Holder() {
            headers.put("Authorization", CREDENTIAL);
            headers.put("Accept", "application/json");
        }
    }
}
