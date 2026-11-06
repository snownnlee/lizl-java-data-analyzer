package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.util.ThreadNames;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 拦截器通道的异步分析执行器：固定单守护线程 + 有界队列。队列满时降级为调用线程执行，那次分析不丢；
 * 停机最多等 5 秒，超时强制中断并告警——此时排队中的请求不产出报告。
 *
 * <p>提交时捕获请求线程的上下文类加载器并在工作线程内还原：Spring Boot 可执行 jar / devtools 下
 * 业务类只挂在业务 ClassLoader 上，工作线程默认拿到的是系统 ClassLoader。
 */
final class InterceptorAnalysisExecutor {

    /**
     * 队列容量默认值：与写盘队列同量级，排队的是待分析的对象图，容量即内存里最多压多少份请求入参。
     * 实际取值由 {@code interceptor.analysis-queue-capacity} 决定，未配置或非法时用本值。
     */
    static final int DEFAULT_QUEUE_CAPACITY = 64;

    /** 停机等待上限：超时强制打断，避免拖住上下文关闭 */
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final Logger log = Logger.getLogger(getClass().getName());

    /** 队列满载降级为调用线程同步分析的累计次数 */
    private final AtomicLong callerRunCount = new AtomicLong();

    private final ThreadPoolExecutor executor;

    InterceptorAnalysisExecutor(int queueCapacity) {
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, ThreadNames.of("interceptor-analysis"));
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, pool) -> {
                    // 满载不丢：在提交线程上跑完这次分析，代价只是这一个请求的延迟回到同步水平
                    long callerRuns = callerRunCount.incrementAndGet();
                    if (callerRuns == 1 || callerRuns % 100 == 0) {
                        log.warning("异步分析队列已满，累计 " + callerRuns
                                + " 次降级为请求线程同步分析（不丢产物）");
                    }
                    if (!pool.isShutdown()) {
                        runnable.run();
                    }
                });
    }

    void submit(Runnable task) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        executor.execute(() -> {
            Thread current = Thread.currentThread();
            ClassLoader original = current.getContextClassLoader();
            current.setContextClassLoader(contextClassLoader);
            try {
                task.run();
            } finally {
                current.setContextClassLoader(original);
            }
        });
    }

    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log.warning("异步分析队列在 " + SHUTDOWN_WAIT_SECONDS
                        + " 秒内未排空，已强制中断；排队的请求不会产出报告");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
