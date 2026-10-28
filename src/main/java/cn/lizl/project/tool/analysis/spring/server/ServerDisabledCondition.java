package cn.lizl.project.tool.analysis.spring.server;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import javax.annotation.Nonnull;

/**
 * {@link ServerEnabledCondition} 的反面：只在 HTTP 服务<b>没</b>启用时成立。
 *
 * <p>用于挂载「通道开着但 server 关着」的诊断提示——这是最容易让人误判成"功能坏了"的组合。
 */
public class ServerDisabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, @Nonnull AnnotatedTypeMetadata metadata) {
        return !flag(context.getEnvironment(), ServerProperties.PREFIX + ".enable");
    }

    /** 与 {@link ServerEnabledCondition} 同口径：enable 取不到再看 enabled */
    private static boolean flag(Environment environment, String key) {
        Boolean value = environment.getProperty(key, Boolean.class);
        if (value == null) {
            value = environment.getProperty(key + "d", Boolean.class);
        }
        return Boolean.TRUE.equals(value);
    }
}
