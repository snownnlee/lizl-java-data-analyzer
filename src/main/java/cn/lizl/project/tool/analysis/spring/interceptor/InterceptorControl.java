package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.api.RenderType;
import org.springframework.util.AntPathMatcher;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 拦截器通道的运行时状态：捕获开关与路径规则的内存态，可被 {@code /interceptor} 端点热改；
 * 配置文件口径只作为初始值，热改不回写、重启失效。
 *
 * <p>注意这里只管<b>运行期是否捕获</b>（{@code interceptor.active}）。通道是否装配由
 * {@code interceptor.enable} 在启动期决定，装配后本类才存在，运行期无法卸载。
 */
final class InterceptorControl implements InterceptorControlProvider {

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final InterceptorOutputWriter writer;

    private volatile boolean active;

    /** 空列表 = 拦截所有请求；黑名单优先 */
    private volatile List<String> includePaths = Collections.emptyList();

    private volatile List<String> excludePaths = Collections.emptyList();

    /** 运行时渲染类型，端点上可热切 */
    private volatile RenderType renderType;

    /** 运行期异步分析开关，端点上可热切；初始值取自 interceptor.async-analysis */
    private volatile boolean asyncAnalysis;

    /** 异步分析线程池：首次真正需要异步时才创建，默认同步配置下不产生线程 */
    private volatile InterceptorAnalysisExecutor analysisExecutor;

    /**
     * MVC 异步分析队列容量：启动期解析一次即固定（配置非法时此处就会打出 WARN），线程池懒创建时直接用。
     */
    private final int analysisQueueCapacity;

    private final InterceptorProperties properties;

    private volatile InterceptorRequestAnalyzer analyzer;

    InterceptorControl(InterceptorProperties properties) {
        this.properties = properties;
        this.active = properties.isActive();
        if (properties.getIncludePaths() != null) {
            this.includePaths = Collections.unmodifiableList(sanitizePaths(properties.getIncludePaths(), "include-paths"));
        }
        if (properties.getExcludePaths() != null) {
            this.excludePaths = Collections.unmodifiableList(sanitizePaths(properties.getExcludePaths(), "exclude-paths"));
        }
        // 初始渲染类型与异步开关从配置读取
        this.renderType = properties.getRenderType();
        this.asyncAnalysis = properties.isAsyncAnalysis();
        // 容量在这里定型：非法配置在启动期就打出 WARN，运行期改配置不会重建线程池
        this.analysisQueueCapacity = properties.resolveAnalysisQueueCapacity();
        this.writer = new InterceptorOutputWriter(
                properties.getOutputQueueCapacity() != null ? properties.getOutputQueueCapacity() : InterceptorOutputWriter.DEFAULT_QUEUE_CAPACITY,
                properties.getOutputRetention() != null ? properties.getOutputRetention() : InterceptorOutputWriter.DEFAULT_OUTPUT_RETENTION);
        this.writer.seedFromDisk(InterceptorRequestAnalyzer.resolveRootDir(properties));
    }

    /** 运行期捕获开关；通道未装配时本类根本不存在，故不必再判 interceptor.enable */
    boolean isActive() {
        return active;
    }

    /** 运行时渲染类型 */
    RenderType getRenderType() {
        return renderType;
    }

    /** 运行时是否异步分析 */
    boolean isAsyncAnalysis() {
        return asyncAnalysis;
    }

    /** 提交一次异步分析；线程池懒创建，队列满时由提交线程自己执行 */
    void submitAnalysis(Runnable task) {
        InterceptorAnalysisExecutor executor = analysisExecutor;
        if (executor == null) {
            synchronized (this) {
                executor = analysisExecutor;
                if (executor == null) {
                    executor = new InterceptorAnalysisExecutor(analysisQueueCapacity);
                    analysisExecutor = executor;
                }
            }
        }
        executor.submit(task);
    }

