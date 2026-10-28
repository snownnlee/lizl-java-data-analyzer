package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 拦截器运行时控制：路径匹配、热更新与计数透传。
 */
class InterceptorControlTest {

    @TempDir
    Path outputRootDir;

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("白名单为空时拦截所有请求，黑名单优先")
    void shouldMatchPathsByRule() {
        InterceptorProperties properties = baseProperties(true);
        properties.setExcludePaths(Collections.singletonList("/actuator/**"));
        InterceptorControl control = new InterceptorControl(properties);

        assertTrue(control.shouldAnalyze("/users/1"));
        assertFalse(control.shouldAnalyze("/actuator/health"), "黑名单应优先");
    }

    @Test
    @DisplayName("白名单非空时只放行命中请求")
    void shouldFilterByIncludePaths() {
        InterceptorProperties properties = baseProperties(true);
        properties.setIncludePaths(Collections.singletonList("/api/**"));
        InterceptorControl control = new InterceptorControl(properties);

        assertTrue(control.shouldAnalyze("/api/orders"));
        assertFalse(control.shouldAnalyze("/web/index"));
    }

    @Test
    @DisplayName("interceptor.active=false 时一律不捕获；装配开关 enable 不参与运行期判定")
    void shouldNotAnalyzeWhenInactive() {
        InterceptorControl control = new InterceptorControl(baseProperties(false));
        assertFalse(control.shouldAnalyze("/users/1"));

        // 装配开关只决定通道装不装，不该反过来影响已经在跑的通道
        InterceptorProperties assembled = baseProperties(true);
        assembled.setEnable(false);
        assertTrue(new InterceptorControl(assembled).shouldAnalyze("/users/1"),
                "运行期只看 active，enable=false 不应让已装配的通道静默失效");
    }

    @Test
    @DisplayName("端点热更新：开关与路径规则即时生效")
    void shouldUpdateStateAtRuntime() {
        InterceptorControl control = new InterceptorControl(baseProperties(false));

        control.updateInterceptorState(true, Collections.singletonList("/api/**"), null);
        assertTrue(control.shouldAnalyze("/api/orders"));
        assertFalse(control.shouldAnalyze("/web/index"));

        control.updateInterceptorState(null, null, Collections.singletonList("/api/internal/**"));
        assertFalse(control.shouldAnalyze("/api/internal/debug"), "热加的黑名单应优先");
        assertTrue(control.shouldAnalyze("/api/orders"));

        Map<String, Object> state = control.updateInterceptorState(false, Collections.emptyList(), Collections.emptyList());
        assertEquals(Boolean.FALSE, state.get("active"));
        assertFalse(control.shouldAnalyze("/api/orders"));
    }

    @Test
    @DisplayName("分析提交后产物落盘，队列满载降级同步落盘，产物一个不丢")
    void shouldSubmitAndCount() throws Exception {
        InterceptorControl control = new InterceptorControl(baseProperties(true));
        // 不用 @TempDir：异步写盘线程的生命周期长于测试方法，TempDir 清理会与残留写入竞态
        java.nio.file.Path outputDir = java.nio.file.Paths.get("target", "test-output",
                "interceptor-control-" + System.nanoTime());
        java.nio.file.Files.createDirectories(outputDir);
        try {
            // 容量 64 且写盘线程消费有限，200 条连续提交必然触发满载降级路径
            int total = 200;
            for (int i = 1; i <= total; i++) {
                assertTrue(control.submit(new InterceptorOutputWriter.Entry(
                                outputDir.toString(), "bulk-" + i + ".html", "content-" + i, "bulk-" + i)),
                        "满载不应拒绝提交（降级同步落盘，不丢产物）");
            }
            assertEquals(total, (int) control.writer().getSubmitted(), "提交计数应等于全部提交数");

            awaitOrFail(() -> control.writer().getWritten() >= total, "全部产物应落盘");
            for (int i = 1; i <= total; i++) {
                assertTrue(new java.io.File(outputDir.toFile(), "bulk-" + i + ".html").isFile(),
                        "产物 bulk-" + i + ".html 不存在");
            }
            assertEquals(0, (int) control.writer().getDropped(),
                    "满载不丢产物，dropped 只在写盘失败时累加");
        } finally {
            control.shutdown();
        }
    }

    @Test
    @DisplayName("保留策略：超出 retention 从最旧开始删磁盘文件，登记同步收缩")
    void shouldEnforceOutputRetention() throws Exception {
        InterceptorProperties properties = baseProperties(true);
        properties.setOutputRetention(2);
        InterceptorControl control = new InterceptorControl(properties);
        java.nio.file.Path outputDir = java.nio.file.Paths.get("target", "test-output",
                "interceptor-retention-" + System.nanoTime());
        java.nio.file.Files.createDirectories(outputDir);
        try {
            for (int i = 1; i <= 3; i++) {
                control.submit(new InterceptorOutputWriter.Entry(outputDir.toString(),
                        "keep-" + i + ".html", "content-" + i, "retention-" + i));
            }
            File old1 = new File(outputDir.toFile(), "keep-1.html");
            File keep2 = new File(outputDir.toFile(), "keep-2.html");
            File keep3 = new File(outputDir.toFile(), "keep-3.html");
            awaitOrFail(() -> control.writer().getWritten() >= 3 && !old1.exists()
                            && keep2.isFile() && keep3.isFile(),
                    "最旧的 keep-1 应被淘汰删除，keep-2/3 保留");
            assertEquals(2, control.interceptorOutputNames().size(), "登记表应同步收缩到保留条数");
        } finally {
            control.shutdown();
        }
    }

