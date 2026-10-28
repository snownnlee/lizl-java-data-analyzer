package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.util.FrontendAssets;
import cn.lizl.project.tool.analysis.util.HtmlTemplates;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Mermaid 转 HTML：图文放 {@code pre.mermaid}，引擎以 sidecar 形式放产物同目录
 * （不内联、不引 CDN），双击 {@code file://} 与断网都能渲染。
 */
class MermaidHtmlRender extends BaseRender {

    private static final String FILE_EXTENSION = ".mmd.html";

    private static final String SHELL_TEMPLATE = "mermaid-shell.html";

    private final MermaidRender mermaidRender;

    MermaidHtmlRender(RunningConfig runningConfig) {
        super(runningConfig, FILE_EXTENSION);
        mermaidRender = new MermaidRender(runningConfig);
    }

    @Override
    public String render(ClassModel[] classModels) {
        String diagram = mermaidRender.render(classModels);
        return HtmlTemplates.fill(HtmlTemplates.load(SHELL_TEMPLATE),
                new String[]{"${favicon}", "${title}", "${mermaidFile}", "${diagram}"},
                new String[]{PumlHtmlRender.FAVICON_DATA_URI,
                        HtmlTemplates.escapeHtml(buildTitle(classModels)),
                        FrontendAssets.MERMAID, HtmlTemplates.escapeHtml(diagram)});
    }

    private static String buildTitle(ClassModel[] classModels) {
        return Stream.of(classModels)
                .map(ClassModel::getClassSimpleName)
                .collect(Collectors.joining("、", "【", "】"));
    }
}
