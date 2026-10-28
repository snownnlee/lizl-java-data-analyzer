package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 单个请求的分析执行器：在请求线程上同步做分析建模与渲染（方案 A 的既定口径，用 maxNodeCount /
 * timeout 配置兜底延迟），产物内容交给异步写盘器，文件名按请求自持、不碰共享配置。
 *
 * <p>每次分析新建 RunningConfigurer：共享实例的 add/clear 在并发请求下会串目标，属于必选动作而非浪费。
 */
final class InterceptorRequestAnalyzer {

    /** 这些前缀命中的参数是框架设施对象，不进分析目标 */
    private static final String[] FRAMEWORK_TYPE_PREFIXES = {
            "javax.servlet.", "jakarta.servlet.",
            "org.springframework.ui.", "org.springframework.validation.",
            "org.springframework.http.", "org.springframework.web.",
            "org.springframework.core.io.",
            // Spring Security 的 Authentication/SecurityContext 里是现成的凭证与权限数据，不进分析目标
            "org.springframework.security.",
            "java.io.", "java.nio.", "java.security.Principal", "java.util.Locale",
    };

    private static final int URI_PART_MAX_LENGTH = 96;

    /** 产物文件名里的 URI 非法字符（含空白）；每请求一次，预编译 */
    private static final Pattern UNSAFE_URI_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\s]");

    /** 产物目录/文件名时间戳：DateTimeFormatter 线程安全，免去每请求 new SimpleDateFormat */
    private static final DateTimeFormatter DATE_DIR_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Logger log = Logger.getLogger(getClass().getName());

    private final InterceptorProperties properties;

    private final InterceptorControl control;

    private final AtomicLong sequence = new AtomicLong();

    InterceptorRequestAnalyzer(InterceptorProperties properties, InterceptorControl control) {
        this.properties = properties;
        this.control = control;
    }

    /**
     * @param handler 简要描述（类名#方法名），只用于摘要日志
     * @param args    参数下标 → 已解析的参数值，来自捕获包装器
     */
    void analyze(String handler, String requestUri, Map<Integer, Object> args) {
        try {
            Map<Integer, Object> sortedArgs = new TreeMap<>(args);
            RunningConfigurer configurer = buildConfigurer();
            boolean hasTarget = false;
            for (Object arg : sortedArgs.values()) {
                if (arg != null && !isFrameworkType(arg.getClass())) {
                    configurer.addAnalyzingObject(arg);
                    hasTarget = true;
                }
            }
            if (!hasTarget) {
                return;
            }
            long start = System.currentTimeMillis();
            String rootDir = resolveRootDir();
            String fileName = buildFileName(requestUri);
            configurer.run((OutputContentReceiver) content -> {
                String summary = requestUri + " (" + handler + ", " + (System.currentTimeMillis() - start) + "ms)";
                control.submit(new InterceptorOutputWriter.Entry(rootDir, fileName, content, summary));
            });
        } catch (Throwable throwable) {
            log.log(Level.WARNING, "请求分析失败：" + requestUri + " (" + handler + ")", throwable);
        }
    }

    /**
     * 按本通道配置构建 RunningConfigurer：全局配置打底，然后应用 {@code interceptor.*} 的全部配置项。
     *
     * <p>不再引用任何其它通道的配置——本方法的结果只取决于 {@code InterceptorProperties} 与全局默认。
     * 包规则在 {@link InterceptorProperties#newRunningConfigurer()} 里已按「非空 → 追加合并」处理。
     */
    /** 包私有供测试读取配置结果；生产路径只有 analyze 使用 */
    RunningConfigurer buildConfigurer() {
        RunningConfigurer configurer = properties.newRunningConfigurer();
        // 渲染类型以控制器的运行时值为准（端点可热切），它初始值就是 interceptor.render-type
        RenderType runtimeRenderType = control.getRenderType();
        if (runtimeRenderType != null) {
            configurer.setRenderType(runtimeRenderType);
        }
        return configurer;
    }

    boolean isFrameworkType(Class<?> type) {
        String name = type.getName();
        for (String prefix : FRAMEWORK_TYPE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 本通道产物根目录：只读 {@code interceptor.output-root-dir}（自带默认值，不回落其它通道）。写盘器与控制台共用 */
    static String resolveRootDir(InterceptorProperties properties) {
        String configured = properties.getOutputRootDir();
        if (configured == null || configured.trim().isEmpty()) {
            return InterceptorProperties.DEFAULT_OUTPUT_ROOT_DIR;
        }
        return configured;
    }

    private String resolveRootDir() {
        return resolveRootDir(properties);
    }

    /** 扩展名跟随控制器的运行时渲染类型（配置链 + 端点热切）；产物按日期建子目录 */
    private String buildFileName(String requestUri) {
        RenderType renderType = control.getRenderType();
        String extension;
        switch (renderType) {
            case PUML:
                extension = ".puml";
                break;
            case PUML_HTML:
                extension = ".puml.html";
                break;
            case MERMAID:
                extension = ".mmd";
                break;
            case MERMAID_HTML:
                extension = ".mmd.html";
                break;
            default:
                extension = ".html";
                break;
        }
        LocalDateTime now = LocalDateTime.now();
        String dateDir = DATE_DIR_FORMATTER.format(now);
        String timestamp = TIMESTAMP_FORMATTER.format(now);
        String uriPart = UNSAFE_URI_CHARS.matcher(requestUri).replaceAll("_");
        if (uriPart.length() > URI_PART_MAX_LENGTH) {
            uriPart = uriPart.substring(0, URI_PART_MAX_LENGTH);
        }
        return dateDir + "/" + timestamp + "-" + sequence.incrementAndGet() + "_" + uriPart + extension;
    }
}
