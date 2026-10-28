package cn.lizl.project.tool.analysis.api;

/**
 * 单次分析任务的配置与目标，通过 {@code JavaDataAnalyzer.runningConfigurer()} 获取。
 *
 * <p>深度、模式、渲染类型等在创建时对全局配置做快照，之后改全局配置不影响已创建的实例；
 * 要重新读取全局值请调 {@link #reset()}。例外：全局包规则（common include/exclude）与
 * {@code classLoader} 在分析时实时读取 {@code GlobalConfig}，改全局包规则对已创建实例同样生效。
 *
 * <p>实例可反复 {@code run}，配置与目标都保留，只有 {@link #clear()} 与 {@link #reset(boolean)} 会清掉目标。
 */
public interface RunningConfigurer extends GeneralConfigurer<RunningConfigurer> {

    /**
     * 本次分析的输出根目录，默认取全局的 {@code globalOutputRootDir}。目录不存在时自动创建。
     */
    RunningConfigurer setOutputRootDir(String outputRootDir);

    /**
     * 输出文件名，按路径段拼接：{@code setOutputFileName("order", "detail")} 得到
     * {@code order/detail.html}，中间目录自动创建。
     *
     * <p>最后一段会被补上当前渲染类型的扩展名，其中的非法文件名字符替换为全角问号。不设置时按分析目标
     * 自动命名。
     */
    RunningConfigurer setOutputFileName(String... outputFileName);

    /**
     * 添加只按类型分析的目标（字段值全部按类型展开）。
     */
    RunningConfigurer addAnalyzingClass(Class<?> analyzingClass);

    /**
     * 按全限定类名添加只按类型分析的目标。
     *
     * <p>用 {@link GlobalConfigurer#setClassLoader(ClassLoader)} 指定的类加载器加载且不初始化该类。
     * 类名找不到时默认记一条警告并跳过，是否抛异常由
     * {@link GlobalConfigurer#setThrowExceptionWhenStringClassNotFound(boolean)} 决定。
     */
    RunningConfigurer addAnalyzingClass(String analyzingClassName);

    /**
     * 添加按实例分析的目标。传入 {@link Class} 时等同 {@link #addAnalyzingClass(Class)}。
     */
    RunningConfigurer addAnalyzingObject(Object analyzingObject);

    /**
     * 执行分析并把产物写入文件，成功后回调。目标为空时不产出文件、也不回调。
     *
     * <p>回调在分析线程上同步执行，耗时操作请自行异步化。
     */
    RunningConfigurer run(OutputFileReceiver outputFileReceiver);

    /**
     * 执行分析并只把渲染结果交给回调，不写文件。目标为空时不回调，回调同样在分析线程上同步执行。
     */
    RunningConfigurer run(OutputContentReceiver outputContentReceiver);

    /**
     * 执行分析并把产物写入文件，只打印路径，等同 {@code run(OutputFileReceiver.DEFAULT)}。
     */
    default RunningConfigurer run() {
        return run(OutputFileReceiver.DEFAULT);
    }

    /**
     * 清空已添加的分析目标，不动配置项。
     */
    RunningConfigurer clear();

    /**
     * 配置项全部退回全局配置的当前值并清空目标，等同 {@code reset(true)}。
     */
    default RunningConfigurer reset() {
        return reset(true);
    }

    /**
     * 配置项退回调用这一刻的全局值，已设置的文件名一并清掉（恢复为自动命名）。
     *
     * @param clear 是否同时清空已添加的分析目标
     */
    RunningConfigurer reset(boolean clear);

    /**
     * 当前生效的单次分析允许耗时（毫秒）；≤0 表示不限制。
     *
     * <p>读的是本实例的快照值，不含尚未创建的实例；用于配置链验证与自检。
     */
    int analyzingTimeoutMillis();
}
