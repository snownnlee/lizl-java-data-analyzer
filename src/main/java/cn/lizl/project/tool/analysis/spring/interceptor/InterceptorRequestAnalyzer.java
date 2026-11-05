package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
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
 * 单个请求的分析执行器：默认在请求线程上同步建模与渲染（用 maxNodeCount / timeout 配置兜底延迟），
 * 开启 {@code interceptor.async-analysis} 后改为投递到独立线程；产物内容一律交给异步写盘器，
 * 文件名按请求自持、不碰共享配置。
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

    /**
     * 每个分析线程复用同一个 configurer：省掉每次请求的实例、渲染器与包规则数组重建。
     *
     * <p>只能按线程持有，不能跨线程共享（{@link RunningConfigurer} 内部攒目标，并发会串）。
     * 复用时每次都要 {@code reset(true)} 回到全局快照并清空目标，否则上一次的通道配置与目标会残留。
     */
    private final ThreadLocal<RunningConfigurer> configurerHolder = new ThreadLocal<>();

    InterceptorRequestAnalyzer(InterceptorProperties properties, InterceptorControl control) {
        this.properties = properties;
        this.control = control;
    }

    /**
     * @param handler    简要描述（类名#方法名），只用于摘要日志
     * @param requestUri 请求路径：决定产物文件名摘要与日志内容；路径过滤已由调用方按
     *                   include-paths / exclude-paths 判完，本方法不再判
     * @param args       参数下标 → 已解析的参数值，来自捕获包装器
     */
    void analyze(String handler, String requestUri, Map<Integer, Object> args) {
        // 先做快照：异步执行时调用方的 Map 可能已被复用或清空
        Map<Integer, Object> sortedArgs = new TreeMap<>(args);
        if (control.isAsyncAnalysis()) {
            control.submitAnalysis(() -> doAnalyze(handler, requestUri, sortedArgs));
            return;
        }
        doAnalyze(handler, requestUri, sortedArgs);
    }

    /**
     * 在调用线程上直接分析，不看 {@code async-analysis}：WebFlux 通道已在自己的线程池里跑，
     * 再套一层异步只会多一次排队。
     */
    void analyzeNow(String handler, String requestUri, Map<Integer, Object> args) {
        doAnalyze(handler, requestUri, new TreeMap<>(args));
    }

    private void doAnalyze(String handler, String requestUri, Map<Integer, Object> sortedArgs) {
        RunningConfigurer configurer = null;
        try {
            configurer = buildConfigurer();
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
        } finally {
            // 线程池线程会长期存活：不清掉就把上一次的目标对象图钉在内存里
            if (configurer != null) {
                configurer.clear();
            }
        }
    }

    /**
     * 取本线程的 RunningConfigurer：全局配置打底 + {@code interceptor.*} 覆盖 + 运行期渲染类型。
     *
     * <p>复用同一实例，所以每次先 {@code reset(true)}（回全局快照并清空目标）再重放通道配置——
     * 运行期改配置仍然立即生效，省掉的只是实例、渲染器与包规则数组的重复创建。
     */
    RunningConfigurer buildConfigurer() {
        RunningConfigurer configurer = configurerHolder.get();
        if (configurer == null) {
            configurer = JavaDataAnalyzer.runningConfigurer();
            configurerHolder.set(configurer);
        }
        configurer.reset(true);
        properties.applyTo(configurer);
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
            // HTML 与 HTML2 的产物都是 .html：区别在页面内部是完整 DOM 还是 JSON 骨架，扩展名不区分
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
