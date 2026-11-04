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
     * 同一份代码在不同机器上产出不同结果。
     *
     * <p>这是协作式超时：仅在新建节点边界检查，用于封住单次分析的总量；若耗时卡在字段取值、
     * 容器迭代或某个 {@code toString()} 内部，检查点不会执行、无法中断（Java 无安全强杀线程的手段）。
     */
    static volatile int analysisTimeoutMillis = 0;

    static volatile boolean defaultToExpand = false;

    static volatile AnalysisMode analysisMode = AnalysisMode.AUTO_BY_COMPUTE;

    static volatile boolean enableCollectionSimpleView = true;

    /**
     * 是否允许消费一次性容器（{@code Iterator} / {@code Enumeration}）。分析会真实调用
     * {@code next()} / {@code nextElement()}，默认 {@code false} 保证分析是只读操作：
     * 报告只显示占位提示，不改变对象状态；显式开启后一次性容器会被耗尽。
     */
    static volatile boolean analyzeOneShotCollections = false;

    static volatile boolean addToStringField = false;

    /**
     * 敏感字段（password/token/secret 等，见 {@code CommonUtil.isSensitiveName}）的值是否脱敏渲染为
     * {@code ***}；默认 true。只挡值不挡结构——分析与展开照常，仅报告不落明文。
     */
    static volatile boolean maskSensitiveValues = true;

    static volatile RenderType renderType = RenderType.HTML;
}
