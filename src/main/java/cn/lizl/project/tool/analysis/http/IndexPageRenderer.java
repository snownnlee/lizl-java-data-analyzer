package cn.lizl.project.tool.analysis.http;

import cn.lizl.project.tool.analysis.util.HtmlTemplates;

import java.util.List;

/**
 * 根路径索引页的渲染：模板是 classpath 上的 {@code index.html}，这里只做数据装配。
 *
 * <p>把 HTML 从 {@link HttpAnalysisServer} 里挪出来，是为了让服务器只保留"生命周期/鉴权/路由"三件事，
 * 页面改动不必再碰 Java 代码。模板只有两个占位符：{@code {{cards}}} 与 {@code {{missingBlock}}}。
 */
final class IndexPageRenderer {

    private static final String TEMPLATE_NAME = "index.html";

    private static final String[] PLACEHOLDERS = {"{{cards}}", "{{missingBlock}}"};

    /**
     * @param orderedHandlers 全部已注册处理器（按注册顺序），用于生成卡片与缺失提示
     * @param available       其中可用的处理器
     * @param pathPrefix      服务器路径前缀
     * @param query           附带查询串（配置了 token 时是 {@code ?token=...}），可为空
     */
    String render(List<HttpServiceHandler> orderedHandlers, List<HttpServiceHandler> available,
                  String pathPrefix, String query) {
        StringBuilder cards = new StringBuilder();
        for (HttpServiceHandler handler : available) {
            HttpServiceHandler.IndexCard card = handler.indexCard();
            if (card == null) {
                continue;
            }
            String entryPath = handler.getEntryPath();
            if (entryPath == null || entryPath.trim().isEmpty()) {
                // 没有入口路径就没有可点击的目标，宁可不出卡片也不要生成死链
                continue;
            }
            cards.append(renderCard(card, pathPrefix + entryPath + query));
        }

        StringBuilder missingItems = new StringBuilder();
        for (HttpServiceHandler handler : orderedHandlers) {
            if (!handler.isAvailable()) {
                missingItems.append("<li>")
                        .append(HtmlTemplates.escapeHtml(handler.unavailableMessage()))
                        .append("</li>");
            }
        }
        String missingBlock = missingItems.length() == 0
                ? ""
                : "<div class=\"missing\"><b>未装配的通道</b><ul>" + missingItems + "</ul></div>";

        return HtmlTemplates.fill(HtmlTemplates.load(TEMPLATE_NAME),
                        PLACEHOLDERS,
                        new String[]{cards.toString(), missingBlock});
    }

    private String renderCard(HttpServiceHandler.IndexCard card, String href) {
        // 新标签页打开：从通道索引跳进具体页面后，原索引页仍留在，方便来回切换两个通道
        return "<a class=\"card\" href=\"" + HtmlTemplates.escapeHtml(href)
                + "\" target=\"_blank\" rel=\"noopener\">"
                + "<span class=\"tag\">" + HtmlTemplates.escapeHtml(card.getTag()) + "</span>"
                + "<div class=\"name\">" + HtmlTemplates.escapeHtml(card.getName()) + "</div>"
                + "<div class=\"desc\">" + HtmlTemplates.escapeHtml(card.getDescription()) + "</div></a>";
    }
}
