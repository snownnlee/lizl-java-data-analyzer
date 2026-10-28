package cn.lizl.project.tool.analysis.api;

/**
 * 接收渲染结果内容，不经过文件系统。
 *
 * <p>回调在分析线程上同步执行，异常会原样传回调用方。
 */
public interface OutputContentReceiver {

    /**
     * @param outputContent {@code HTML} / {@code PUML_HTML} / {@code MERMAID_HTML} 是完整 HTML 文档，
     *                      {@code PUML} 是 PlantUML 源码，{@code MERMAID} 是 Mermaid 源码。
     *                      注意 {@code PUML_HTML} / {@code MERMAID_HTML} 以相对路径引用同目录的浏览器端
     *                      引擎 js（sidecar），本回调<b>不会</b>代写引擎文件：自行落盘时需把引擎放同目录
     *                      才能双击出图（可复用 {@code FrontendAssets}，或改走文件输出路径自动补齐）。
     */
    void receive(String outputContent);
}
