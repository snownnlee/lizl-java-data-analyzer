package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.util.FrontendAssets;
import cn.lizl.project.tool.analysis.util.HtmlTemplates;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mermaid 转 HTML：图文放 {@code pre.mermaid}，引擎默认以 sidecar 放产物同目录（不引 CDN），
 * 开启 {@code inlineAssets} 时内联进产物；两种形态都能双击 {@code file://}、断网渲染。
 */
class MermaidHtmlRender extends BaseRender {

    private static final String FILE_EXTENSION = ".mmd.html";

    private static final String SHELL_TEMPLATE = "mermaid-shell.html";

    private static final String[] PLACEHOLDERS = {"${favicon}", "${title}", "${mermaidTag}", "${mermaidName}",
            "${diagram}"};

    private final MermaidRender mermaidRender;

    MermaidHtmlRender(RunningConfig runningConfig) {
        super(runningConfig, FILE_EXTENSION);
        mermaidRender = new MermaidRender(runningConfig);
    }

    @Override
    public String render(ClassModel[] classModels) {
        String diagram = mermaidRender.render(classModels);
        return HtmlTemplates.fill(HtmlTemplates.load(SHELL_TEMPLATE),
                PLACEHOLDERS,
                new String[]{PumlHtmlRender.FAVICON_DATA_URI,
                        HtmlTemplates.escapeHtml(buildTitle(classModels)),
                        engineScriptTag(FrontendAssets.MERMAID),
                        // 引擎缺失时的提示文案仍写文件名，内联模式下同样可读
                        FrontendAssets.MERMAID,
                        HtmlTemplates.escapeHtml(diagram)});
    }

    private static String buildTitle(ClassModel[] classModels) {
        return Stream.of(classModels)
                .map(ClassModel::getClassSimpleName)
                .collect(Collectors.joining("、", "【", "】"));
    }
}
