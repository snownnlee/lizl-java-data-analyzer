package cn.lizl.project.tool.analysis.api;

public enum RenderType {

    /**
     * 可折叠的交互式 HTML 树。
     */
    HTML,

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
