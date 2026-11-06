package cn.lizl.project.tool.analysis.util;

/**
 * 本库线程的命名规则：{@code <prefix>-<role>[-<identifier>...]}。
 *
 * <p>线程名是 thread dump、APM 与日志里唯一的来源线索，两段都要能定位问题：<b>角色</b>说明这条线程在
 * 干什么（HTTP 请求、容器分析、拦截器落盘……），<b>标识</b>说明是哪个实例（端口、序号、容器注册名）。
 * 只给角色不给标识时，同一个 JVM 里挂两个容器就会出现两条同名线程，dump 出来仍然分不清。
 *
 * <p>标识来自使用者（容器名可自由填写），因此统一净化：只保留字母、数字、点与下划线（中文等字母类字符
 * 保留），其余字符折叠成单个 {@code -}，并截断到 40 个字符——避免空格、控制字符甚至换行混进 thread dump。
 */
public final class ThreadNames {

    /** 本库所有线程名的公共前缀 */
    public static final String PREFIX = "lizl-java-data-analyzer";

    /** 单个角色 / 标识的最大长度：容器名可能很长，不能让线程名喧宾夺主 */
    private static final int MAX_PART_LENGTH = 40;

    private ThreadNames() {
    }

    /**
     * 拼一个线程名。
     *
     * @param role        角色，如 {@code http}、{@code analysis}、{@code interceptor-writer}
     * @param identifiers 实例标识（端口、序号、容器名等），{@code null} 或净化后为空时跳过
     * @return 形如 {@code lizl-java-data-analyzer-analysis-SpringBeans} 的线程名
     */
    public static String of(String role, Object... identifiers) {
        StringBuilder name = new StringBuilder(PREFIX);
        appendPart(name, role);
        if (identifiers != null) {
            for (Object identifier : identifiers) {
                if (identifier != null) {
                    appendPart(name, String.valueOf(identifier));
                }
            }
        }
        return name.toString();
    }

    private static void appendPart(StringBuilder name, String raw) {
        String part = sanitize(raw);
        if (!part.isEmpty()) {
            name.append('-').append(part);
        }
    }

    /** 只留字母 / 数字 / 点 / 下划线，其余折叠成单个 {@code -}；首尾的 {@code -} 去掉并截断 */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(Math.min(raw.length(), MAX_PART_LENGTH));
        for (int i = 0; i < raw.length() && sanitized.length() < MAX_PART_LENGTH; i++) {
            char ch = raw.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '_') {
                sanitized.append(ch);
            } else if (sanitized.length() > 0 && sanitized.charAt(sanitized.length() - 1) != '-') {
                sanitized.append('-');
            }
        }
        int end = sanitized.length();
        while (end > 0 && sanitized.charAt(end - 1) == '-') {
            end--;
        }
        return sanitized.substring(0, end);
    }
}
