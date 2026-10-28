package cn.lizl.project.tool.analysis.api;

/**
 * 进程级全局配置，通过 {@code JavaDataAnalyzer.globalConfigurer()} 获取。
 *
 * <p>setter 只登记动作，需调 {@link #apply()} 才真正写入。apply 按登记顺序执行、<b>没有事务性</b>：
 * 某个动作抛异常时前面的已生效、队列不清空，修复后再次 apply 会重放全部动作。
 * 实例本身非线程安全，勿跨线程共享同一配置器。
 *
 * <p>硬约束：{@link RunningConfigurer} 在创建那一刻快照全局配置，所以必须先 {@code apply()} 再创建实例，
 * 否则该次任务仍按旧的全局值运行。全局配置是静态的，同一 JVM 内所有调用方共享。
 */
public interface GlobalConfigurer extends GeneralConfigurer<GlobalConfigurer> {

    /**
     * 把排除包名恢复为内置默认值：{@code com.sun.}、{@code java.}、{@code javax.}、{@code jakarta.}、
     * {@code jdk.}、{@code sun.}。
     */
    GlobalConfigurer setCommonExcludePackagesToDefault();

    /**
     * 输出文件的根目录，默认 {@code <user.home>/cn.lizl.project.tool.analysis-result}，不存在时自动创建。
     */
    GlobalConfigurer setGlobalOutputRootDir(String outputRootDir);

    /**
     * {@link RunningConfigurer#addAnalyzingClass(String)} 遇到类名无法加载时是抛异常还是记警告后跳过，
     * 默认 {@code false}（只警告）。
     */
    GlobalConfigurer setThrowExceptionWhenStringClassNotFound(boolean value);

    /**
     * 按类名加载分析目标时使用的类加载器，默认本库自身的类加载器。
     *
     * <p>在 Spring Boot 可执行 jar、OSGi、热部署容器里业务类往往不在本库的类加载器下，必须显式传入
     * 业务侧的 {@code ClassLoader}，否则按类名添加的目标会加载失败。传 {@code null} 不生效。
     */
    GlobalConfigurer setClassLoader(ClassLoader classLoader);

    /**
     * 应用此前登记的全部设置。调用后队列清空，重复调用不会重复执行。
     */
    void apply();
}
