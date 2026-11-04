package cn.lizl.project.tool.analysis.http;

import com.sun.net.httpserver.HttpExchange;
import lombok.Getter;

import java.io.IOException;
import java.util.Collection;

/**
 * 一个可挂载到 {@link HttpAnalysisServer} 的服务处理器。
 *
 * <p>一个处理器负责一组请求（例如 beans 通道的 {@code /beans} 与 {@code /bean/*}）。服务器只做四件事：
 * 鉴权、跨站写校验、按 {@link #supports} 把请求分给处理器、渲染根路径索引页。新增一种服务 = 实现本接口
 * + 注册一次，<b>不需要改服务器</b>。
 *
 * <p>实现方只需关心"这个请求该不该我处理、该返回什么"，鉴权、安全响应头、token 透传都不用管：
 * 请求到达 {@link #handle} 之前已经过统一鉴权，响应建议统一走 {@link HttpResponses}。
 */
@SuppressWarnings("JavadocReference")
public interface HttpServiceHandler {

    /**
     * 服务器在分发前放入 exchange attribute 的相对路径键（已剥掉服务器的 path-prefix）。
     *
     * <p>exchange 本身携带的是带 prefix 的原始路径，而 prefix 是服务器运行时可配的，
     * 处理器无法自行算出相对路径——默认 {@link #supports} 与自定义实现都从这里取。
     */
    String RELATIVE_PATH_ATTRIBUTE = HttpServiceHandler.class.getName() + ".relativePath";

    /**
     * 本处理器的入口路径，相对服务器的 path-prefix，形如 {@code "/beans"}、{@code "/interceptor"}。
     *
     * <p>消费方有两处：根路径索引页的卡片链接（自动带上当前 token）与启动日志里的「通道已挂载」地址，
     * 因此必须是能直接点进去的页面地址，而不是仅用于匹配的路径前缀。启动期收尾时也会被调用，不应抛异常。
     *
     * <p>不再作为服务器的路由注册键（路由由 {@link #supports} 决定）。
     */
    String getEntryPath();

    /**
     * 本处理器认领的路径前缀集合，供默认 {@link #supports} 做前缀匹配：路径与该前缀完全相等，
     * 或以该前缀开头且紧跟 {@code /}（所以 {@code /customx} 不会误命中 {@code /custom}）。
     *
     * <p>同一处理器声明多个前缀时彼此等价——它只回答"认不认领"，不参与跨处理器的优先级。
     *
     * <p>这组前缀是处理器固有的路由声明，不是每次调用现算的东西：实现必须返回固定集合（静态常量），
     * 不要每次调用都新建列表——默认 {@code supports} 在每个请求的路由匹配里都会遍历它。
     */
    Collection<String> getPathPrefixes();

    /**
     * 是否处理该请求。返回 true 后服务器会调 {@link #handle}；多个处理器都返回 true 时
     * 按注册顺序先到先得，因此处理器应只对真正属于自己的请求返回 true。
     *
     * <p>默认实现取 {@link #RELATIVE_PATH_ATTRIBUTE} 里的相对路径，对 {@link #getPathPrefixes()}
     * 做前缀匹配（结果只区分认领与否；前缀必须止于路径分隔符，避免 {@code /customx} 误命中
     * {@code /custom}）。需要更灵活的路由（按方法、请求头或自定义路径规则）时自行覆盖。
     */
    default boolean supports(HttpExchange exchange) {
        Object attribute = exchange.getAttribute(RELATIVE_PATH_ATTRIBUTE);
        if (!(attribute instanceof String)) {
            return false;
        }
        String relativePath = (String) attribute;
        for (String path : getPathPrefixes()) {
            if (relativePath.equals(path)
                    || (relativePath.startsWith(path)
                    && (path.endsWith("/") || relativePath.charAt(path.length()) == '/'))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 本处理器当前是否具备服务条件（例如对应通道是否装配）。
     *
     * <p>返回 false 时服务器不会把请求交给 {@link #handle}，而是返回 404 + {@link #unavailableMessage()}，
     * 让访问者知道是"没启用"而不是"地址写错"。
     *
     * <p>启动期与每个请求都会被调用，不应抛异常——抛出会中断服务器的 {@code start()} 收尾，
     * 导致宿主启动失败。
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
     * <p>链接由服务器按 {@link #getEntryPath()} 拼出（并自动带上 token），处理器不必关心鉴权参数。
     *
     * <p>启动期收尾时也会被调用，不应抛异常。
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
