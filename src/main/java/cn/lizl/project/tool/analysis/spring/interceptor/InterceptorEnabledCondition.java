package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.spring.server.EnableFlag;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 开关键条件：只读 {@code interceptor.enable}，与其它两块配置的开关互不影响。
 *
 * <p>同时接受 {@code enable} 与 {@code enabled} 两种写法：interceptor 段的字段名是 {@code enable}，
 * 但使用者习惯写 {@code enabled: true}，写错就静默不生效，是最难排查的一类配置错误。
 */
class InterceptorEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return EnableFlag.isEnabled(context.getEnvironment(), InterceptorProperties.PREFIX + ".enable");
    }
}
