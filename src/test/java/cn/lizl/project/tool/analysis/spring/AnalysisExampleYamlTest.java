package cn.lizl.project.tool.analysis.spring;

import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.spring.beans.BeansProperties;
import cn.lizl.project.tool.analysis.spring.interceptor.InterceptorProperties;
import cn.lizl.project.tool.analysis.spring.server.ServerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 示例配置 {@code src/main/resources/analysis/spring/application-analysis.yaml} 的守护用例。
 *
 * <p>它同时是文档与可复制的模板，一旦键名写错、类型不符或缩进层级错位，用户照抄就会静默失效
 * （Spring 对未知键不报错）。这里用与运行期完全相同的加载链路（YamlPropertySourceLoader + Binder）
 * 解析一遍，并逐个绑定三块配置。
 */
class AnalysisExampleYamlTest {

    private final Binder binder = loadBinder();

    @Test
    @DisplayName("示例 yaml 可解析，三块配置各自绑定成功")
    void shouldBindEachBlock() {
        ServerProperties server = bind(ServerProperties.PREFIX, ServerProperties.class);
        assertTrue(server.isEnable());
        // 示例里的端口是给人照着改的，不锁具体值；只要求「显式配置且合法」。
        // 默认 18080 由 HttpAnalysisServer.DEFAULT_PORT 保证，见 HttpAnalysisServerTest#shouldDefaultPort。
        assertTrue(server.getPort() != null && server.getPort() > 0,
                "server.port 应为正整数，实际：" + server.getPort());
        assertEquals("127.0.0.1", server.getBindAddress());
        assertEquals("/lizl-java-data-analyzer", server.getPathPrefix());
        assertEquals("admin", server.getAuthUser());

        BeansProperties beans = bind(BeansProperties.PREFIX, BeansProperties.class);
        assertTrue(beans.isEnable());
        assertTrue(beans.isEagerToAnalyze());
        assertTrue(beans.isLazy());
        assertEquals("./analysis-output/beans", beans.getOutputRootDir());
        assertEquals(RenderType.HTML, beans.getRenderType());
        assertEquals(Integer.valueOf(7), beans.getMaxDepth());
        assertEquals(Integer.valueOf(0), beans.getAnalysisTimeoutMillis());
        assertEquals(AnalysisMode.AUTO_BY_COMPUTE, beans.getAnalysisMode());
        // beans 段只列「追加」的规则：java./javax./jakarta./jdk./sun. 由全局默认排除负责，示例里不重复写
        assertTrue(beans.getCommonExcludePackages().length >= 5,
                "beans 段应给出若干追加的排除前缀，实际：" + beans.getCommonExcludePackages().length);
        assertTrue(Arrays.asList(beans.getCommonExcludePackages()).contains("org.springframework."),
                "应包含框架设施前缀 org.springframework.");

        InterceptorProperties interceptor = bind(InterceptorProperties.PREFIX, InterceptorProperties.class);
        assertTrue(interceptor.isEnable(), "interceptor.enable");
        assertTrue(interceptor.isActive(), "interceptor.active");
        assertEquals(0, interceptor.getExcludePaths().size());
        assertEquals(Integer.valueOf(5), interceptor.getMaxDepth());
        assertEquals(Integer.valueOf(64), interceptor.getOutputQueueCapacity());
        assertEquals(Integer.valueOf(3000), interceptor.getOutputRetention());
        assertEquals("./analysis-output/interceptor", interceptor.getOutputRootDir());
        // interceptor 段不继承 beans 的包规则，因此自带完整一套；与 beans 段有重复前缀是设计使然
        assertTrue(interceptor.getCommonExcludePackages().length >= 5,
                "interceptor 段应自带完整的排除清单，实际：" + interceptor.getCommonExcludePackages().length);
        assertTrue(Arrays.asList(interceptor.getCommonExcludePackages()).contains("org.springframework."),
                "interceptor 段应包含框架设施前缀 org.springframework.");
    }

