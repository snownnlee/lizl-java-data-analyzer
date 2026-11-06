package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 请求拦截器通道的配置，前缀 {@code cn.lizl.java-data-analyzer.interceptor}。
 *
 * <p>与 {@code beans.*} 之间没有任何继承或回落关系：字段全量独立定义、独立默认值。
 * 共享的只有进程级的 {@code GlobalConfig}（代码里设的兜底值）。
 */
@Data
@ConfigurationProperties(prefix = InterceptorProperties.PREFIX)
public class InterceptorProperties {

    /** 配置项前缀。必须是编译期常量：注解属性只接受常量表达式。 */
    public static final String PREFIX = "cn.lizl.java-data-analyzer.interceptor";

    /**
     * 是否装配本通道：包装 handler 参数解析器 + 注册控制 Bean + 挂载 {@code /interceptor} 端点。
     *
     * <p><b>只在启动期生效</b>——装配了就无法在运行期卸载，运行期开关是 {@link #active}。
     * 装配与否由启动期条件直接读 Environment（{@code enable}/{@code enabled} 都认），本字段只用于绑定与文档。
     */
    private boolean enable = false;

    /**
     * 通道装好之后，是否真正捕获请求入参。<b>默认关闭</b>，需显式配 true。
     * 运行期可经 {@code /interceptor} 端点热改，改动仅内存态。
     */
    private boolean active = false;

    /** Ant 风格路径白名单；空或未配置表示拦截所有请求。 */
    private List<String> includePaths;

    /** Ant 风格路径黑名单，优先于白名单。 */
    private List<String> excludePaths;

    /** 异步写盘队列容量：满载短等待后降级为调用线程同步落盘，不丢产物。默认 64。 */
    private Integer outputQueueCapacity;

    /** 每个日期目录各自保留的产物上限，{@code <=0} 表示不限制。默认 3000。 */
    private Integer outputRetention;

    /** 本通道默认产物根目录：全局默认目录下的 interceptor 子目录，避免与 beans 产物混在一起。 */
    public static final String DEFAULT_OUTPUT_ROOT_DIR =
            System.getProperty("user.home") + java.io.File.separator
                    + "cn.lizl.project.tool.analysis-result" + java.io.File.separator + "interceptor";

    /** 本通道产物的根目录（其下按日期建子目录）。 */
    private String outputRootDir = DEFAULT_OUTPUT_ROOT_DIR;

    /**
     * 渲染类型，默认值 HTML2。
     */
    private RenderType renderType = RenderType.HTML2;

    /**
     * 分析是否放独立线程执行，默认 {@code false}（请求线程同步分析）。
     *
     * <p>开启后请求线程立即返回，代价是分析时请求上下文已结束：request scope、
     * {@code RequestContextHolder} 不可用，request-scoped 代理的字段可能读不到。
     * 分析线程固定 1 个，队列满时回落到请求线程同步执行（不丢产物）。
     *
     * <p>只影响 MVC 宿主：WebFlux 阻塞事件循环会拖垮同线程的全部连接，本来就在专用线程分析。
     */
    private boolean asyncAnalysis;

    /**
     * MVC 异步分析队列容量，只作用于 {@code async-analysis=true} 时懒创建的那个线程池（当前为 64）。
     *
     * <p>小于 1 视为非法：回落默认值并打一条 WARN。容量在启动期固定——线程池创建后无法调整，
     * 改这项只能重启应用。
     */
    private Integer analysisQueueCapacity;

    /**
     * WebFlux 分析队列容量（当前为 256）。该通道始终在专用线程分析，因此本项与 {@code async-analysis} 无关。
     *
     * <p>小于 1 视为非法：回落默认值并打一条 WARN。同样在启动期固定。
     */
    private Integer reactiveAnalysisQueueCapacity;

    /** 产物是否内联样式/脚本资源：单文件自包含但体积更大；默认 false，按同目录 sidecar 分发 */
    private Boolean inlineAssets;

    private String[] commonExcludePackages;

    private String[] commonIncludePackages;

    private Boolean analyzeStaticField;

    private Integer valueDisplayLengthLimit;

    private Integer collectionsAnalyzingCount;

    private Integer maxDepth;

    private Integer maxNodeCount;

    /** 单次分析允许耗时（毫秒）；未配置时用安全默认 3000ms（{@code 0} 表示不限制）。 */
    private Integer analysisTimeoutMillis;

    private AnalysisMode analysisMode;

    private Boolean shouldAnalyzeNullValue;

    private Boolean defaultToExpand;

    private Boolean addToStringField;

    private Boolean maskSensitiveValues;

    private Boolean enableCollectionSimpleView;

    /** 是否允许消费一次性容器（Iterator/Enumeration）；默认 false，分析不改变对象状态 */
    private Boolean analyzeOneShotCollections;

