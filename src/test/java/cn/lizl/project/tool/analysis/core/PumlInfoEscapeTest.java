package cn.lizl.project.tool.analysis.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PUML 的 info 文本必须走 Creole 转义：内容里出现 {@code <color:...>} 之类标签时不得改写样式。
 *
 * <p>当前 info 只由库生成（库文案 + 类名，类名不含尖括号），这条是防口径漂移：字段值路径一直转义，
 * 将来若把用户文本放进 info，不转义就会变成样式注入。
 */
class PumlInfoEscapeTest {

    @Test
    @DisplayName("info 折行着色转义 Creole 标签")
    void infoShouldEscapeCreole() {
        String out = BasePumlRender.wrapEscapedColorize(BasePumlRender.COLOR_WARN,
                "警告：<color:red>伪造样式</color>", BasePumlRender.INFO_WRAP_WIDTH);

        assertFalse(out.contains("<color:red>"), "内容里的 Creole 标签未被转义：" + out);
        assertTrue(out.contains("<U+003C>color:red<U+003E>"), "应按 <U+003C>/<U+003E> 转义：" + out);
    }
}
