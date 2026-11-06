package cn.lizl.project.tool.analysis.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadNamesTest {

    @Test
    @DisplayName("角色 + 实例标识拼成可定位的线程名")
    void shouldJoinRoleAndIdentifiers() {
        assertEquals("lizl-java-data-analyzer-analysis-SpringBeans",
                ThreadNames.of("analysis", "SpringBeans"));
        assertEquals("lizl-java-data-analyzer-http-18080-1",
                ThreadNames.of("http", 18080, 1));
        assertEquals("lizl-java-data-analyzer-interceptor-writer",
                ThreadNames.of("interceptor-writer"));
    }

    @Test
    @DisplayName("空白、控制字符与标点折叠成单个连字符，不留首尾连字符")
    void shouldSanitizeIdentifiers() {
        assertEquals("lizl-java-data-analyzer-analysis-my-container",
                ThreadNames.of("analysis", "  my  container\n"));
        assertEquals("lizl-java-data-analyzer-analysis-a-b",
                ThreadNames.of("analysis", "a\t/\nb"));
    }

    @Test
    @DisplayName("null 与净化后为空的标识直接跳过，不留多余连字符")
    void shouldSkipBlankIdentifiers() {
        assertEquals("lizl-java-data-analyzer-analysis", ThreadNames.of("analysis", null, "   "));
    }

    @Test
    @DisplayName("超长标识截断到 40 个字符，且不留尾部连字符")
    void shouldTruncateLongIdentifier() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longName.append('x');
        }

        String name = ThreadNames.of("analysis", longName);
        String prefix = "lizl-java-data-analyzer-analysis-";

        assertEquals(prefix.length() + 40, name.length(), "单个标识最多 40 个字符：" + name);
        assertFalse(name.endsWith("-"));
    }

    @Test
    @DisplayName("中文等字母类字符保留，仍是可读的标识")
    void shouldKeepLetterLikeCharacters() {
        assertEquals("lizl-java-data-analyzer-analysis-订单容器", ThreadNames.of("analysis", "订单容器"));
        assertTrue(ThreadNames.of("analysis", "订单容器").startsWith(ThreadNames.PREFIX));
    }
}
