package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.http.HttpServiceHandler;
import cn.lizl.project.tool.analysis.spring.server.ServerDisabledCondition;
import cn.lizl.project.tool.analysis.spring.server.ServerEnabledCondition;
import cn.lizl.project.tool.analysis.spring.server.ServerProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.logging.Logger;

/**
 * 请求拦截器通道的自动配置：只装配本通道（控制 Bean + 参数解析器包装 + 自己的 HTTP 处理器），
 * 不碰 bean 采集、不碰 HTTP 服务本身。
 *
 * <p>开关键 {@code cn.lizl.java-data-analyzer.interceptor.enable}，默认关闭。
 * 装好之后是否真的捕获由运行期开关 {@code interceptor.active} 决定。
 */
@SuppressWarnings("ClassEscapesDefinedScope")
@EnableConfigurationProperties(InterceptorProperties.class)
@Configuration
public class InterceptorAutoConfiguration {

    private static final Logger LOG = Logger.getLogger(InterceptorAutoConfiguration.class.getName());

    /**
     * 通道的运行时控制：捕获开关与路径规则的内存态持有者，也是 {@code /interceptor} 端点的数据来源。
     * 随上下文关闭停掉写盘线程。
     *
     * <p>注意这是<b>装配</b>：未装配时整条通道不存在（没有控制 Bean、没有捕获包装）。
     * 运行期「暂停捕获」由 {@code interceptor.active} 表达。
     */
    @Bean(destroyMethod = "shutdown")
    @Conditional(InterceptorEnabledCondition.class)
    public InterceptorControl javaDataAnalyzerInterceptorControl(InterceptorProperties properties) {
        InterceptorControl control = new InterceptorControl(properties);
        if (!properties.isActive()) {
            LOG.warning("请求拦截器通道已装配，但捕获开关 interceptor.active 未开启（默认关闭），当前不会产生任何产物；"
                    + "如需启用请配置 " + InterceptorProperties.PREFIX + ".active=true，"
                    + "或在管理页 / POST /interceptor?active=true 临时打开");
        }
        return control;
    }

    /**
     * 拦截器端点的处理器。
     *
     * <p>两道条件，各管一件事：
     * <ul>
     *   <li>{@link ServerEnabledCondition}：<b>没有 HTTP 服务就不要这个 Bean</b>——处理器只被
     *       {@code ServerAutoConfiguration} 消费，服务不装配时它连模板都不会读一次</li>
     *   <li>不看 {@code interceptor.enable}：服务在、通道没装时 control 为 null，处理器照常注册，
     *       访问该组能拿到「interceptor 通道未启用」的明确提示，而不是干巴巴的 not found</li>
     * </ul>
     */
    @Bean
    @Conditional(ServerEnabledCondition.class)
    public HttpServiceHandler javaDataAnalyzerInterceptorHttpService(
            ObjectProvider<InterceptorControlProvider> interceptorControl) {
        return new InterceptorHttpService(interceptorControl.getIfAvailable());
    }

    /**
     * 「本通道装配了、但 HTTP 服务没开」的启动提示：捕获与落盘照常，只是没有管理页，
     * 产物只能去目录里翻、也没法在线暂停捕获。
     */
    @Bean
    @Conditional({InterceptorEnabledCondition.class, ServerDisabledCondition.class})
    public SmartInitializingSingleton javaDataAnalyzerInterceptorServerDisabledReporter() {
        return () -> LOG.warning("请求拦截器通道已装配，但 HTTP 服务未开启（" + ServerProperties.PREFIX + ".enable=false）："
                + "捕获与落盘不受影响（仍需 " + InterceptorProperties.PREFIX + ".active=true），产物写入 "
                + InterceptorProperties.PREFIX + ".output-root-dir，但没有 /interceptor 管理页，"
                + "无法在线查看产物或暂停捕获。需要管理页请配置 " + ServerProperties.PREFIX + ".enable=true");
    }

    /**
     * MVC 宿主里的参数捕获装配：只有 classpath 上有 DispatcherServlet 时才解析该配置类，
     * 非 MVC 应用连类都不加载。BPP 声明为 static，避免过早实例化触发容器警告。
     */
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    @Conditional(InterceptorEnabledCondition.class)
    @Configuration
    static class InterceptorWebConfiguration {

        @Bean
        static InterceptorArgumentCaptureInstaller javaDataAnalyzerInterceptorInstaller(
                ObjectProvider<InterceptorControl> interceptorControl) {
            return new InterceptorArgumentCaptureInstaller(interceptorControl::getIfAvailable);
        }
    }

    /**
     * WebFlux 宿主里的参数捕获装配，与 MVC 侧同构：按 DispatcherHandler 存在与否独立解析，
     * 两个通道共用同一个 {@link InterceptorControl}（开关/路径/产物登记全共享）。
     */
    @ConditionalOnClass(name = "org.springframework.web.reactive.DispatcherHandler")
    @Conditional(InterceptorEnabledCondition.class)
    @Configuration
    static class InterceptorWebFluxConfiguration {

        @Bean
        static ReactiveArgumentCaptureInstaller javaDataAnalyzerReactiveInstaller(
                ObjectProvider<InterceptorControl> interceptorControl) {
            return new ReactiveArgumentCaptureInstaller(interceptorControl::getIfAvailable);
        }
    }
}
