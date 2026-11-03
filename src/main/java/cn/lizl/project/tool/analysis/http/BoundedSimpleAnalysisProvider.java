package cn.lizl.project.tool.analysis.http;

import cn.lizl.project.tool.analysis.api.RunningConfigurer;

import java.util.List;

/**
 * 带固定容量上限的 {@link SimpleAnalysisProvider}：登记数量超过上限时，按<b>登记先后</b>淘汰最早的对象。
 *
 * <p>用于对象名不断变化、又长期不重启的进程（例如按请求创建对象），避免登记表无限增长，也不必手工
 * {@link #remove}。淘汰只摘掉登记，已落盘的产物保留在磁盘上（与 {@code remove} 的语义一致）；
 * 被淘汰的对象再 {@code add} 回来时会重新分析并覆盖原文件。
 *
 * <p>淘汰顺序是登记顺序，不是访问顺序：重复 {@code add} 同一名字只替换对象、不改变它的位置。
 */
public final class BoundedSimpleAnalysisProvider extends SimpleAnalysisProvider {

    private final int capacity;

    /**
     * @param name       容器注册名（{@link #getName()}），非空白
     * @param capacity   最多保留多少个登记对象，至少为 1；超出时淘汰最早登记的
     * @param configurer 决定分析粒度与产物形态的运行配置器，本类会独占使用它；不能为 null
     */
    public BoundedSimpleAnalysisProvider(String name, int capacity, RunningConfigurer configurer) {
        super(name, configurer);
        if (capacity < 1) {
            throw new IllegalArgumentException("容量至少为 1：" + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * 登记后立即把登记表收敛到上限。
     *
     * <p>与父类共用同一把锁（父类 {@code add} 内部也是 {@code synchronized (this)}，这里可重入），
     * 因此「登记 + 淘汰」是一个原子步骤：并发登记既不会短暂超限，也不会淘汰错对象。
     */
    @Override
    public SimpleAnalysisProvider add(String name, Object target) {
        synchronized (this) {
            super.add(name, target);
            List<String> registered = getBeanNames(false);
            // 返回列表按登记顺序排列，超出容量后留在表里的应是末尾 capacity 个，前面的逐个摘掉；
            // 这里只按下标取名字，不改动返回的列表。
            for (int i = 0; i < registered.size() - capacity; i++) {
                remove(registered.get(i));
            }
        }
        return this;
    }
}
