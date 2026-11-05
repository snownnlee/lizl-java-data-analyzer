package cn.lizl.project.tool.analysis.util;

/**
 * 把产物里对同目录资源的相对引用替换成内联内容，得到单文件自包含页面。
 *
 * <p>只认本库分发的资源与模板固定的标签形态：{@link HtmlTreeAssets} 的带指纹样式/脚本、
 * {@link FrontendAssets} 的前端引擎。其余内容原样返回，因此对纯文本产物是空操作。
 * 代价是把 MB 级引擎文本拼进页面，换掉「同目录资源复用 + 浏览器缓存」，只在内联开启时执行。
 *
 * <p>内联要求资源里不含 {@code </script} / {@code </style}，否则会提前闭合标签；该约束由
 * {@code InlineAssetsTest} 卡住，升级引擎后失败时须先处理资源。
 */
public final class AssetInliner {

    private static final String[] TREE_STYLES = {HtmlTreeAssets.STYLE};

    private static final String[] TREE_SCRIPTS = {HtmlTreeAssets.INTERACTION, HtmlTreeAssets.BUILDER};

    private static final String[] ENGINES = {FrontendAssets.VIZ_GLOBAL, FrontendAssets.PLANTUML, FrontendAssets.MERMAID};

    private AssetInliner() {
    }

    public static String inline(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        String result = html;
        for (String style : TREE_STYLES) {
            result = inlineTreeAsset(result, style, true);
        }
        for (String script : TREE_SCRIPTS) {
            result = inlineTreeAsset(result, script, false);
        }
        for (String engine : ENGINES) {
            result = inlineEngine(result, engine);
        }
        return result;
    }

    private static String inlineTreeAsset(String html, String fileName, boolean css) {
        String reference = reference(HtmlTreeAssets.servedName(fileName), css);
        // 先判存在：引擎内容有 MB 级，没被引用就不必读出来
        if (!html.contains(reference)) {
            return html;
        }
        return html.replace(reference, wrap(HtmlTreeAssets.text(fileName), css));
    }

    private static String inlineEngine(String html, String fileName) {
        String reference = reference(fileName, false);
        if (!html.contains(reference)) {
            return html;
        }
        return html.replace(reference, wrap(FrontendAssets.readAsText(fileName), false));
    }

    private static String reference(String servedName, boolean css) {
        return css
                ? "<link rel=\"stylesheet\" href=\"./" + servedName + "\">"
                : "<script src=\"./" + servedName + "\"></script>";
    }

    private static String wrap(String content, boolean css) {
        return css ? "<style>" + content + "</style>" : "<script>" + content + "</script>";
    }
}
