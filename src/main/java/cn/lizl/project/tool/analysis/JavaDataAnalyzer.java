package cn.lizl.project.tool.analysis;

import cn.lizl.project.tool.analysis.api.GlobalConfigurer;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import cn.lizl.project.tool.analysis.core.DefaultGlobalConfigurer;
import cn.lizl.project.tool.analysis.core.DefaultRunningConfigurer;
import cn.lizl.project.tool.analysis.http.AnalysisProvider;
import cn.lizl.project.tool.analysis.http.BeansHttpServiceHandler;
import cn.lizl.project.tool.analysis.http.HttpAnalysisServer;

/**
 * 库的唯一入口。
 *
 * <p>两条互不依赖的通道：手工调用产出 HTML / PUML 文件；HTTP 服务基于 JDK 原生 HttpServer，不依赖
 * Spring 与任何 Web 容器。
 */
public final class JavaDataAnalyzer {

    private JavaDataAnalyzer() {
    }

    /**
     * 新建全局配置器。每次返回新实例，未调 {@code apply()} 前不产生任何影响。
     */
    public static GlobalConfigurer globalConfigurer() {
        return new DefaultGlobalConfigurer();
    }

    /**
     * 新建单次分析配置器。深度、模式、渲染类型等在创建时快照当前全局配置（要在全局配置
     * {@code apply()} 之后再调用）；全局包规则（common include/exclude）与 {@code classLoader}
     * 则在分析时实时读取——改全局包规则对已创建实例同样生效，这是包过滤"四级叠加"语义所依赖的。
     */
    public static RunningConfigurer runningConfigurer() {
        return new DefaultRunningConfigurer();
    }

    /**
     * 创建基于 JDK 原生 HttpServer 的独立 HTTP 服务，预挂 beans 通道。返回的实例默认未启动，
     * 用完调 {@code stop()} 释放端口。
     *
     * <p>便捷组装层：服务器本身只认识 {@code HttpServiceHandler}，beans 概念由这里与
     * {@link BeansHttpServiceHandler} 承接。要挂多个聚合容器，对返回实例上的 beans 处理器无法再拿到——
     * 这种场景请自行 {@code register(new BeansHttpServiceHandler(pa, pb))}（容器名取自
     * {@code AnalysisProvider#getName()}）或在实例上 {@code addProvider}。
     *
     * <p>除 token / Basic 鉴权外没有其他访问控制，绑定地址建议限定在 127.0.0.1；确需对外暴露时请设置
     * {@link HttpAnalysisServer#setToken(String)} 或 {@link HttpAnalysisServer#setBasicAuth(String, String)}。
     */
    public static HttpAnalysisServer httpServer(AnalysisProvider provider) {
        return new HttpAnalysisServer().register(new BeansHttpServiceHandler(provider));
    }
}
