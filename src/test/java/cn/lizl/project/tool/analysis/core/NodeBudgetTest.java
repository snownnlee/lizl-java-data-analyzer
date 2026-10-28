package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分析总量限额用例（对应分析报告 P3-5）。
 *
 * <p>历史缺陷：只有 {@code maxDepth}（纵向多深）与 {@code collectionsAnalyzingCount}
 * （单个集合取几项）两个旋钮，都管不住横向展开——深度 7、每层取 10 项时最坏是 10^7 个节点，
 * 产物能涨到几十 MB 并长时间占线程，且没有任何开关能叫停。</p>
 *
 * <p>断言口径：触顶后产物里必须出现明确的提示文字，且规模确实小于不限额时；
 * 不逐字比对内容（含哈希与时间戳）。</p>
 */
class NodeBudgetTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("节点数触顶后产物出现提示，且规模小于不限额时")
    void shouldStopAndWarnWhenNodeCountExceeded() {
        String capped = render(20, 0);
        String unlimited = render(0, 0);

        assertTrue(capped.contains("已达到最大分析节点数20"), "触顶后没有留下提示，用户无法知道产物被截断");
        assertFalse(unlimited.contains("已达到最大分析节点数"), "限额为 0 时不应出现截断提示");
        assertTrue(capped.length() < unlimited.length(),
                "截断后的产物应小于不限额时，实际为 " + capped.length() + " vs " + unlimited.length());
    }

    @Test
    @DisplayName("默认上限 2000 不干扰常规规模的对象图")
    void shouldNotTruncateNormalGraphWithDefaultLimit() {
        String withDefault = render(2000, 0);
        String unlimited = render(0, 0);

        assertFalse(withDefault.contains("已达到最大分析节点数"), "常规规模的对象图不应被默认上限截断");
        assertTrue(withDefault.length() == unlimited.length(), "默认上限下产物应与不限额时完全一致");
    }

    @Test
    @DisplayName("超过耗时上限后应拒绝继续创建节点（机制层，不依赖机器快慢）")
    void shouldStopByDeadline() throws Exception {
        RunningConfig runningConfig = new RunningConfig();
        // 只验耗时，先把节点数上限关掉，否则可能先被节点数拦下
        runningConfig.maxNodeCount = 0;
        runningConfig.analysisTimeoutMillis = 1;
        ClassModelsManager manager = new ClassModelsManager(runningConfig, Collections.emptyList());

        Thread.sleep(5);

        String reason = manager.requestNodeBudget();
        assertNotNull(reason, "已经过了截止时刻，却仍允许创建节点");
        assertTrue(reason.contains("已超过最大分析耗时1ms"), "拦截原因不对：" + reason);
    }

    @Test
    @DisplayName("耗时为 0（默认）表示不限时")
    void shouldNotLimitWhenTimeoutIsZero() {
        String content = render(0, 0);

        assertFalse(content.contains("已超过最大分析耗时"), "耗时为 0 时不应限时");
    }

    private static String render(int maxNodeCount, int timeoutMillis) {
        RunningConfigurer configurer = JavaDataAnalyzer.runningConfigurer()
                .setAnalyzingMaxDepth(7)
                .setCollectionsAnalyzingCount(10)
                .setAnalyzingMaxNodeCount(maxNodeCount)
                .setAnalyzingTimeoutMillis(timeoutMillis)
                .addAnalyzingObject(wideGraph());

        StringBuilder content = new StringBuilder();
        configurer.run((OutputContentReceiver) content::append);
        return content.toString();
    }

    /** 10 个 Map × 每个 10 个 10 字段对象，实测约 211 个节点 */
    private static List<Object> wideGraph() {
        List<Object> list = new LinkedList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> map = new HashMap<>();
            for (int j = 0; j < 10; j++) {
                map.put("k" + j, new TenFields());
            }
            list.add(map);
        }
        return list;
    }

    @SuppressWarnings("unused")
    static class TenFields {

        private String a = "value-a";

        private String b = "value-b";

        private int c = 1;

        private long d = 2L;

        private double e = 3.0;

        private boolean f = true;

        private byte g = 4;

        private short h = 5;

        private char i = 'x';

        private float j = 6.0f;
    }
}
