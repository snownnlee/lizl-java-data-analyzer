package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块封装降级用例（对应分析报告 P3-6）。
 *
 * <p>历史缺陷：{@code field.setAccessible(true)} 在 JDK 16+ 对未开放模块会抛
 * {@code InaccessibleObjectException}，被笼统的 {@code catch (Exception)} 接住后包成
 * 「错误：获取字段值 … 发生异常」塞进报告——让人以为是本工具坏了，实际是运行环境限制。</p>
 *
 * <p><b>取证边界</b>：JDK 9~15 默认 {@code --illegal-access=permit}，只会打印告警、仍可访问，
 * 复现不了；JDK 8 根本没有模块系统。所以本用例在 JDK 16 以下自动跳过，
 * 默认构建（JDK 8）不会执行它，需在 JDK 16+ 上跑一次才能验证。</p>
 */
class EncapsulationDegradeTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("强封装下不再报成错误，改为一条提示 + --add-opens 建议")
    void shouldDegradeToHintInsteadOfError() {
        assumeTrue(isJdkInternalsSealed(), "当前 JDK 仍允许反射访问内部成员，无法复现模块封装");

        StringBuilder content = new StringBuilder();
        JavaDataAnalyzer.runningConfigurer()
                // Integer.value 是 java.base/java.lang 里的私有字段，默认不对外开放
                .setCommonIncludePackages("java.lang.Integer")
                .setCommonExcludePackages()
                .addAnalyzingObject(Integer.valueOf(42))
                .run((OutputContentReceiver) content::append);

        String html = content.toString();
        assertFalse(html.contains("错误：获取字段值"), "模块封装被当成错误混进了报告");
        assertFalse(html.contains("InaccessibleObjectException"), "异常类名不该出现在产物里");
        assertTrue(html.contains("--add-opens"), "缺少可操作的启动参数建议");
        assertTrue(html.contains("java.base/java.lang"), "建议里应给出模块与包名");
        assertEquals(1, count(html, "提示：该类部分字段因模块封装无法反射读取"),
                "同类提示应只出现一次，不该每个字段刷一条");
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

    /** JDK 16 起默认拒绝深层反射；换成判断版本号，避免真的去 setAccessible 触发告警 */
    private static boolean isJdkInternalsSealed() {
        String version = System.getProperty("java.specification.version", "1.8");
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        return Integer.parseInt(version) >= 16;
    }
}
