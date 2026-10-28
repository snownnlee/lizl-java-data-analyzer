package cn.lizl.project.tool.analysis.spring.interceptor;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 请求拦截器通道的运行时控制。独立于任何 Web 容器：由 {@link InterceptorHttpService} 的
 * {@code /interceptor} 端点读写，实现方自行保证并发安全与状态语义。
 *
 * <p>这里的开关是<b>运行期捕获开关</b>（{@code interceptor.active}），不是通道装配开关
 * （{@code interceptor.enable}）——后者在启动期决定，装配后无法在运行期改变。
 *
 * <p>运行时修改只落在内存，进程重启后回到配置文件口径。
 */
public interface InterceptorControlProvider {

    /**
     * 当前状态：捕获开关（键 {@code active}）、路径规则、队列与计数。
     * 返回的 Map 值只含 Boolean / Number / String / List&lt;String&gt;。
     */
    Map<String, Object> interceptorState();

    /**
     * 更新运行时状态，返回更新后的完整状态。
     *
     * @param active       null 表示不变，否则设置运行期捕获开关
     * @param includePaths null 表示不变，空列表表示清空（清空后拦截所有请求）
     * @param excludePaths 同上
     * @throws IllegalArgumentException 参数非法（如路径规则含 null 元素）
     */
    Map<String, Object> updateInterceptorState(Boolean active, List<String> includePaths, List<String> excludePaths);

    /**
     * 同 {@link #updateInterceptorState(Boolean, List, List)}，额外支持运行时切换渲染类型。
     *
     * @param renderType null 表示不变，否则为 {@code RenderType} 枚举名；非法值抛 {@code IllegalArgumentException}
     */
    default Map<String, Object> updateInterceptorState(Boolean active, List<String> includePaths,
                                                       List<String> excludePaths, String renderType) {
        if (renderType != null) {
            throw new IllegalArgumentException("该实现不支持运行时切换渲染类型：" + renderType);
        }
        return updateInterceptorState(active, includePaths, excludePaths);
    }

    /**
     * 已成功落盘的产物名列表，新的在前。名称即 {@link #getInterceptorOutput(String)} 的键。
     */
    List<String> interceptorOutputNames();

    /**
     * 按名称取产物文件；不存在或已被清理时返回 null。
     */
    File getInterceptorOutput(String name);
}