    @Test
    @DisplayName("渲染类型热切：初始按配置链解析，端点可改，非法值报错")
    void shouldHotSwitchRenderType() {
        InterceptorProperties properties = baseProperties(false);
        properties.setRenderType(null);
        properties.setRenderType(cn.lizl.project.tool.analysis.api.RenderType.PUML_HTML);
        InterceptorControl control = new InterceptorControl(properties);

        assertEquals(cn.lizl.project.tool.analysis.api.RenderType.PUML_HTML, control.getRenderType(),
                "初始值应回落顶层配置");
        control.updateInterceptorState(null, null, null, "MERMAID");
        assertEquals(cn.lizl.project.tool.analysis.api.RenderType.MERMAID, control.getRenderType());
        try {
            control.updateInterceptorState(null, null, null, "NOT_A_TYPE");
            fail("非法渲染类型应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期路径
        }
    }

    @Test
    @DisplayName("保留策略按目录独立计算：目录 A/B 各自保留，互不挤占")
    void shouldEnforceRetentionPerDirectory() throws Exception {
        InterceptorProperties properties = baseProperties(true);
        properties.setOutputRetention(1);
        InterceptorControl control = new InterceptorControl(properties);
        java.nio.file.Path root = java.nio.file.Paths.get("target", "test-output",
                "interceptor-per-dir-" + System.nanoTime());
        java.nio.file.Path dirA = root.resolve("20261026");
        java.nio.file.Path dirB = root.resolve("20261027");
        java.nio.file.Files.createDirectories(dirA);
        java.nio.file.Files.createDirectories(dirB);
        try {
            control.submit(new InterceptorOutputWriter.Entry(dirA.toString(), "a-1.html", "a1", "a-1"));
            control.submit(new InterceptorOutputWriter.Entry(dirA.toString(), "a-2.html", "a2", "a-2"));
            control.submit(new InterceptorOutputWriter.Entry(dirB.toString(), "b-1.html", "b1", "b-1"));
            control.submit(new InterceptorOutputWriter.Entry(dirB.toString(), "b-2.html", "b2", "b-2"));

            awaitOrFail(() -> control.writer().getWritten() >= 4
                            && !new File(dirA.toFile(), "a-1.html").exists()
                            && new File(dirA.toFile(), "a-2.html").isFile()
                            && !new File(dirB.toFile(), "b-1.html").exists()
                            && new File(dirB.toFile(), "b-2.html").isFile(),
                    "两个目录应各自淘汰最旧、各保留 1 份");
            assertEquals(2, control.interceptorOutputNames().size(), "登记表应为两目录各 1 份");
        } finally {
            control.shutdown();
        }
    }

    @Test
    @DisplayName("重启回填：磁盘已有产物在登记表重建后重新可见，非日期目录不扫")
    void shouldSeedRegistryFromDiskOnStartup() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get("target", "test-output",
                "interceptor-seed-" + System.nanoTime());
        java.nio.file.Path dateDir = root.resolve("20261026");
        java.nio.file.Path beanDir = root.resolve("1792998326424");
        java.nio.file.Files.createDirectories(dateDir);
        java.nio.file.Files.createDirectories(beanDir);
        java.nio.file.Files.write(dateDir.resolve("20261026-120000-000-1__a.html"), "a".getBytes());
        java.nio.file.Files.write(dateDir.resolve("20261026-120001-000-2__b.puml"), "b".getBytes());
        java.nio.file.Files.write(beanDir.resolve("000001-someBean.html"), "z".getBytes());

        InterceptorProperties properties = baseProperties(true);
        properties.setOutputRootDir(root.toString());
        InterceptorControl control = new InterceptorControl(properties);
        try {
            java.util.List<String> names = control.interceptorOutputNames();
            assertEquals(2, names.size(), "只应回填日期目录下的 2 份产物：" + names);
            assertTrue(names.contains("20261026/20261026-120000-000-1__a.html"));
            assertTrue(names.contains("20261026/20261026-120001-000-2__b.puml"));
            assertNotNull(control.getInterceptorOutput("20261026/20261026-120000-000-1__a.html"));
            assertNull(control.getInterceptorOutput("1792998326424/000001-someBean.html"),
                    "非日期目录不应被扫入");
        } finally {
            control.shutdown();
        }
    }

    private InterceptorProperties baseProperties(boolean active) {
        InterceptorProperties properties = new InterceptorProperties();
        properties.setActive(active);
        properties.setOutputQueueCapacity(64);
        return properties;
    }

    static void awaitOrFail(BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("等待超时：" + message);
    }
}