    /**
     * 拦截器通道的单次分析安全默认超时：高频触发、默认在请求线程上同步分析，用上限封住单个请求的
     * 分析总量。注意这是协作式超时，仅在新建节点边界检查；某个字段的 {@code toString()} 卡住时
     * 仍会占住线程直到调用返回，超时无法中断它。
     */
    public static final int DEFAULT_ANALYSIS_TIMEOUT_MILLIS = 3000;

    /** 空白字符串视为未配置 */
    private static final Pattern WHITESPACE_ONLY = Pattern.compile("\\s*");

    private static final Logger LOG = Logger.getLogger(InterceptorProperties.class.getName());

    /**
     * 解析 MVC 异步分析队列的实际容量：未配置用默认值；非法值（{@code < 1}）回落默认值并打 WARN。
     */
    public int resolveAnalysisQueueCapacity() {
        return resolveQueueCapacity("analysis-queue-capacity", analysisQueueCapacity,
                InterceptorAnalysisExecutor.DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * 解析 WebFlux 分析队列的实际容量：未配置用默认值；非法值（{@code < 1}）回落默认值并打 WARN。
     */
    public int resolveReactiveAnalysisQueueCapacity() {
        return resolveQueueCapacity("reactive-analysis-queue-capacity", reactiveAnalysisQueueCapacity,
                ReactiveArgumentCaptureInstaller.DEFAULT_QUEUE_CAPACITY);
    }

    private int resolveQueueCapacity(String key, Integer configured, int defaultValue) {
        if (configured == null) {
            return defaultValue;
        }
        if (configured < 1) {
            LOG.warning("interceptor." + key + "=" + configured + " 非法（至少为 1），已回落默认值 "
                    + defaultValue + "；容量在启动期固定，需要调整请改配置后重启");
            return defaultValue;
        }
        return configured;
    }

    /**
     * 把本通道配置重放到 {@code configurer} 上（覆盖式：只改本通道显式配置的项）。
     *
     * <p>调用方负责先把实例重置回全局快照（{@code reset(true)}），否则上一次的通道配置会残留。
     */
    public void applyTo(RunningConfigurer configurer) {
        checkAndSet(outputRootDir, configurer::setOutputRootDir);
        applyPackages(commonExcludePackages, configurer::addCommonExcludePackages);
        applyPackages(commonIncludePackages, configurer::addCommonIncludePackages);
        checkAndSet(analyzeStaticField, configurer::setAnalyzeStaticField);
        checkAndSet(valueDisplayLengthLimit, configurer::setValueDisplayLengthLimit);
        checkAndSet(collectionsAnalyzingCount, configurer::setCollectionsAnalyzingCount);
        checkAndSet(maxDepth, configurer::setAnalyzingMaxDepth);
        checkAndSet(maxNodeCount, configurer::setAnalyzingMaxNodeCount);
        configurer.setAnalyzingTimeoutMillis(analysisTimeoutMillis != null
                ? analysisTimeoutMillis : DEFAULT_ANALYSIS_TIMEOUT_MILLIS);
        checkAndSet(analysisMode, configurer::setAnalysisMode);
        checkAndSet(renderType, configurer::setRenderType);
        checkAndSet(inlineAssets, configurer::setInlineAssets);
        checkAndSet(shouldAnalyzeNullValue, configurer::setShouldAnalyzeNullValue);
        checkAndSet(defaultToExpand, configurer::setDefaultToExpand);
        checkAndSet(addToStringField, configurer::setAddToStringField);
        checkAndSet(maskSensitiveValues, configurer::setMaskSensitiveValues);
        checkAndSet(enableCollectionSimpleView, configurer::setEnableCollectionSimpleView);
        checkAndSet(analyzeOneShotCollections, configurer::setAnalyzeOneShotCollections);
    }

    private <T> void checkAndSet(T value, Consumer<T> setter) {
        if (value == null) {
            return;
        }
        if (value instanceof String && WHITESPACE_ONLY.matcher((String) value).matches()) {
            return;
        }
        setter.accept(value);
    }

    /**
     * 包规则专用：只接受非空且不含空白项的数组，追加合并（去重）。
     *
     * <p>口径与 {@code BeansProperties} 完全一致：空数组按未配置处理，空白项过滤，
     * 否则 {@code startsWith("")} 会命中一切类名。
     */
    private void applyPackages(String[] packages, Consumer<String[]> adder) {
        if (packages == null || packages.length == 0) {
            return;
        }
        java.util.List<String> rules = new java.util.ArrayList<>(packages.length);
        for (String rule : packages) {
            if (rule != null && !WHITESPACE_ONLY.matcher(rule).matches()) {
                rules.add(rule);
            }
        }
        if (rules.isEmpty()) {
            return;
        }
        adder.accept(rules.toArray(new String[0]));
    }
}
