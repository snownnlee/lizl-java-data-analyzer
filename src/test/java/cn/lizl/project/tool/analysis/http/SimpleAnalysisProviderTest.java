package cn.lizl.project.tool.analysis.http;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleAnalysisProviderTest {

    @TempDir
    Path tempDir;

    private SimpleAnalysisProvider provider;

    @BeforeEach
    void setUp() {
        // 全局配置是静态字段，用例之间必须显式复位，否则上一个用例的排除包/深度会渗进来
        TestSupport.resetGlobalConfig();
        RunningConfigurer configurer = JavaDataAnalyzer.runningConfigurer().setOutputRootDir(tempDir.toString());
        provider = new SimpleAnalysisProvider(configurer);
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    @DisplayName("add 只登记不分析：名字立刻可见，但产物目录仍是空的")
    void shouldNotAnalyzeOnAdd() {
        provider.add("order", new Sample());

        assertEquals(Collections.singletonList("order"), provider.getBeanNames(false));
        File[] files = tempDir.toFile().listFiles();
        assertNotNull(files);
        assertEquals(0, files.length, "懒分析：add 阶段不应产生任何产物");
    }

    @Test
    @DisplayName("首次取值时才分析并落盘，返回的 HTML 包含被分析的类名")
    void shouldAnalyzeOnFirstAccess() throws IOException {
        Sample sample = new Sample();
        sample.name = "demo";
        provider.add("order", sample);

        File file = provider.getAnalyzedResult("order", false);

        assertNotNull(file);
        assertTrue(file.isFile());
        assertTrue(file.getName().endsWith(".html"), "渲染类型为默认 HTML，后缀应为 .html：" + file.getName());
        assertTrue(file.length() > 0);
        String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(html.contains("Sample"), "产物应包含被分析类名");
    }

    @Test
    @DisplayName("不指定名字时按类简单名自动生成，重名依次追加 #2、#3")
    void shouldGenerateNameFromClass() {
        provider.add(new Sample()).add(new Sample()).add("custom", new Other());

        assertEquals(Arrays.asList("Sample", "Sample#2", "custom"), provider.getBeanNames(false));
    }

    @Test
    @DisplayName("同名重复登记复用原文件名前缀，产物被覆盖而不是不断堆文件")
    void shouldReuseFileNameWhenNameRepeated() {
        provider.add("order", new Sample());
        File first = provider.getAnalyzedResult("order", false);
        assertNotNull(first);

        provider.add("order", new Other());
        File second = provider.getAnalyzedResult("order", false);

        assertNotNull(second);
        assertEquals(first.getName(), second.getName());
        File[] files = tempDir.toFile().listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
    }

    @Test
    @DisplayName("remove 与 clear 只摘掉登记，已生成的产物保留在磁盘上")
    void shouldRemoveRegistrationWithoutDeletingOutput() {
        provider.add("a", new Sample()).add("b", new Other());
        File file = provider.getAnalyzedResult("a", false);
        assertNotNull(file);

        assertTrue(provider.remove("a"));
        assertFalse(provider.remove("a"), "重复移除应返回 false");
        assertNull(provider.getAnalyzedResult("a", false));
        assertEquals(Collections.singletonList("b"), provider.getBeanNames(false));
        assertTrue(file.isFile(), "摘掉登记不应删除产物文件");

        provider.clear();
        assertTrue(provider.getBeanNames(false).isEmpty());
    }

    @Test
    @DisplayName("未登记的名字返回 null，由 HTTP 层转成 404")
    void shouldReturnNullForUnknownName() {
        assertNull(provider.getAnalyzedResult("nope", false));
    }

    @Test
    @DisplayName("登记 Class 时按类结构分析，不需要实例")
    void shouldAnalyzeClassWithoutInstance() {
        provider.add("sampleClass", Sample.class);

        File file = provider.getAnalyzedResult("sampleClass", false);

        assertNotNull(file);
        assertTrue(file.isFile());
    }

    @Test
    @DisplayName("rebuild=true 强制重新分析，被篡改的产物会被覆盖回真实内容")
    void shouldForceReanalyzeOnRebuild() throws IOException {
        provider.add("order", new Sample());
        File file = provider.getAnalyzedResult("order", false);
        assertNotNull(file);

        Files.write(file.toPath(), "STALE_MARKER".getBytes(StandardCharsets.UTF_8));

        File rebuilt = provider.getAnalyzedResult("order", true);

        assertNotNull(rebuilt);
        String html = new String(Files.readAllBytes(rebuilt.toPath()), StandardCharsets.UTF_8);
        assertFalse(html.contains("STALE_MARKER"), "rebuild 应重新分析并覆盖旧产物");
        assertTrue(html.contains("Sample"));
    }

    @Test
    @DisplayName("rebuild=true 的批量入口返回全部名字，并在后台重算后仍能正常取到产物")
    void shouldReturnAllNamesOnBatchRebuild() {
        provider.add("a", new Sample()).add("b", new Other());

        assertEquals(Arrays.asList("a", "b"), provider.getBeanNames(true));

        // 后台重算与随后的同步取结果共用同一把锁，因此这里不需要 sleep 也能拿到确定结果
        assertNotNull(provider.getAnalyzedResult("a", false));
        assertNotNull(provider.getAnalyzedResult("b", false));
    }

    @SuppressWarnings("unused")
    static class Sample {

        private String name = "demo";

        private int count = 3;
    }

    @SuppressWarnings("unused")
    static class Other {

        private boolean enabled = true;
    }
}