    /** 黑名单命中或白名单不命中都返回 false；白名单为空表示放行所有 */
    boolean shouldAnalyze(String requestUri) {
        if (!active) {
            return false;
        }
        for (String excludePath : excludePaths) {
            if (pathMatcher.match(excludePath, requestUri)) {
                return false;
            }
        }
        if (includePaths.isEmpty()) {
            return true;
        }
        for (String includePath : includePaths) {
            if (pathMatcher.match(includePath, requestUri)) {
                return true;
            }
        }
        return false;
    }

    boolean submit(InterceptorOutputWriter.Entry entry) {
        return writer.submit(entry);
    }

    InterceptorRequestAnalyzer analyzer() {
        InterceptorRequestAnalyzer result = analyzer;
        if (result == null) {
            synchronized (this) {
                result = analyzer;
                if (result == null) {
                    result = new InterceptorRequestAnalyzer(properties, this);
                    analyzer = result;
                }
            }
        }
        return result;
    }

    InterceptorOutputWriter writer() {
        return writer;
    }

    void shutdown() {
        // 先排空分析：排队中的任务还会往写盘队列里塞产物
        InterceptorAnalysisExecutor executor = analysisExecutor;
        if (executor != null) {
            executor.shutdown();
        }
        writer.shutdown();
    }

    @Override
    public Map<String, Object> interceptorState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("active", active);
        state.put("includePaths", includePaths);
        state.put("excludePaths", excludePaths);
        state.put("queueSize", writer.queueSize());
        state.put("submitted", writer.getSubmitted());
        state.put("written", writer.getWritten());
        state.put("dropped", writer.getDropped());
        state.put("retention", writer.getOutputRetention());
        state.put("renderType", renderType == null ? null : renderType.name());
        state.put("asyncAnalysis", asyncAnalysis);
        return state;
    }

    @Override
    public Map<String, Object> updateInterceptorState(Boolean active, List<String> includePaths,
                                                      List<String> excludePaths) {
        return updateInterceptorState(active, includePaths, excludePaths, null, null);
    }

    @Override
    public Map<String, Object> updateInterceptorState(Boolean active, List<String> includePaths,
                                                      List<String> excludePaths, String renderType) {
        return updateInterceptorState(active, includePaths, excludePaths, renderType, null);
    }

    @Override
    public Map<String, Object> updateInterceptorState(Boolean active, List<String> includePaths,
                                                      List<String> excludePaths, String renderType,
                                                      Boolean asyncAnalysis) {
        if (renderType != null) {
            try {
                this.renderType = RenderType.valueOf(renderType);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知渲染类型：" + renderType);
            }
        }
        if (asyncAnalysis != null) {
            this.asyncAnalysis = asyncAnalysis;
        }
        if (active != null) {
            this.active = active;
        }
        if (includePaths != null) {
            this.includePaths = Collections.unmodifiableList(validatedPaths(includePaths, "includePaths"));
        }
        if (excludePaths != null) {
            this.excludePaths = Collections.unmodifiableList(validatedPaths(excludePaths, "excludePaths"));
        }
        return interceptorState();
    }

    /** 热改路径规则校验：null/空白元素直接拒绝（含 null 的规则会让 {@code match} 抛 NPE，
     *  NPE 又被捕获路径吞掉，表现为开启后静默捕获全灭，必须在入口拦下） */
    private static List<String> validatedPaths(List<String> paths, String name) {
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) {
                throw new IllegalArgumentException(name + " 含 null 或空白元素，已拒绝本次更新");
            }
        }
        return new ArrayList<>(paths);
    }

    /** 启动期配置宽松处理：过滤 null/空白元素并告警，不让脏配置杀死整个通道 */
    private static List<String> sanitizePaths(List<String> paths, String name) {
        List<String> result = new ArrayList<>(paths.size());
        for (String path : paths) {
            if (path != null && !path.trim().isEmpty()) {
                result.add(path);
            }
        }
        if (result.size() != paths.size()) {
            Logger.getLogger(InterceptorControl.class.getName()).warning(
                    name + " 含 " + (paths.size() - result.size()) + " 个 null/空白元素，已过滤");
        }
        return result;
    }

    @Override
    public List<String> interceptorOutputNames() {
        return writer.outputNames();
    }

    @Override
    public File getInterceptorOutput(String name) {
        return writer.getOutput(name);
    }
}
