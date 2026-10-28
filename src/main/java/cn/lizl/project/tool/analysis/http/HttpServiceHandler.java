package cn.lizl.project.tool.analysis.http;

import com.sun.net.httpserver.HttpExchange;
import lombok.Getter;

import java.io.IOException;
import java.util.Collection;

/**
 * 一个可挂载到 {@link HttpAnalysisServer} 的服务处理器。
 *
 * <p>一个处理器负责一组路径（例如 beans 通道的 {@code /beans} 与 {@code /bean/*}）。服务器只做四件事：
 * 鉴权、跨站写校验、按路径把请求分给处理器、渲染根路径索引页。新增一种服务 = 实现本接口 + 注册一次，
 * <b>不需要改服务器</b>。
 *
 * <p>实现方只需关心"这个路径该返回什么"，鉴权、安全响应头、token 透传都不用管：
 * 请求到达 {@link #handle} 之前已经过统一鉴权，响应建议统一走 {@link HttpResponses}。
 */
public interface HttpServiceHandler {

    /**
     * 本处理器负责的路径，相对服务器的 path-prefix，形如 {@code "/beans"}、{@code "/bean/"}。
     *
     * <p>路由按<b>最长前缀匹配</b>：{@code "/bean/alpha"} 会命中 {@code "/bean/"} 而不是 {@code "/beans"}。
     */
    Collection<String> getPaths();

    /**
     * 本处理器当前是否具备服务条件（例如对应通道是否装配）。
     *
     * <p>返回 false 时服务器不会把请求交给 {@link #handle}，而是返回 404 + {@link #unavailableMessage()}，
     * 让访问者知道是"没启用"而不是"地址写错"。
     */
    boolean isAvailable();

    /** 不可用时的提示文案，应包含开启该通道所需的配置键 */
    String unavailableMessage();

    /**
     * 处理请求。
     *
     * @param relativePath 已去掉 path-prefix 的完整路径，例如 {@code "/bean/alpha"}、{@code "/interceptor/state"}
     */
    void handle(HttpExchange exchange, String relativePath) throws IOException;

    /**
     * 根路径索引页上的入口卡片；返回 null 表示不在索引页展示。
     *
     * <p>链接由服务器按 {@link #getPaths()} 的第一项拼出（并自动带上 token），处理器不必关心鉴权参数。
     */
    default IndexCard indexCard() {
        return null;
    }

    /** 索引页卡片文案。链接不在这里，由服务器拼。 */
    @Getter
    final class IndexCard {

        private final String tag;

        private final String name;

        private final String description;

        public IndexCard(String tag, String name, String description) {
            this.tag = tag;
            this.name = name;
            this.description = description;
        }

    }
}
