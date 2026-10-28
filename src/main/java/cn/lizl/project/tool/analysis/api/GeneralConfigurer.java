package cn.lizl.project.tool.analysis.api;

/**
 * 全局配置与单次分析配置共用的设置项。
 *
 * <p>setter 均为链式调用，传 {@code null} 时忽略该次设置；数组型参数（包名、输出文件名）传空数组
 * 为<b>显式覆盖</b>——即清空本层该项（全局默认排除不受影响）。{@link GlobalConfigurer} 的 setter
 * 需调 {@link GlobalConfigurer#apply()} 才生效，{@link RunningConfigurer} 的 setter 立即生效。
 *
 * @param <T> 具体 configurer 类型，用于让链式调用返回自身类型
 */
public interface GeneralConfigurer<T extends GeneralConfigurer<T>> {

    /**
     * 覆盖强制分析的包名前缀，默认空。命中前缀的类一定展开，且优先于排除规则。
     */
    T setCommonIncludePackages(String... commonIncludePackages);

    /**
     * 追加强制分析的包名前缀，按前缀去重。
     */
    T addCommonIncludePackages(String... commonIncludePackages);

    /**
     * 覆盖不展开的包名前缀。
     *
     * <p>判定顺序：本层 include → 本层 exclude → 全局 include → 全局 exclude → 默认展开。全局默认排除
     * {@code com.sun.}、{@code java.}、{@code javax.}、{@code jakarta.}、{@code jdk.}、{@code sun.}。
     */
    T setCommonExcludePackages(String... commonExcludePackages);

    /**
     * 追加不展开的包名前缀，按前缀去重。
     */
    T addCommonExcludePackages(String... commonExcludePackages);

    /**
     * 是否分析静态字段，默认 {@code false}。
     */
    T setAnalyzeStaticField(boolean value);

    /**
     * 字段值的显示长度上限（字符数），默认 {@code 800}。超长值截断后追加 {@code ...}；
     * 嵌套容器的每一层各自受此限，深层结构的总输出可超过该值。
     */
    T setValueDisplayLengthLimit(int lengthLimit);

    /**
     * 单个集合 / Map 最多展开几项，默认 {@code 10}。
     */
    T setCollectionsAnalyzingCount(int count);

    /**
     * 字段值为 {@code null} 时是否仍按类型分析，默认 {@code true}。仅对 {@code AUTO_BY_CONFIG} 生效：
     * 默认的 {@code AUTO_BY_COMPUTE} 没有实例就无法计算，null 字段一律跳过。
     */
    T setShouldAnalyzeNullValue(boolean value);

    /**
     * 最大分析深度，默认 {@code 7}，根节点深度为 1。
     */
    T setAnalyzingMaxDepth(int maxDepth);

    /**
     * 单次分析最多新建的节点数，默认 {@code 2000}；{@code <= 0} 表示不限制。触顶后停止展开新节点，
     * 已建节点照常输出。
     */
    T setAnalyzingMaxNodeCount(int maxNodeCount);

    /**
     * 单次分析的耗时上限（毫秒），默认 {@code 0} 不限时。默认关闭是有意的：耗时随机器性能变化，
     * 作默认值会让同一份代码产出不同结果。
     */
    T setAnalyzingTimeoutMillis(int timeoutMillis);

    /**
     * HTML 中节点是否默认展开，默认 {@code false}。
     */
    T setDefaultToExpand(boolean defaultToExpand);

    /**
     * 分析模式，默认 {@link AnalysisMode#AUTO_BY_COMPUTE}。
     */
    T setAnalysisMode(AnalysisMode analysisMode);

    /**
     * 集合是否使用简单视图，默认 {@code true}：只展示元素值，不展开集合内部结构。
     */
    T setEnableCollectionSimpleView(boolean value);

    /**
     * 是否为每个节点补一个 {@code toString} 字段，默认 {@code false}。每节点会真实调用一次
     * {@code toString()}，目标类该方法有副作用或较慢时保持关闭。
     */
    T setAddToStringField(boolean value);

    /**
     * 敏感字段（password/token/secret 等，按字段名子串匹配）的值是否脱敏渲染为 {@code ***}，
     * 默认 {@code true}。只挡值不挡结构：分析与展开照常，仅报告不落明文。
     */
    T setMaskSensitiveValues(boolean value);

    /**
     * 渲染类型，默认 {@link RenderType#HTML}。同一实例可在两次 {@code run} 之间切换。
     */
    T setRenderType(RenderType renderType);
}
