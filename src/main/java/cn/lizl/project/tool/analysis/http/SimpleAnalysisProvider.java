package cn.lizl.project.tool.analysis.http;

import cn.lizl.project.tool.analysis.api.OutputFileReceiver;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 手动登记分析对象的 {@link AnalysisProvider} 简单实现，用于非 Spring 程序与单元测试。
 *
 * <p>分析粒度与产物形态由构造时传入的 {@link RunningConfigurer} 决定，本类只负责
 * 「登记对象 → 按需分析 → 交出结果文件」。
 *
 * <p>行为约定：{@code add} 只登记，首次访问 {@code /bean/{name}} 时才分析并落盘；configurer 是单个可变
 * 实例，因此所有分析串行执行，且每次分析前都会 {@code clear()}，不要在外并发复用同一个 configurer。
 *
 * @see HttpAnalysisServer
 */
public final class SimpleAnalysisProvider implements AnalysisProvider, AutoCloseable {

    /**
     * 文件名中被保留的最大字符数，避免对象名过长导致路径超限。
     */
    private static final int MAX_FILE_NAME_LENGTH = 60;

    private final Logger log = Logger.getLogger(getClass().getName());

    private final RunningConfigurer configurer;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private final List<String> names = new CopyOnWriteArrayList<>();

    private final AtomicInteger sequenceGenerator = new AtomicInteger(0);

    private final Object analyzeLock = new Object();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lizl-java-data-analyzer-manual");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param configurer 决定分析粒度与产物形态的运行配置器，本类会独占使用它；不能为 null
     */
    public SimpleAnalysisProvider(RunningConfigurer configurer) {
        if (configurer == null) {
            throw new IllegalArgumentException("configurer 不能为 null");
        }
        this.configurer = configurer;
    }

    /**
     * 按指定名字登记一个分析对象；同名重复登记会替换旧对象并复用原有文件名。
     * <p>
     * 传入 {@link Class} 时按「类结构」分析，传入实例时按「类结构 + 实例值」分析。
     *
     * @return this，便于链式调用
     */
    public SimpleAnalysisProvider add(String name, Object target) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("对象名不能为空");
        }
        if (target == null) {
            throw new IllegalArgumentException("分析对象不能为 null");
        }
        String finalName = name.trim();
        synchronized (this) {
            Entry existing = entries.get(finalName);
            int sequence = existing != null ? existing.sequence : sequenceGenerator.incrementAndGet();
            entries.put(finalName, new Entry(finalName, target, sequence));
            if (existing == null) {
                names.add(finalName);
            }
        }
        return this;
    }

    /**
     * 登记一个分析对象，名字由类简单名自动生成；重名时依次追加 {@code #2}、{@code #3}……
     *
     * @return this，便于链式调用
     */
    public SimpleAnalysisProvider add(Object target) {
        if (target == null) {
            throw new IllegalArgumentException("分析对象不能为 null");
        }
        return add(generateName(target), target);
    }

    /**
     * 移除一个已登记的对象；已生成的产物文件不会被删除。
     *
     * @return 是否确实存在并移除了该名字
     */
    public synchronized boolean remove(String name) {
        if (name == null || entries.remove(name) == null) {
            return false;
        }
        names.remove(name);
        return true;
    }

    /**
     * 移除全部已登记对象；已生成的产物文件不会被删除。
     *
     * @return this，便于链式调用
     */
    public synchronized SimpleAnalysisProvider clear() {
        entries.clear();
        names.clear();
        return this;
    }

    @Override
    public List<String> getBeanNames(boolean rebuild) {
        if (rebuild) {
            List<Entry> snapshot = snapshot();
            for (Entry entry : snapshot) {
                entry.outputFile = null;
            }
            submitReanalyze(snapshot);
        }
        return new ArrayList<>(names);
    }

    @Override
    public File getAnalyzedResult(String beanName, boolean rebuild) {
        if (beanName == null) {
            return null;
        }
        Entry entry = entries.get(beanName);
        if (entry == null) {
            return null;
        }
        if (rebuild) {
            entry.outputFile = null;
        }
        File cached = entry.outputFile;
        if (cached != null && cached.isFile()) {
            return cached;
        }
        return analyze(entry);
    }

    /**
     * 关闭内部重算线程池。关闭后仍可同步分析（{@code /bean/{name}} 照常可用），
     * 只是 {@code rebuild=true} 不再触发后台批量重算。
     */
    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void submitReanalyze(List<Entry> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            executor.execute(() -> {
                for (Entry entry : snapshot) {
                    analyze(entry);
                }
            });
        } catch (RejectedExecutionException e) {
            log.log(Level.FINE, "提供者已关闭，忽略批量重算请求", e);
        }
    }

    private List<Entry> snapshot() {
        List<Entry> result = new ArrayList<>(names.size());
        for (String name : names) {
            Entry entry = entries.get(name);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 串行执行一次分析。configurer 是单个可变实例，HTTP 线程与重算线程必须共用同一把锁。
     */
    private File analyze(Entry entry) {
        synchronized (analyzeLock) {
            File cached = entry.outputFile;
            if (cached != null && cached.isFile()) {
                return cached;
            }
            try {
                configurer.clear();
                configurer.setOutputFileName(fileNameOf(entry));
                configurer.addAnalyzingObject(entry.target);

                final File[] holder = new File[1];
                // run 的两个重载都是函数式接口，裸 lambda 会编译歧义，必须显式转型
                configurer.run((OutputFileReceiver) outputFile -> {
                    holder[0] = outputFile;
                    OutputFileReceiver.DEFAULT.receive(outputFile);
                });

                File outputFile = holder[0];
                if (outputFile != null && outputFile.isFile()) {
                    entry.outputFile = outputFile;
                    return outputFile;
                }
                log.warning("分析对象未产出文件：" + entry.name);
                return null;
            } catch (Throwable throwable) {
                log.log(Level.WARNING, "分析对象失败：" + entry.name, throwable);
                return null;
            } finally {
                configurer.clear();
            }
        }
    }

    private static String fileNameOf(Entry entry) {
        return String.format("%03d-%s-%s",
                entry.sequence, sanitize(entry.name), Integer.toHexString(entry.name.hashCode()));
    }

    private static String sanitize(String name) {
        StringBuilder builder = new StringBuilder(Math.min(name.length(), MAX_FILE_NAME_LENGTH));
        for (int i = 0; i < name.length() && builder.length() < MAX_FILE_NAME_LENGTH; i++) {
            char c = name.charAt(i);
            builder.append(isSafeFileNameChar(c) ? c : '_');
        }
        return builder.length() == 0 ? "object" : builder.toString();
    }

    private static boolean isSafeFileNameChar(char c) {
        if (c == '-' || c == '_' || c == '.') {
            return true;
        }
        // isLetterOrDigit 对中文等非 ASCII 字母同样返回 true，无需额外放行
        return Character.isLetterOrDigit(c);
    }

    private String generateName(Object target) {
        String base = target instanceof Class<?>
                ? ((Class<?>) target).getSimpleName()
                : target.getClass().getSimpleName();
        if (base.isEmpty()) {
            base = "anonymous";
        }
        synchronized (this) {
            if (!entries.containsKey(base)) {
                return base;
            }
            int index = 2;
            while (entries.containsKey(base + "#" + index)) {
                index++;
            }
            return base + "#" + index;
        }
    }

    private static final class Entry {

        private final String name;

        private final Object target;

        private final int sequence;

        private volatile File outputFile;

        private Entry(String name, Object target, int sequence) {
            this.name = name;
            this.target = target;
            this.sequence = sequence;
        }
    }
}
