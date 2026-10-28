package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.util.CommonUtil;
import cn.lizl.project.tool.analysis.util.FrontendAssets;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 请求分析产物的异步落盘器：单守护线程消费有界队列，写文件并记一条摘要日志。
 * 队列满载**不丢产物**：短等待后仍满则降级为调用线程同步落盘（限频告警），
 * 代价只是极端突发时请求线程短暂写盘，内存占用上限仍由队列容量约束。
 *
 * <p>产物按保留条数滚动淘汰，且**按产物所在目录（日期目录）独立计算**：每个目录各自保留最近
 * {@code outputRetention} 份，超出时只删本目录里最旧的，其他目录不受影响。
 */
final class InterceptorOutputWriter {

    static final int DEFAULT_QUEUE_CAPACITY = 64;

    /** 默认每目录保留 3000 份；磁盘压力大的场景可用 output-retention=0 关闭限制 */
    static final int DEFAULT_OUTPUT_RETENTION = 3000;

    private final Logger log = Logger.getLogger(getClass().getName());

    private final BlockingQueue<Entry> queue;

    private final Thread worker;

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final AtomicLong submitted = new AtomicLong();

    private final AtomicLong written = new AtomicLong();

    /** 写盘失败（产物真正丢失）的累计数；满载不再丢弃，只降级同步落盘 */
    private final AtomicLong dropped = new AtomicLong();

    /** 满载降级为调用线程同步落盘的累计次数 */
    private final AtomicLong callerRunCount = new AtomicLong();

    /** 产物保留上限（按所在目录独立计算）；≤0 不限制 */
    private final int outputRetention;

    /**
     * 已落盘产物登记表：name → 文件，旧的在前。
     * 写盘线程与满载降级的调用线程都会写，HTTP 读取方会遍历——全部增删以
     * {@code outputRecords} 自身为锁（见 {@link #addToRecords}），锁内保证遍历安全。
     * 淘汰时连同磁盘文件一起删。
     */
    private final LinkedHashMap<String, File> outputRecords = new LinkedHashMap<>(64, 0.75f, false);

    /**
     * 按目录（日期目录）索引的登记表视图：目录路径 → 目录内 name → 文件（插入序即时间序）。
     * 与 {@link #outputRecords} 同步增删，让保留淘汰只扫本目录（O(目录内条数)），
     * 不再每次写盘全表扫描（O(全登记表)，跨日期长期运行会退化成 O(n²)/天）。
     * 与 {@link #outputRecords} 同锁保护，不单独加锁。
     */
    private final Map<String, LinkedHashMap<String, File>> outputRecordsByDir = new HashMap<>();

