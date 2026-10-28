package cn.lizl.project.tool.analysis.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 值格式化（{@link CommonUtil#getStringFrom(Object, int)}）对成环结构的处理。
 *
 * <p>原先是一路无脑递归：自引用的 map / 集合（{@code map.put("self", map)}）会把
 * {@code {self={self={self=...} 一直展开下去，直到 {@code StackOverflowError}。
 * {@code HashMap.toString()} 自己会输出 {@code (this Map)}，但我们没有走 {@code toString}。</p>
 */
class CommonUtilCycleTest {

    private static final int LIMIT = 800;

    @Test
    @DisplayName("自引用的 map 输出占位符，不再无限递归")
    void shouldMarkSelfReferencingMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("self", map);

        assertEquals("{self=(循环引用)}", CommonUtil.getStringFrom(map, LIMIT));
    }

    @Test
    @DisplayName("自引用的 list 输出占位符")
    void shouldMarkSelfReferencingList() {
        List<Object> list = new ArrayList<>();
        list.add("first");
        list.add(list);

        assertEquals("[first, (循环引用)]", CommonUtil.getStringFrom(list, LIMIT));
    }

    @Test
    @DisplayName("并列引用同一个容器不算成环，两处都要完整展开")
    void shouldNotMarkSharedButAcyclicReference() {
        List<Object> inner = new ArrayList<>();
        inner.add("x");
        List<Object> outer = new ArrayList<>();
        outer.add(inner);
        outer.add(inner);

        assertEquals("[[x], [x]]", CommonUtil.getStringFrom(outer, LIMIT));
    }
}
