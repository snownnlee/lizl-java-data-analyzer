package cn.lizl.project.tool.analysis.api;

/**
 * 分析模式：声明分析目标是按「类型」还是按「实例值」展开。
 *
 * <p>「按类型」只看声明类型并继续展开其结构，「按实例」才读取运行时值。
 * 由 {@code setAnalysisMode} 配置，全局默认 {@link #AUTO_BY_COMPUTE}。
 */
public enum AnalysisMode {
    /** 只按类型分析。 */
    ONLY_CLASS,

    /** 只按实例分析。 */
    ONLY_INSTANCE,

    /** 有实例值则按实例分析，否则按类型；null 字段是否分析由 shouldAnalyzeNullValue 决定。 */
    AUTO_BY_CONFIG,

    /** 字段所在类型有实例时只分析实例值，否则只分析类型。 */
    AUTO_BY_COMPUTE
}
