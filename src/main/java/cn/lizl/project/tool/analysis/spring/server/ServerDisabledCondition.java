package cn.lizl.project.tool.analysis.spring.server;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
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
        return !EnableFlag.isEnabled(context.getEnvironment(), ServerProperties.PREFIX + ".enable");
    }
}
