package cn.lizl.project.tool.analysis.api;

/**
 * 产物形态：同一份分析结果可落成可折叠 HTML、PlantUML 或 Mermaid。
 *
 * <p>{@code HTML} / {@code HTML2} 的扩展名都是 {@code .html}（区别在 DOM 由服务端还是浏览器拼）；
 * 带 {@code _HTML} 后缀的静态图内嵌浏览器端引擎，默认需与同目录 sidecar 一起分发，
 * 开启 {@link GeneralConfigurer#setInlineAssets(boolean)} 后产物自包含。
 * 全局默认 {@link #HTML}（拦截器通道自带自己的默认值，见其配置说明）。
 */
public enum RenderType {

    /**
     * 可折叠的交互式 HTML 树，完整 DOM 由服务端拼好写进产物。
     */
    HTML,

    /**
     * 同一套交互式 HTML 树的「数据骨架」形态：产物里只有 JSON 数据，DOM 由浏览器端脚本构建。
     *
     * <p>产物扩展名与 {@link #HTML} 相同（{@code .html}），页面结构与交互完全一致，区别只在体积——
     * 标签不再重复落盘，大对象的产物通常能小一大截。
     */
    HTML2,

    /**
     * PlantUML 文本，需外部渲染器才能查看。
     */
    PUML,

    /**
     * PlantUML 文本嵌进极简 HTML，由浏览器端官方 JS 引擎渲染成 SVG；引擎以 sidecar 形式放产物
     * 同目录，断网与 {@code file://} 双击都能出图。产物需与 {@code plantuml-*.js} / {@code viz-global-*.js} 同目录。
     */
    PUML_HTML,

    /**
     * Mermaid classDiagram 文本，GitHub / Typora / Obsidian 等环境原生出图。
     */
    MERMAID,

    /**
     * Mermaid 图文嵌进 HTML，浏览器端渲染；引擎以 sidecar 形式放产物同目录，
     * 断网与 {@code file://} 双击都能出图。产物需与 {@code mermaid-*.js} 同目录。
     */
    MERMAID_HTML
}
