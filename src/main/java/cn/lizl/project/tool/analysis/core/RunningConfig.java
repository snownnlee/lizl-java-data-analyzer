package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.RenderType;

class RunningConfig {

    private static final String[] EMPTY_ARRAY = new String[0];

    String[] commonIncludePackages = EMPTY_ARRAY;

    String[] commonExcludePackages = EMPTY_ARRAY;

    boolean analyzeStaticField = GlobalConfig.analyzeStaticField;

    int valueDisplayLengthLimit = GlobalConfig.valueDisplayLengthLimit;

    int collectionsAnalyzingCount = GlobalConfig.collectionsAnalyzingCount;

    boolean shouldAnalyzeNullValue = GlobalConfig.shouldAnalyzeNullValue;

    int maxDepth = GlobalConfig.maxDepth;

    int maxNodeCount = GlobalConfig.maxNodeCount;

    int analysisTimeoutMillis = GlobalConfig.analysisTimeoutMillis;

    boolean defaultToExpand = GlobalConfig.defaultToExpand;

    AnalysisMode analysisMode = GlobalConfig.analysisMode;

    boolean enableCollectionSimpleView = GlobalConfig.enableCollectionSimpleView;

    boolean addToStringField = GlobalConfig.addToStringField;

    boolean maskSensitiveValues = GlobalConfig.maskSensitiveValues;

    RenderType renderType = GlobalConfig.renderType;

    String outputRootDir = GlobalConfig.globalOutputRootDir;

    String[] outputFileName;

    void reset() {
        commonIncludePackages = EMPTY_ARRAY;
        commonExcludePackages = EMPTY_ARRAY;
        analyzeStaticField = GlobalConfig.analyzeStaticField;
        valueDisplayLengthLimit = GlobalConfig.valueDisplayLengthLimit;
        collectionsAnalyzingCount = GlobalConfig.collectionsAnalyzingCount;
        shouldAnalyzeNullValue = GlobalConfig.shouldAnalyzeNullValue;
        maxDepth = GlobalConfig.maxDepth;
        maxNodeCount = GlobalConfig.maxNodeCount;
        analysisTimeoutMillis = GlobalConfig.analysisTimeoutMillis;
        defaultToExpand = GlobalConfig.defaultToExpand;
        analysisMode = GlobalConfig.analysisMode;
        enableCollectionSimpleView = GlobalConfig.enableCollectionSimpleView;
        addToStringField = GlobalConfig.addToStringField;
        maskSensitiveValues = GlobalConfig.maskSensitiveValues;
        renderType = GlobalConfig.renderType;

        outputRootDir = GlobalConfig.globalOutputRootDir;
        outputFileName = null;
    }
}
