package cn.lizl.project.tool.analysis.spring.beans;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import javax.annotation.Nonnull;

/**
 * 开关键条件：只读 {@code beans.enable}，与其它两块配置的开关互不影响。
 *
 * <p>同时接受 {@code enable} 与 {@code enabled} 两种写法：beans 段的字段名是 {@code enable}，
 * 但使用者习惯写 {@code enabled: true}，写错就静默不生效，是最难排查的一类配置错误。
 */
class BeansEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, @Nonnull AnnotatedTypeMetadata metadata) {
        return flag(context.getEnvironment(), BeansProperties.PREFIX + ".enable");
    }

    /** 取布尔开关：主键不存在时再看 {@code <key>d}（enable → enabled） */
    private static boolean flag(Environment environment, String key) {
        Boolean value = environment.getProperty(key, Boolean.class);
        if (value == null) {
            value = environment.getProperty(key + "d", Boolean.class);
        }
        return Boolean.TRUE.equals(value);
    }
}
