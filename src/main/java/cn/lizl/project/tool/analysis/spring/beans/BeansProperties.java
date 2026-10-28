package cn.lizl.project.tool.analysis.spring.beans;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * beans 通道的配置，前缀 {@code cn.lizl.java-data-analyzer.beans}。
 *
 * <p>本类只服务于 bean 采集通道，与 {@code interceptor.*} 之间没有任何继承或回落关系：
 * 两边各自定义全部字段、各自解析默认值。共享的只有进程级的 {@code GlobalConfig}（代码里设的兜底值）。
 */
@Data
@ConfigurationProperties(prefix = BeansProperties.PREFIX)
public class BeansProperties {

    /** 配置项前缀。必须是编译期常量：注解属性只接受常量表达式。 */
    public static final String PREFIX = "cn.lizl.java-data-analyzer.beans";

    /** 是否启用 bean 采集通道（是否注册采集处理器）。默认关闭，需显式配 true。 */
    private boolean enable = false;

    /** true：每个 Bean 初始化后立即登记；false：全部单例就绪后统一登记。 */
    private boolean eagerToAnalyze = true;

    /** true：只登记、首次访问才分析；false：登记后立即异步分析。 */
    private boolean lazy = true;

    /** 按 Bean 名称精确忽略。 */
    private Set<String> ignoreBeanNames;

    /** 按 Bean 名称正则忽略（{@code String.matches} 全匹配）。 */
    private String ignoreBeanNamePattern;

    /** 按类、父类、父接口的类名包前缀忽略。 */
    private String[] ignoreBeanPackages;

    /** 本通道产物的根目录；未配置时用 {@code GlobalConfig.globalOutputRootDir}。 */
    private String outputRootDir;

    private String[] commonExcludePackages;

    private String[] commonIncludePackages;

    private Boolean analyzeStaticField;

    private Integer valueDisplayLengthLimit;

    private Integer collectionsAnalyzingCount;

    /** 最大分析深度。 */
    private Integer maxDepth;

    /** 单次分析最多新建节点数；{@code <= 0} 不限制。 */
    private Integer maxNodeCount;

    /** 单次分析允许耗时（毫秒）；{@code <= 0} 不限制。 */
    private Integer analysisTimeoutMillis;

    /** 分析模式；未配置时沿用全局配置（不同于旧版：不再强制 ONLY_INSTANCE）。 */
    private AnalysisMode analysisMode;

    /** 渲染类型；未配置时沿用全局配置。 */
    private RenderType renderType;

    private Boolean shouldAnalyzeNullValue;

    private Boolean defaultToExpand;

    private Boolean addToStringField;

    private Boolean maskSensitiveValues;

    private Boolean enableCollectionSimpleView;

    /** 空白字符串视为未配置 */
    private static final Pattern WHITESPACE_ONLY = Pattern.compile("\\s*");

    /**
     * 按本通道配置新建一个 RunningConfigurer：全局配置打底，本通道显式配置的项覆盖。
     *
     * <p>每次分析都要新建：它内部持有待分析对象列表，共享实例会被 {@code clear()} 抹掉别的任务。
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
        checkAndSet(analysisTimeoutMillis, configurer::setAnalyzingTimeoutMillis);
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
     * <p>空数组无法与「有意清空」区分（Binder 对 {@code key:} 与 {@code key: []} 绑出同一个空数组），
     * 一律按未配置处理；空白项必须过滤，否则 {@code startsWith("")} 会命中一切类名。
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

    /** {@code ignore-bean-name-pattern} 的编译缓存：每个 bean 注册都要匹配一次，启动期可达数千次 */
    private final Map<String, Pattern> ignorePatternCache = new ConcurrentHashMap<>();

    public boolean shouldIgnore(Object bean, String beanName) {
        if (bean == null || beanName == null || beanName.isEmpty()) {
            return true;
        }
        if (ignoreBeanNames != null && ignoreBeanNames.contains(beanName)) {
            return true;
        }
        if (ignoreBeanNamePattern != null && compiledIgnorePattern().matcher(beanName).matches()) {
            return true;
        }
        if (ignoreBeanPackages != null && ignoreBeanPackages.length > 0) {
            return shouldIgnore(bean.getClass());
        }
        return false;
    }

    private Pattern compiledIgnorePattern() {
        return ignorePatternCache.computeIfAbsent(ignoreBeanNamePattern, Pattern::compile);
    }

    private boolean shouldIgnore(Class<?> beanClass) {
        if (beanClass != null) {
            String beanClassName = beanClass.getName();
            for (String ignoreBeanPackage : ignoreBeanPackages) {
                if (beanClassName.startsWith(ignoreBeanPackage)) {
                    return true;
                }
            }
            for (Class<?> anInterface : beanClass.getInterfaces()) {
                if (shouldIgnore(anInterface)) {
                    return true;
                }
            }
            return shouldIgnore(beanClass.getSuperclass());
        }
        return false;
    }
}
