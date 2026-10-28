package cn.lizl.project.tool.analysis.spring.server;

import cn.lizl.project.tool.analysis.http.HttpAnalysisServer;
import cn.lizl.project.tool.analysis.http.HttpServiceHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 共用 HTTP 服务的自动配置：一台 server、一个端口、一套鉴权。
 *
 * <p>本类<b>不认识任何具体通道</b>：它只收集容器里所有的 {@link HttpServiceHandler} 并逐个挂载。
 * 新增一种服务 = 某个自动配置提供一个 {@code HttpServiceHandler} Bean，这里一行都不用改。
 *
 * <p>开关键 {@code cn.lizl.java-data-analyzer.server.enable}，默认关闭。
 * <b>一旦开启，服务必须真的起来</b>：端口被占用、地址非法等启动失败会直接让宿主应用启动失败。
 */
@EnableConfigurationProperties(ServerProperties.class)
@Conditional(ServerEnabledCondition.class)
@Configuration
public class ServerAutoConfiguration {

    private static final Logger LOG = Logger.getLogger(ServerAutoConfiguration.class.getName());

    @Bean(destroyMethod = "stop")
    public HttpAnalysisServer javaDataAnalyzerHttpServer(ServerProperties properties,
                                                         ObjectProvider<HttpServiceHandler> handlers) {
        HttpAnalysisServer server = new HttpAnalysisServer();
        List<HttpServiceHandler> registered = new ArrayList<>();
        boolean anyAvailable = false;
        // orderedStream 尊重 @Order，索引页卡片顺序 = 注册顺序
        for (HttpServiceHandler handler : handlers.orderedStream().toArray(HttpServiceHandler[]::new)) {
            server.register(handler);
            registered.add(handler);
            anyAvailable |= handler.isAvailable();
        }
        if (registered.isEmpty()) {
            LOG.warning("HTTP 服务已启用，但容器里没有任何 HttpServiceHandler，所有端点都会返回 404");
        } else if (!anyAvailable) {
            LOG.warning("HTTP 服务已启用，但没有任何可用通道（已注册 " + registered.size()
                    + " 个处理器，全部不可用），所有端点都会返回 404 + 未启用提示");
        }
        if (properties.getPort() != null) {
            server.setPort(properties.getPort());
        }
        if (properties.getBindAddress() != null) {
            server.setBindAddress(properties.getBindAddress());
        }
        if (properties.getPathPrefix() != null) {
            server.setPathPrefix(properties.getPathPrefix());
        }
        if (properties.getToken() != null) {
            server.setToken(properties.getToken());
        }
        if (properties.getAuthUser() != null) {
            // 用户名或密码缺一个时 setBasicAuth 内部按「未启用」处理，不需要在这里额外判空
            server.setBasicAuth(properties.getAuthUser(), properties.getAuthPassword());
        }
        try {
            return server.start();
        } catch (IOException e) {
            // 配上 server.enable=true 就是"我要这个服务"：这里失败必须让上下文一起失败。
            throw new IllegalStateException("JavaDataAnalyzer 独立 HTTP 服务启动失败（"
                    + server.getBoundPort() + " 端口）：" + rootMessage(e)
                    + "。可改用其它端口（" + ServerProperties.PREFIX + ".port）、"
                    + "用 0 让系统分配，或关闭 " + ServerProperties.PREFIX + ".enable", e);
        }
    }

    /** 取最内层异常的消息：BinderException/BeanCreation 之类外层包装对使用者没有信息量 */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
