package cn.lizl.project.tool.analysis.api;

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
