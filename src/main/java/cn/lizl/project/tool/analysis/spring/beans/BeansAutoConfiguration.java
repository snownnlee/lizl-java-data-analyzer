package cn.lizl.project.tool.analysis.spring.beans;

import cn.lizl.project.tool.analysis.http.AnalysisProvider;
import cn.lizl.project.tool.analysis.http.BeansHttpService;
import cn.lizl.project.tool.analysis.http.HttpServiceHandler;
import cn.lizl.project.tool.analysis.spring.server.ServerDisabledCondition;
import cn.lizl.project.tool.analysis.spring.server.ServerEnabledCondition;
import cn.lizl.project.tool.analysis.spring.server.ServerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.logging.Logger;

/**
 * beans 通道的自动配置：只装配 bean 采集与它自己的 HTTP 处理器，不碰 HTTP 服务本身、不碰拦截器。
 *
 * <p>开关键 {@code cn.lizl.java-data-analyzer.beans.enable}，默认关闭（用 Binder 绑定而不是
 * {@code @ConditionalOnProperty}：后者按属性名精确匹配、不做松散绑定）。
 *
 * <p>本类同时向容器提供一个 {@link HttpServiceHandler}，由 {@code ServerAutoConfiguration} 统一收集挂载——
 * 服务器那边不认识"beans"这个概念。
 */
@EnableConfigurationProperties(BeansProperties.class)
@Configuration
public class BeansAutoConfiguration {

    private static final Logger LOG = Logger.getLogger(BeansAutoConfiguration.class.getName());

    /** 是否启用 bean 采集：没启用时连采集处理器都不建（端点处理器是否注册见下一个方法） */
    @Bean
    @Conditional(BeansEnabledCondition.class)
    public JavaDataAnalyzerLifecycleHandler javaDataAnalyzerLifecycleHandler(BeansProperties properties) {
        // RunningConfigurer 有状态（攒待分析对象、clear 清空），刻意不注册成 Bean：
        // 只由 handler 独占并串行使用，避免被其他方注入后并发操作
        return new JavaDataAnalyzerLifecycleHandler(properties::newRunningConfigurer, properties);
    }

    /**
     * beans 端点的处理器。
     *
     * <p>两道条件，各管一件事：
     * <ul>
     *   <li>{@link ServerEnabledCondition}：<b>没有 HTTP 服务就不要这个 Bean</b>——处理器只被
     *       {@code ServerAutoConfiguration} 消费，服务不装配时它只是一份没人读的模板缓存</li>
     *   <li>不看 {@code beans.enable}：服务在、通道没开时 provider 为 null，处理器照常注册，
     *       访问该组能拿到「beans 通道未启用」的明确提示，而不是干巴巴的 not found</li>
     * </ul>
     */
    @Bean
    @Conditional(ServerEnabledCondition.class)
    public HttpServiceHandler javaDataAnalyzerBeansHttpService(ObjectProvider<AnalysisProvider> analysisProvider) {
        return new BeansHttpService(analysisProvider.getIfAvailable());
    }

    /**
     * 「本通道开着、但 HTTP 服务没开」的启动提示：没有网页入口时，{@code lazy=true}（默认）意味着
     * 只登记不分析，磁盘上永远不会出现产物——这种静默行为最容易被误判成功能坏了。
     */
    @Bean
    @Conditional({BeansEnabledCondition.class, ServerDisabledCondition.class})
    public SmartInitializingSingleton javaDataAnalyzerBeansServerDisabledReporter() {
        return () -> LOG.warning("bean 采集通道已启用，但 HTTP 服务未开启（" + ServerProperties.PREFIX + ".enable=false）："
                + "不会有任何产物。原因：lazy=true（默认）时只在首次访问 /bean/{name} 时才分析，"
                + "而网页入口正是唯一入口。需要落盘请配置 " + BeansProperties.PREFIX + ".lazy=false，"
                + "或配置 " + ServerProperties.PREFIX + ".enable=true 打开网页入口");
    }
}
