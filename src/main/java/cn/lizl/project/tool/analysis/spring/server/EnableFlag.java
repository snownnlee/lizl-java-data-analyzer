package cn.lizl.project.tool.analysis.spring.server;

import org.springframework.core.env.Environment;

/**
 * 三块配置共用的开关键判定：主键不存在时再看 {@code <key>d}（{@code enable} → {@code enabled}）。
 *
 * <p>为什么不用 {@code @ConditionalOnProperty}：它一次给多个属性名时要求<b>全部</b>满足，
 * 做不到「{@code enable} / {@code enabled} 任一即可」。这里只认这两个精确键，不做松散绑定。
 */
public final class EnableFlag {

    private EnableFlag() {
    }

    /** 取布尔开关：主键不存在时再看 {@code <key>d}（enable → enabled） */
    public static boolean isEnabled(Environment environment, String key) {
        Boolean value = environment.getProperty(key, Boolean.class);
        if (value == null) {
            value = environment.getProperty(key + "d", Boolean.class);
        }
        return Boolean.TRUE.equals(value);
    }
}
