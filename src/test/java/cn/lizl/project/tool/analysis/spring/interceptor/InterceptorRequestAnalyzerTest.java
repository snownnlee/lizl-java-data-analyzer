package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求分析器：入参过滤、产物落盘、框架类型零产物，以及「配置只来自 interceptor.*」的独立性。
 *
 * <p>不用 {@code @TempDir}：异步写盘线程的生命周期长于测试方法，TempDir 清理会与残留写入竞态，
 * 统一改用 {@code target/test-output} 下的独立目录并显式 shutdown。
 */
class InterceptorRequestAnalyzerTest {

    private java.nio.file.Path outputRootDir;

    @BeforeEach
    void setUp() throws Exception {
        TestSupport.resetGlobalConfig();
        outputRootDir = java.nio.file.Paths.get("target", "test-output",
                "interceptor-analyzer-" + System.nanoTime());
        java.nio.file.Files.createDirectories(outputRootDir);
    }

    @Test
    @DisplayName("业务入参被同步分析并异步落盘，产物落在日期子目录下")
    void shouldAnalyzeBusinessArgsAndWriteFile() {
        InterceptorProperties properties = baseProperties();
        InterceptorControl control = new InterceptorControl(properties);
        try {
            InterceptorRequestAnalyzer analyzer = new InterceptorRequestAnalyzer(properties, control);

            Map<Integer, Object> args = new HashMap<>();
            args.put(0, new Sample());
            args.put(1, "text-arg");

            analyzer.analyze("SampleController#handle", "/sample/handle", args);

            // 等「有内容」而不是「文件出现」：写盘器先建文件再写内容，只看存在会读到 0 字节
            InterceptorControlTest.awaitOrFail(() -> listFiles().length >= 1 && listFiles()[0].length() > 0,
                    "应有产物落盘");
            File[] files = listFiles();
            assertEquals(1, files.length);
            assertTrue(files[0].getName().endsWith(".html"), "默认应产出 html：" + files[0].getName());
            assertTrue(files[0].length() > 0, "产物文件为空");
            assertTrue(files[0].getName().contains("_sample_handle"), "文件名应含 URI 摘要");
            assertEquals(new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date()),
                    files[0].getParentFile().getName(), "产物应位于日期子目录下");
        } finally {
            control.shutdown();
        }
    }

    @Test
    @DisplayName("渲染类型完全由本通道决定：配 PUML 就产 .puml，不读任何其它通道的配置")
    void shouldUseOwnRenderType() {
        InterceptorProperties properties = baseProperties();
        properties.setRenderType(RenderType.PUML);
        InterceptorControl control = new InterceptorControl(properties);
        try {
            InterceptorRequestAnalyzer analyzer = new InterceptorRequestAnalyzer(properties, control);

            Map<Integer, Object> args = new HashMap<>();
            args.put(0, new Sample());
            analyzer.analyze("SampleController#handle", "/sample/own", args);

            // 等「有内容」而不是「文件出现」：写盘器先建文件再写内容，只看存在会读到 0 字节
            InterceptorControlTest.awaitOrFail(() -> listFiles().length >= 1 && listFiles()[0].length() > 0,
                    "应有产物落盘");
            assertTrue(listFiles()[0].getName().endsWith(".puml"),
                    "本通道 render-type=PUML 应产出 puml：" + listFiles()[0].getName());
            assertEquals(outputRootDir.toString(), listFiles()[0].getParentFile().getParent(),
                    "产物应落在本通道自己的 output-root-dir 下");
        } finally {
            control.shutdown();
        }
    }

    @Test
    @DisplayName("框架设施类型与 null 不进分析目标，全部被过滤时不产出")
    void shouldFilterFrameworkTypes() {
        InterceptorProperties properties = baseProperties();
        InterceptorControl control = new InterceptorControl(properties);
        try {
            InterceptorRequestAnalyzer analyzer = new InterceptorRequestAnalyzer(properties, control);

            assertTrue(analyzer.isFrameworkType(org.springframework.ui.Model.class));
            assertTrue(analyzer.isFrameworkType(java.io.InputStream.class));
            assertFalse(analyzer.isFrameworkType(Sample.class));

            Map<Integer, Object> args = new HashMap<>();
            args.put(0, null);
            args.put(1, new org.springframework.ui.ModelMap());

            analyzer.analyze("SampleController#handle", "/sample/only-framework", args);

            // 等一小段时间确认确实没有产物（负向断言用短等待即可）
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertEquals(0, listFiles().length, "全框架入参不应产出文件");
        } finally {
            control.shutdown();
        }
    }

    @Test
    @DisplayName("超时：未配置用本通道安全默认 3000ms，配置了就取配置值（含 0=不限制）")
    void shouldApplyOwnTimeoutOnly() {
        InterceptorProperties properties = baseProperties();
        InterceptorControl control = new InterceptorControl(properties);
        try {
            InterceptorRequestAnalyzer analyzer = new InterceptorRequestAnalyzer(properties, control);

            assertEquals(InterceptorProperties.DEFAULT_ANALYSIS_TIMEOUT_MILLIS,
                    analyzer.buildConfigurer().analyzingTimeoutMillis(),
                    "未配置时应使用本通道的安全默认 3000ms");

            properties.setAnalysisTimeoutMillis(0);
            assertEquals(0, analyzer.buildConfigurer().analyzingTimeoutMillis(),
                    "显式 0 表示不限制，应原样透传");

            properties.setAnalysisTimeoutMillis(1500);
            assertEquals(1500, analyzer.buildConfigurer().analyzingTimeoutMillis());
        } finally {
            control.shutdown();
        }
    }

    @Test
    @DisplayName("包规则只来自本通道：去重后进运行级数组，不与其它通道合并")
    void shouldApplyOwnPackageRulesOnly() throws Exception {
        InterceptorProperties properties = baseProperties();
        properties.setCommonExcludePackages(new String[]{"com.acme.internal.", "java.", "java."});
        InterceptorControl control = new InterceptorControl(properties);
        try {
            InterceptorRequestAnalyzer analyzer = new InterceptorRequestAnalyzer(properties, control);
            String[] rules = TestSupport.commonExcludePackagesOf(analyzer.buildConfigurer());

            assertEquals(2, rules.length, "重复项应去重：" + Arrays.toString(rules));
            assertTrue(Arrays.asList(rules).contains("com.acme.internal."));
            assertTrue(Arrays.asList(rules).contains("java."));
        } finally {
            control.shutdown();
        }
    }

    @Test
    @DisplayName("包规则里的空白项被过滤，不会变成「匹配一切类名」的通配")
    void shouldDropBlankPackageRules() throws Exception {
        InterceptorProperties properties = baseProperties();
        properties.setCommonExcludePackages(new String[]{"", "   ", "com.acme."});
        InterceptorControl control = new InterceptorControl(properties);
        try {
            InterceptorRequestAnalyzer analyzer = new InterceptorRequestAnalyzer(properties, control);
            String[] rules = TestSupport.commonExcludePackagesOf(analyzer.buildConfigurer());

            assertEquals(1, rules.length, "空白项应被过滤：" + Arrays.toString(rules));
            assertFalse(Arrays.asList(rules).contains(""), "空串条目绝不能进规则表");
        } finally {
            control.shutdown();
        }
    }

    @Test
    @DisplayName("空数组按未配置处理：不向运行级数组追加任何规则")
    void shouldTreatEmptyArrayAsUnset() throws Exception {
        InterceptorProperties properties = baseProperties();
        InterceptorControl control = new InterceptorControl(properties);
        try {
            InterceptorRequestAnalyzer analyzer = new InterceptorRequestAnalyzer(properties, control);

            // 未配置 → 运行级数组为空；全局默认排除包在 GlobalConfig 层另行生效，不体现在这里
            assertEquals(0, TestSupport.commonExcludePackagesOf(analyzer.buildConfigurer()).length);

            properties.setCommonExcludePackages(new String[0]);
            assertEquals(0, TestSupport.commonExcludePackagesOf(analyzer.buildConfigurer()).length,
                    "空数组等价于未配置，不应变成「清空全部规则」以外的任何语义");
        } finally {
            control.shutdown();
        }
    }

    private InterceptorProperties baseProperties() {
        InterceptorProperties properties = new InterceptorProperties();
        properties.setActive(true);   // 本类只测分析+落盘路径，不走捕获开关
        properties.setOutputQueueCapacity(8);
        properties.setRenderType(RenderType.HTML);
        properties.setOutputRootDir(outputRootDir.toString());
        return properties;
    }

    /** 产物在根目录的日期子目录下，递归收集 */
    private File[] listFiles() {
        File root = outputRootDir.toFile();
        if (!root.exists()) {
            return new File[0];
        }
        java.util.List<File> files = new java.util.ArrayList<>();
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    File[] inner = child.listFiles();
                    if (inner != null) {
                        files.addAll(java.util.Arrays.asList(inner));
                    }
                } else {
                    files.add(child);
                }
            }
        }
        return files.toArray(new File[0]);
    }

    @SuppressWarnings("unused")
    static class Sample {

        private String name = "sample";

        private int count = 1;
    }
}
