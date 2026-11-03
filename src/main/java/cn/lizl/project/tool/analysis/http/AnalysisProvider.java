package cn.lizl.project.tool.analysis.http;

import java.io.File;
import java.util.List;

/**
 * beans 端点的数据提供者契约：给一批可查看的对象名，再按名字取分析结果文件。
 *
 * <p>刻意不依赖任何框架：Spring 集成侧由采集处理器实现它，非 Spring 程序可以直接用
 * {@link SimpleAnalysisProvider} 手工登记对象。
 */
public interface AnalysisProvider {

    /**
     * 容器注册名：{@link BeansHttpServiceHandler} 用它登记本 provider，也是 {@code ?container=} 的取值，
     * 因此同一处理器内必须唯一。
     *
     * <p>必须是稳定值：运行期改名会让已经发出的 {@code ?container=} 链接失效。空白值在登记时直接抛
     * {@link IllegalArgumentException}。
     */
    String getName();

    /**
     * 返回可查看的对象名列表。
     *
     * @param rebuild 为 true 时触发一次重新分析（异步），并返回当前已有的名字列表
     */
    List<String> getBeanNames(boolean rebuild);

    /**
     * 返回某个对象名的分析结果文件；不存在或分析失败时返回 null。
     *
     * @param rebuild 为 true 时丢弃缓存、强制重新分析
     */
    File getAnalyzedResult(String beanName, boolean rebuild);

    /**
     * 容器的展示名；返回 null 时用 {@link #getName()} 的注册名。
     *
     * <p>只在多容器的选择页上展示；单容器页面不出现容器名，实现可以不关心本方法。
     */
    default String getDisplayName() {
        return null;
    }
}
