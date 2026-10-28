package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.RenderType;

import java.io.File;

class GlobalConfig {

    static final String[] DEFAULT_COMMON_EXCLUDE_PACKAGES = {"com.sun.", "java.", "javax.", "jakarta.", "jdk.", "sun."};

    static volatile String globalOutputRootDir = System.getProperty("user.home") + File.separator + "cn.lizl.project.tool.analysis-result";

    static volatile boolean throwExceptionWhenStringClassNotFound;

    static volatile ClassLoader classLoader = GlobalConfig.class.getClassLoader();

    static volatile String[] commonIncludePackages = new String[0];

    static volatile String[] commonExcludePackages = DEFAULT_COMMON_EXCLUDE_PACKAGES;

    static volatile boolean analyzeStaticField = false;

    static volatile int valueDisplayLengthLimit = 800;

    static volatile int collectionsAnalyzingCount = 10;

    static volatile boolean shouldAnalyzeNullValue = true;

    static volatile int maxDepth = 7;

    /**
     * 单次分析最多新建多少个节点；{@code <= 0} 表示不限制。深度与集合取项数都管不住横向展开，
     * 这是唯一能封住产物总量的开关。
     */
    static volatile int maxNodeCount = 2000;

    /**
     * 单次分析允许耗时（毫秒）；{@code <= 0} 表示不限制。默认关闭：耗时随机器性能变化，作默认值会让
     * 同一份代码在不同机器上产出不同结果。它防的是「某个字段取值本身就卡住」这类场景。
     */
    static volatile int analysisTimeoutMillis = 0;

    static volatile boolean defaultToExpand = false;

    static volatile AnalysisMode analysisMode = AnalysisMode.AUTO_BY_COMPUTE;

    static volatile boolean enableCollectionSimpleView = true;

    static volatile boolean addToStringField = false;

    /**
     * 敏感字段（password/token/secret 等，见 {@code CommonUtil.isSensitiveName}）的值是否脱敏渲染为
     * {@code ***}；默认 true。只挡值不挡结构——分析与展开照常，仅报告不落明文。
     */
    static volatile boolean maskSensitiveValues = true;

    static volatile RenderType renderType = RenderType.HTML;
}
