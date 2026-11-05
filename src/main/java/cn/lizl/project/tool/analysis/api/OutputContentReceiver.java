package cn.lizl.project.tool.analysis.api;

/**
 * 接收渲染结果内容，不经过文件系统。
 *
 * <p>回调在调用 {@code run} 的线程上同步执行（库内不创建线程），异常会原样传回调用方。
 */
public interface OutputContentReceiver {

    /**
     * @param outputContent 产物内容：{@code HTML} / {@code HTML2} 是可折叠交互式页面，
     *                      {@code PUML_HTML} / {@code MERMAID_HTML} 是内嵌浏览器端引擎的极简页面，
     *                      {@code PUML} 是 PlantUML 源码，{@code MERMAID} 是 Mermaid 源码。
     *
     *                      <p>默认情况下前四类都靠<b>同目录 sidecar</b> 以相对路径引用才能正常显示：
     *                      {@code HTML} / {@code HTML2} 需要 {@code tree-style-*.css} 与
     *                      {@code tree-interaction-*.js}（{@code HTML2} 另需 {@code tree-builder-*.js}，
     *                      缺了整页空白）；{@code PUML_HTML} / {@code MERMAID_HTML} 需要对应的引擎 js。
     *                      本回调<b>不会</b>代写这些文件：自行落盘时需一并补齐（可复用
     *                      {@code HtmlTreeAssets} / {@code FrontendAssets}），或改走
     *                      {@link RunningConfigurer#run(OutputFileReceiver)} 自动补齐。
     *                      开启 {@link GeneralConfigurer#setInlineAssets(boolean)} 后内容已是自包含页面，
     *                      无此限制（代价是体积变大）。
     */
    void receive(String outputContent);
}
