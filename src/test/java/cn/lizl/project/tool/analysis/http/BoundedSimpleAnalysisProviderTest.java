package cn.lizl.project.tool.analysis.http;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedSimpleAnalysisProviderTest {

    @TempDir
    Path tempDir;

    private BoundedSimpleAnalysisProvider provider;

    @BeforeEach
    void setUp() {
        // 全局配置是静态字段，用例之间必须显式复位，否则上一个用例的排除包/深度会渗进来
        TestSupport.resetGlobalConfig();
        provider = newProvider(2);
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    private BoundedSimpleAnalysisProvider newProvider(int capacity) {
        return new BoundedSimpleAnalysisProvider("manual", capacity,
                JavaDataAnalyzer.runningConfigurer().setOutputRootDir(tempDir.toString()));
    }

    @Test
    @DisplayName("超限淘汰最早登记的：容量 2 时登记 3 个只留后两个")
    void shouldEvictOldestWhenExceeded() {
        provider.add("a", new Sample()).add("b", new Sample()).add("c", new Sample());

        assertEquals(Arrays.asList("b", "c"), provider.getBeanNames(false));
    }

    @Test
    @DisplayName("重复登记同名不触发淘汰，也不改变它在列表里的位置")
    void shouldKeepOrderWhenNameRepeated() {
        provider.add("a", new Sample()).add("b", new Sample()).add("a", new Sample());

        assertEquals(Arrays.asList("a", "b"), provider.getBeanNames(false));
        assertNotNull(provider.getAnalyzedResult("a", false));
    }

    @Test
    @DisplayName("容量为 0 或负数直接拒绝")
    void shouldRejectNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> newProvider(0));
        assertThrows(IllegalArgumentException.class, () -> newProvider(-1));
    }

    @Test
    @DisplayName("上限内不淘汰：严格小于容量时全部保留")
    void shouldKeepAllUnderCapacity() {
        provider.add("a", new Sample()).add("b", new Sample());

        assertEquals(Arrays.asList("a", "b"), provider.getBeanNames(false));
    }

    @Test
    @DisplayName("淘汰只摘登记：产物文件留在磁盘，再登记同名会重新分析")
    void shouldKeepOutputFileAfterEviction() {
        provider.add("a", new Sample());
        File file = provider.getAnalyzedResult("a", false);
        assertNotNull(file);

        provider.add("b", new Sample()).add("c", new Sample());

        assertNull(provider.getAnalyzedResult("a", false), "被淘汰的名字应查不到结果");
        assertTrue(file.isFile(), "摘掉登记不应删除产物文件");
        assertEquals(Arrays.asList("b", "c"), provider.getBeanNames(false));

        provider.add("a", new Sample());
        assertEquals(Arrays.asList("c", "a"), provider.getBeanNames(false), "重新登记按最新顺序排");
        assertNotNull(provider.getAnalyzedResult("a", false));
    }

    @Test
    @DisplayName("自动命名的 add(Object) 同样受上限约束")
    void shouldBoundAutomaticallyNamedObjects() {
        BoundedSimpleAnalysisProvider single = newProvider(1);
        try {
            single.add(new Sample()).add(new Sample());

            assertEquals(Collections.singletonList("Sample#2"), single.getBeanNames(false));
        } finally {
            single.close();
        }
    }

    @SuppressWarnings("unused")
    static class Sample {

        private String name = "demo";
    }
}