    InterceptorOutputWriter(int queueCapacity, int outputRetention) {
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.outputRetention = outputRetention;
        this.worker = new Thread(this::drain, "lizl-java-data-analyzer-interceptor-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** 满载降级同步落盘的短等待时长：给写盘线程一点排空时间摊平突发，又不至于久堵请求线程 */
    private static final long OVERFLOW_GRACE_MILLIS = 100;

    /**
     * 满载不丢：先直接入队；满则短等待重试；仍满降级为调用线程同步落盘（限频告警）。
     * 产物是分析记录，要求尽可能不漏；代价只是极端突发时请求线程写一次盘。
     * 始终返回 true（不再有丢弃路径），调用方无须检查返回值。
     */
    boolean submit(Entry entry) {
        if (queue.offer(entry)) {
            submitted.incrementAndGet();
            return true;
        }
        try {
            if (queue.offer(entry, OVERFLOW_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                submitted.incrementAndGet();
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 中断后仍走同步落盘，保证不丢
        }
        long callerRuns = callerRunCount.incrementAndGet();
        if (callerRuns == 1 || callerRuns % 100 == 0) {
            log.warning("请求分析产物队列已满，累计 " + callerRuns + " 次降级为调用线程同步落盘（不丢产物），建议调大 output-queue-capacity");
        }
        writeEntry(entry);
        submitted.incrementAndGet();
        return true;
    }

    /** 认得的产物扩展名（含复合扩展名，按 endsWith 匹配） */
    private static final String[] PRODUCT_EXTENSIONS = {".puml.html", ".mmd.html", ".html", ".puml", ".mmd"};

    /**
     * 启动时扫描输出根目录下的日期目录（8 位数字命名），把磁盘上已有的产物回填登记表——
     * 登记表只在内存，不回填的话进程重启后列表为空、磁盘文件成了孤儿。非日期目录（如 bean 通道的
     * 时间戳目录）不扫；随后按保留策略对每个目录独立收缩。
     * <p>
     * 日期目录名（8 位数字）；启动回填逐目录判断用 */
    private static final Pattern DATE_DIR_NAME = Pattern.compile("\\d{8}");

    void seedFromDisk(String rootDir) {
        File root = new File(rootDir);
        File[] dateDirs = root.listFiles(File::isDirectory);
        if (dateDirs == null) {
            return;
        }
        List<File> seeded = new ArrayList<>();
        for (File dateDir : dateDirs) {
            if (!DATE_DIR_NAME.matcher(dateDir.getName()).matches()) {
                continue;
            }
            File[] files = dateDir.listFiles(File::isFile);
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (isProductFile(file.getName())) {
                    seeded.add(file);
                }
            }
        }
        if (seeded.isEmpty()) {
            return;
        }
        // 文件名带毫秒时间戳与序号，按路径升序插入即时间序
        seeded.sort(Comparator.comparing(File::getPath));
        for (File file : seeded) {
            String name = file.getParentFile().getName() + "/" + file.getName();
            addToRecords(name, file);
            enforceRetention(file.getParent());
        }
        log.info("拦截器产物登记表已从磁盘回填 " + outputRecords.size() + " 份（" + rootDir + "）");
    }

    private static boolean isProductFile(String fileName) {
        String lower = fileName.toLowerCase();
        for (String extension : PRODUCT_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    int queueSize() {
        return queue.size();
    }

    long getSubmitted() {
        return submitted.get();
    }

    long getWritten() {
        return written.get();
    }

    long getDropped() {
        return dropped.get();
    }

    int getOutputRetention() {
        return outputRetention;
    }

    void shutdown() {
        running.set(false);
        worker.interrupt();
    }

    private void drain() {
        while (running.get() || !queue.isEmpty()) {
            Entry entry;
            try {
                entry = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 停机打断不丢产物：改非阻塞取继续排空，队列清空后由循环条件退出
                entry = queue.poll();
            }
            if (entry == null) {
                continue;
            }
            writeEntry(entry);
        }
    }

    /** 单条落盘 + 登记；写盘线程与满载降级路径共用，失败只告警不影响调用方 */
    private void writeEntry(Entry entry) {
        try {
            File outputFile = new File(entry.rootDir, entry.fileName);
            CommonUtil.writeUtf8String(entry.content, outputFile);
            // 产物页相对引用同目录引擎：首次写该目录时补一份，之后复用
            FrontendAssets.ensureReferenced(entry.content, outputFile.getParentFile());
            written.incrementAndGet();
            recordOutput(entry.fileName, outputFile);
            log.info("请求分析产物已落盘：" + entry.summary + " => " + entry.fileName);
        } catch (Throwable throwable) {
            dropped.incrementAndGet();
            log.log(Level.WARNING, "请求分析产物写盘失败：" + entry.summary, throwable);
        }
    }

    private void recordOutput(String name, File file) {
        addToRecords(name, file);
        enforceRetention(file.getParent());
    }

    /**
     * 同步登记进全局表与按目录索引表；同名覆盖（先移除保证插入序刷新到最新）。
     * 写盘线程与满载降级的调用线程都会走到这里，与 {@link #outputNames()}/{@link #getOutput(String)}
     * 的读取方共用 {@code outputRecords} 监视器，保证遍历安全与可见性。
     */
    private void addToRecords(String name, File file) {
        synchronized (outputRecords) {
            outputRecords.remove(name);
            outputRecords.put(name, file);
            String dirPath = file.getParent();
            LinkedHashMap<String, File> dirRecords = outputRecordsByDir.computeIfAbsent(dirPath, k -> new LinkedHashMap<>(64, 0.75f, false));
            dirRecords.remove(name);
            dirRecords.put(name, file);
        }
    }

    /**
     * 按目录独立保留：只淘汰 {@code dirPath} 目录内的登记项，其他目录（其他日期）不受影响。
     * 走按目录索引表，插入序即时间序，目录内头部就是最旧的，淘汰 O(超出条数)。
     */
    private void enforceRetention(String dirPath) {
        if (outputRetention <= 0) {
            return;
        }
        synchronized (outputRecords) {
            LinkedHashMap<String, File> dirRecords = outputRecordsByDir.get(dirPath);
            if (dirRecords == null) {
                return;
            }
            while (dirRecords.size() > outputRetention) {
                Iterator<Map.Entry<String, File>> eldest = dirRecords.entrySet().iterator();
                Map.Entry<String, File> eldestEntry = eldest.next();
                eldest.remove();
                outputRecords.remove(eldestEntry.getKey());
                File eldestFile = eldestEntry.getValue();
                if (eldestFile.isFile() && !eldestFile.delete()) {
                    log.warning("淘汰最旧产物失败（文件仍保留在磁盘，登记已移除）：" + eldestEntry.getKey());
                }
            }
        }
    }

    /** 新的在前 */
    List<String> outputNames() {
        synchronized (outputRecords) {
            List<String> names = new ArrayList<>(outputRecords.keySet());
            Collections.reverse(names);
            return names;
        }
    }

    File getOutput(String name) {
        synchronized (outputRecords) {
            return outputRecords.get(name);
        }
    }

    static final class Entry {

        final String rootDir;

        final String fileName;

        final String content;

        final String summary;

        Entry(String rootDir, String fileName, String content, String summary) {
            this.rootDir = rootDir;
            this.fileName = fileName;
            this.content = content;
            this.summary = summary;
        }
    }
}
