package cn.lizl.project.tool.analysis.spring.server;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 共用 HTTP 服务的传输配置，前缀 {@code cn.lizl.java-data-analyzer.server}。
 *
 * <p>beans 与 interceptor 两个通道共用同一台 server（一个端口、一套鉴权），
 * 各自只决定自己的端点组挂不挂——挂载与否由各通道自己的 {@code enable} 决定。
 *
 * <p>{@code enable=true} 即「这个服务必须起来」：端口被占用等启动失败会直接让宿主应用启动失败。
 */
@Data
@ConfigurationProperties(prefix = ServerProperties.PREFIX)
public class ServerProperties {

    /** 配置项前缀。必须是编译期常量：注解属性只接受常量表达式。 */
    public static final String PREFIX = "cn.lizl.java-data-analyzer.server";

    /** 是否启动 HTTP 服务。默认关闭，需显式配 true。 */
    private boolean enable = false;

    /** 监听端口；不配置时用 18080，{@code 0} 表示由系统分配。端口被占用会让宿主启动失败（见本类 javadoc）。 */
    private Integer port;

    /** 绑定地址；不配置时只绑 127.0.0.1。 */
    private String bindAddress;

    /** 访问路径前缀；不配置时用 {@code /lizl-java-data-analyzer}。 */
    private String pathPrefix;

    /** 访问令牌；配置后需携带 {@code ?token=xxx} 或请求头 {@code X-Analysis-Token}。 */
    private String token;

    /** HTTP Basic 认证用户名；与密码同时配置后启用，凭证不进 URL。 */
    private String authUser;

    /** HTTP Basic 认证密码；标 {@code @ToString.Exclude} 免得被 {@code @Data} 的 toString 打进日志。 */
    @ToString.Exclude
    private String authPassword;
}
