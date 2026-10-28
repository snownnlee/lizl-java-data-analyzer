package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.GlobalConfigurer;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.util.CommonUtil;

import java.util.LinkedList;
import java.util.List;

public final class DefaultGlobalConfigurer implements GlobalConfigurer {

    private final List<Runnable> actions = new LinkedList<>();

    @Override
    public GlobalConfigurer setAnalyzeStaticField(final boolean value) {
        return addAction(() -> GlobalConfig.analyzeStaticField = value);
    }

    @Override
    public GlobalConfigurer setValueDisplayLengthLimit(final int lengthLimit) {
        return addAction(() -> GlobalConfig.valueDisplayLengthLimit = lengthLimit);
    }

    @Override
    public GlobalConfigurer setCollectionsAnalyzingCount(final int count) {
        return addAction(() -> GlobalConfig.collectionsAnalyzingCount = count);
    }

    @Override
    public GlobalConfigurer setShouldAnalyzeNullValue(final boolean value) {
        return addAction(() -> GlobalConfig.shouldAnalyzeNullValue = value);
    }

    @Override
    public GlobalConfigurer setAnalyzingMaxDepth(final int maxDepth) {
        return addAction(() -> GlobalConfig.maxDepth = maxDepth);
    }

    @Override
    public GlobalConfigurer setAnalyzingMaxNodeCount(final int maxNodeCount) {
        return addAction(() -> GlobalConfig.maxNodeCount = maxNodeCount);
    }

    @Override
    public GlobalConfigurer setAnalyzingTimeoutMillis(final int timeoutMillis) {
        return addAction(() -> GlobalConfig.analysisTimeoutMillis = timeoutMillis);
    }

    @Override
    public GlobalConfigurer setDefaultToExpand(final boolean defaultToExpand) {
        return addAction(() -> GlobalConfig.defaultToExpand = defaultToExpand);
    }

    @Override
    public GlobalConfigurer setAnalysisMode(final AnalysisMode analysisMode) {
        if (analysisMode != null) {
            return addAction(() -> GlobalConfig.analysisMode = analysisMode);
        }
        return this;
    }

    @Override
    public GlobalConfigurer setEnableCollectionSimpleView(final boolean value) {
        return addAction(() -> GlobalConfig.enableCollectionSimpleView = value);
    }

    @Override
    public GlobalConfigurer setAddToStringField(final boolean value) {
        return addAction(() -> GlobalConfig.addToStringField = value);
    }

    @Override
    public GlobalConfigurer setMaskSensitiveValues(final boolean value) {
        return addAction(() -> GlobalConfig.maskSensitiveValues = value);
    }

    @Override
    public GlobalConfigurer setRenderType(final RenderType renderType) {
        if (renderType != null) {
            return addAction(() -> GlobalConfig.renderType = renderType);
        }
        return this;
    }

    @Override
    public GlobalConfigurer setCommonIncludePackages(final String... commonIncludePackages) {
        if (commonIncludePackages != null) {
            return addAction(() -> GlobalConfig.commonIncludePackages = commonIncludePackages);
        }
        return this;
    }

    @Override
    public GlobalConfigurer addCommonIncludePackages(final String... commonIncludePackages) {
        return addAction(() -> {
            if (commonIncludePackages != null && commonIncludePackages.length > 0) {
                GlobalConfig.commonIncludePackages = CommonUtil.combine(GlobalConfig.commonIncludePackages, commonIncludePackages);
            }
        });
    }

    @Override
    public GlobalConfigurer setCommonExcludePackages(final String... commonExcludePackages) {
        if (commonExcludePackages != null) {
            return addAction(() -> GlobalConfig.commonExcludePackages = commonExcludePackages);
        }
        return this;
    }

    @Override
    public GlobalConfigurer addCommonExcludePackages(final String... commonExcludePackages) {
        return addAction(() -> {
            if (commonExcludePackages != null && commonExcludePackages.length > 0) {
                GlobalConfig.commonExcludePackages = CommonUtil.combine(GlobalConfig.commonExcludePackages, commonExcludePackages);
            }
        });
    }

    @Override
    public GlobalConfigurer setCommonExcludePackagesToDefault() {
        return setCommonExcludePackages(GlobalConfig.DEFAULT_COMMON_EXCLUDE_PACKAGES);
    }

    @Override
    public GlobalConfigurer setGlobalOutputRootDir(final String outputRootDir) {
        if (outputRootDir != null) {
            return addAction(() -> GlobalConfig.globalOutputRootDir = outputRootDir);
        }
        return this;
    }

    @Override
    public GlobalConfigurer setThrowExceptionWhenStringClassNotFound(final boolean value) {
        return addAction(() -> GlobalConfig.throwExceptionWhenStringClassNotFound = value);
    }

    @Override
    public GlobalConfigurer setClassLoader(final ClassLoader classLoader) {
        return addAction(() -> {
            if (classLoader != null) {
                GlobalConfig.classLoader = classLoader;
            }
        });
    }

    private GlobalConfigurer addAction(final Runnable action) {
        actions.add(action);
        return this;
    }

    @Override
    public void apply() {
        if (!actions.isEmpty()) {
            actions.forEach(Runnable::run);
            actions.clear();
        }
    }
}
