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

    /** 运行时渲染类型：null 表示不覆盖（沿用全局配置），端点上显式改过后才非空 */
    private volatile RenderType renderType;

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
        // 不回落任何其它通道：配了就用自己的，没配就保持 null（分析时由全局配置决定）
        this.renderType = properties.getRenderType();
        this.writer = new InterceptorOutputWriter(
                properties.getOutputQueueCapacity() != null ? properties.getOutputQueueCapacity() : InterceptorOutputWriter.DEFAULT_QUEUE_CAPACITY,
                properties.getOutputRetention() != null ? properties.getOutputRetention() : InterceptorOutputWriter.DEFAULT_OUTPUT_RETENTION);
        this.writer.seedFromDisk(InterceptorRequestAnalyzer.resolveRootDir(properties));
    }

    /** 运行期捕获开关；通道未装配时本类根本不存在，故不必再判 interceptor.enable */
    boolean isActive() {
        return active;
    }

    /** 运行时渲染类型；null 表示本通道未指定，分析时沿用全局配置 */
    RenderType getRenderType() {
        return renderType;
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
        return state;
    }

    @Override
    public Map<String, Object> updateInterceptorState(Boolean active, List<String> includePaths,
                                                      List<String> excludePaths, String renderType) {
        if (renderType != null) {
            try {
                this.renderType = RenderType.valueOf(renderType);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("未知渲染类型：" + renderType);
            }
        }
        return updateInterceptorState(active, includePaths, excludePaths);
    }

    @Override
    public Map<String, Object> updateInterceptorState(Boolean active, List<String> includePaths, List<String> excludePaths) {
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