    @Test
    @DisplayName("两块通道配置互不影响：改一个不会动另一个")
    void shouldNotCrossInfluence() {
        BeansProperties beans = bind(BeansProperties.PREFIX, BeansProperties.class);
        InterceptorProperties interceptor = bind(InterceptorProperties.PREFIX, InterceptorProperties.class);

        assertEquals(RenderType.HTML, beans.getRenderType());
        assertEquals(RenderType.HTML, interceptor.getRenderType());
        assertEquals(Integer.valueOf(7), beans.getMaxDepth());
        assertEquals(Integer.valueOf(5), interceptor.getMaxDepth());
        assertFalse(beans.getMaxDepth().equals(interceptor.getMaxDepth()), "两段各自取值，不共享");
        assertEquals("./analysis-output/beans", beans.getOutputRootDir());
        assertEquals("./analysis-output/interceptor", interceptor.getOutputRootDir());
    }

    @Test
    @DisplayName("旧的单块键已经失效：顶层不再有 enabled / 分析项")
    void shouldIgnoreLegacyTopLevelKeys() {
        // 旧前缀（cn.lizl.java-data-analyzer.config.*）不绑定到任何属性类：绑定结果应为空
        assertNull(bindOrNull("cn.lizl.java-data-analyzer.config", BeansProperties.class),
                "旧 config.* 前缀不应再绑定成功");
    }

    @Test
    @DisplayName("示例 yaml 覆盖三块配置的全部可绑定字段，且每个配置项都带注释")
    void shouldCoverAndCommentEveryProperty() throws Exception {
        // 走 classpath 与 loadBinder() 同源：断言用的字节与绑定用的字节来自同一处，
        // 避免「相对路径能读到、但 classpath 里没有」（如资源被过滤）时用例假通过
        String text = readExampleYamlText();
        String[] lines = text.split("\n", -1);

        // 1) 注释覆盖：叶子项（key: value）与列表项（key: 后接 "- "）都必须在同一行带注释
        List<String> uncommented = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            if (!trimmed.matches("[a-z][a-z0-9-]*:.*")) {
                continue;
            }
            boolean leaf = trimmed.matches("[a-z][a-z0-9-]*:\\s+\\S.*");
            boolean listParent = !leaf && i + 1 < lines.length && lines[i + 1].trim().startsWith("-");
            if (!leaf && !listParent) {
                continue;   // 纯容器键（cn / lizl / java-data-analyzer / server / beans / interceptor）
            }
            if (!trimmed.contains("#")) {
                uncommented.add(trimmed);
            }
        }
        assertTrue(uncommented.isEmpty(), "以下配置项缺少同行注释：" + uncommented);

        // 2) 字段覆盖：属性类里每一个可写字段（有 setter，非 static/final）都必须在示例里出现
        List<String> missing = new ArrayList<>();
        for (Class<?> type : new Class<?>[]{ServerProperties.class, BeansProperties.class,
                InterceptorProperties.class}) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                    continue;   // PREFIX / 常量 / 内部缓存字段不是配置项
                }
                String key = kebabCase(field.getName());
                if (!Pattern.compile("(?m)^\\s+" + Pattern.quote(key) + ":\\s").matcher(text).find()) {
                    missing.add(type.getSimpleName() + "#" + key);
                }
            }
        }
        assertTrue(missing.isEmpty(), "示例 yaml 缺少这些配置项：" + missing);
    }

    /** 读示例 yaml 原文（含注释）；注释覆盖检查需要原始文本而不是绑定后的属性 */
    private static String readExampleYamlText() throws Exception {
        try (java.io.InputStream in = AnalysisExampleYamlTest.class.getClassLoader()
                .getResourceAsStream("analysis/spring/application-analysis.yaml")) {
            if (in == null) {
                throw new IllegalStateException("classpath 上找不到 analysis/spring/application-analysis.yaml");
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** maxDepth → max-depth（Spring Boot 的 kebab 化口径） */
    private static String kebabCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    private Binder loadBinder() {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application-analysis", new ClassPathResource("analysis/spring/application-analysis.yaml"));
            assertFalse(sources.isEmpty(), "示例 yaml 应能被加载");
            return new Binder(ConfigurationPropertySources.from(sources));
        } catch (Exception e) {
            throw new IllegalStateException("加载示例 yaml 失败", e);
        }
    }

    private <T> T bind(String prefix, Class<T> type) {
        return binder.bind(prefix, Bindable.of(type)).orElseGet(() -> {
            throw new IllegalStateException("示例 yaml 缺少或无法绑定：" + prefix);
        });
    }

    private <T> T bindOrNull(String prefix, Class<T> type) {
        return binder.bind(prefix, Bindable.of(type)).orElse(null);
    }
}
