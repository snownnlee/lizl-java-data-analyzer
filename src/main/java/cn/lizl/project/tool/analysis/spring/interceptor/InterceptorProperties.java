package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.function.Consumer;
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

    /** 本通道默认产物根目录：全局默认目录下的 interceptor 子目录，避免与 beans 产物混在一起 */
    public static final String DEFAULT_OUTPUT_ROOT_DIR =
            System.getProperty("user.home") + java.io.File.separator
                    + "cn.lizl.project.tool.analysis-result" + java.io.File.separator + "interceptor";

    /** 本通道产物的根目录（其下按日期建子目录） */
    private String outputRootDir = DEFAULT_OUTPUT_ROOT_DIR;

    /**
     * 渲染类型。本通道<b>自己带默认值 HTML</b>，不继承全局 {@code render-type}：
     * 产物扩展名要在此处就确定（写盘器先于渲染拿文件名），留空会导致扩展名与实际内容不符。
     */
    private RenderType renderType = RenderType.HTML;

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

    /**
     * 拦截器通道的单次分析安全默认超时：高频触发、默认在请求线程上同步分析，
     * 某个字段的 {@code toString()} 卡住就会占死线程池，所以未显式配置时也不放开。
     */
    public static final int DEFAULT_ANALYSIS_TIMEOUT_MILLIS = 3000;

    /** 空白字符串视为未配置 */
    private static final Pattern WHITESPACE_ONLY = Pattern.compile("\\s*");

    /**
     * 按本通道配置新建一个 RunningConfigurer：全局配置打底，本通道显式配置的项覆盖。
     *
     * <p>每次分析都要新建：它内部持有待分析对象列表，共享实例会被 {@code clear()} 抹掉别处。
     */
    public RunningConfigurer newRunningConfigurer() {
        RunningConfigurer configurer = JavaDataAnalyzer.runningConfigurer();
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
        checkAndSet(shouldAnalyzeNullValue, configurer::setShouldAnalyzeNullValue);
        checkAndSet(defaultToExpand, configurer::setDefaultToExpand);
        checkAndSet(addToStringField, configurer::setAddToStringField);
        checkAndSet(maskSensitiveValues, configurer::setMaskSensitiveValues);
        checkAndSet(enableCollectionSimpleView, configurer::setEnableCollectionSimpleView);
        return configurer;
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
