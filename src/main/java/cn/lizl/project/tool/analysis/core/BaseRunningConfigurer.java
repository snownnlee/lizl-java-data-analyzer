package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import cn.lizl.project.tool.analysis.util.CommonUtil;

abstract class BaseRunningConfigurer extends RunningConfigCapable implements RunningConfigurer {

    private volatile RenderHolder renderHolder;

    BaseRunningConfigurer() {
        super(new RunningConfig());
    }

    @Override
    public RunningConfigurer setCommonIncludePackages(final String... commonIncludePackages) {
        if (commonIncludePackages != null) {
            runningConfig.commonIncludePackages = commonIncludePackages;
        }
        return this;
    }

    @Override
    public RunningConfigurer addCommonIncludePackages(final String... commonIncludePackages) {
        if (commonIncludePackages != null && commonIncludePackages.length > 0) {
            runningConfig.commonIncludePackages = CommonUtil.combine(runningConfig.commonIncludePackages, commonIncludePackages);
        }
        return this;
    }

    @Override
    public RunningConfigurer setCommonExcludePackages(final String... commonExcludePackages) {
        if (commonExcludePackages != null) {
            runningConfig.commonExcludePackages = commonExcludePackages;
        }
        return this;
    }

    @Override
    public RunningConfigurer addCommonExcludePackages(final String... commonExcludePackages) {
        if (commonExcludePackages != null && commonExcludePackages.length > 0) {
            runningConfig.commonExcludePackages = CommonUtil.combine(runningConfig.commonExcludePackages, commonExcludePackages);
        }
        return this;
    }

    @Override
    public RunningConfigurer setOutputRootDir(final String outputRootDir) {
        if (outputRootDir != null) {
            runningConfig.outputRootDir = outputRootDir;
        }
        return this;
    }

    @Override
    public RunningConfigurer setAnalyzeStaticField(final boolean value) {
        runningConfig.analyzeStaticField = value;
        return this;
    }

    @Override
    public RunningConfigurer setValueDisplayLengthLimit(final int lengthLimit) {
        runningConfig.valueDisplayLengthLimit = lengthLimit;
        return this;
    }

    @Override
    public RunningConfigurer setCollectionsAnalyzingCount(final int count) {
        runningConfig.collectionsAnalyzingCount = count;
        return this;
    }

    @Override
    public RunningConfigurer setShouldAnalyzeNullValue(final boolean value) {
        runningConfig.shouldAnalyzeNullValue = value;
        return this;
    }

    @Override
    public RunningConfigurer setAnalyzingMaxDepth(final int maxDepth) {
        runningConfig.maxDepth = maxDepth;
        return this;
    }

    @Override
    public RunningConfigurer setAnalyzingMaxNodeCount(final int maxNodeCount) {
        runningConfig.maxNodeCount = maxNodeCount;
        return this;
    }

    @Override
    public RunningConfigurer setAnalyzingTimeoutMillis(final int timeoutMillis) {
        runningConfig.analysisTimeoutMillis = timeoutMillis;
        return this;
    }

    @Override
    public RunningConfigurer setDefaultToExpand(final boolean defaultToExpand) {
        runningConfig.defaultToExpand = defaultToExpand;
        return this;
    }

    @Override
    public RunningConfigurer setAnalysisMode(final AnalysisMode analysisMode) {
        if (analysisMode != null) {
            runningConfig.analysisMode = analysisMode;
        }
        return this;
    }

    @Override
    public RunningConfigurer setEnableCollectionSimpleView(final boolean value) {
        runningConfig.enableCollectionSimpleView = value;
        return this;
    }

    @Override
    public RunningConfigurer setAddToStringField(final boolean value) {
        runningConfig.addToStringField = value;
        return this;
    }

    @Override
    public RunningConfigurer setMaskSensitiveValues(final boolean value) {
        runningConfig.maskSensitiveValues = value;
        return this;
    }

    @Override
    public int analyzingTimeoutMillis() {
        return runningConfig.analysisTimeoutMillis;
    }

    @Override
    public RunningConfigurer setRenderType(final RenderType renderType) {
        if (renderType != null) {
            runningConfig.renderType = renderType;
        }
        return this;
    }

    @Override
    public RunningConfigurer setOutputFileName(final String... outputFileName) {
        if (outputFileName != null) {
            runningConfig.outputFileName = outputFileName;
        }
        return this;
    }

    /**
     * 取本次渲染要用的渲染器。
     *
     * <p>缓存的类型必须与当前配置一致：只按「缓存为空」判断会沿用第一次建的渲染器，把 PUML 产物写成
     * .html。{@link RenderHolder} 把类型与渲染器绑成一个不可变对象，避免二者出现中间态。
     */
    protected IRender getRender() {
        RenderType renderType = runningConfig.renderType;
        RenderHolder holder = renderHolder;
        if (holder != null && holder.renderType == renderType) {
            return holder.render;
        }
        synchronized (this) {
            holder = renderHolder;
            if (holder == null || holder.renderType != renderType) {
                holder = new RenderHolder(renderType, createRender(renderType));
                renderHolder = holder;
            }
            return holder.render;
        }
    }

    private IRender createRender(RenderType renderType) {
        if (renderType == RenderType.PUML) {
            return new PumlRender(runningConfig);
        }
        if (renderType == RenderType.PUML_HTML) {
            return new PumlHtmlRender(runningConfig);
        }
        if (renderType == RenderType.MERMAID) {
            return new MermaidRender(runningConfig);
        }
        if (renderType == RenderType.MERMAID_HTML) {
            return new MermaidHtmlRender(runningConfig);
        }
        return new HtmlRender(runningConfig);
    }

    private static final class RenderHolder {

        private final RenderType renderType;

        private final IRender render;

        private RenderHolder(RenderType renderType, IRender render) {
            this.renderType = renderType;
            this.render = render;
        }
    }
}
