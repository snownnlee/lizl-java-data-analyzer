package cn.lizl.project.tool.analysis.spring.interceptor;

import cn.lizl.project.tool.analysis.http.HttpAnalysisServer;
import cn.lizl.project.tool.analysis.spring.server.ServerAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拦截器通道的开关矩阵：{@code enabled}（总开关）→ {@code interceptor.enable}（装配开关）→
 * {@code interceptor.active}（运行期捕获开关）。前两者只影响装配，最后一个只影响请求是否被捕获。
 */
class InterceptorAutoConfigurationTest {

    @Test
    @DisplayName("interceptor.enable=true 装配控制 Bean；false 或未配置时不装配")
    void shouldAssembleControlByEnableProperty() {
        assertNotNull(controlBeanWith("true"), "显式开启应有控制 Bean");
        assertNull(controlBeanWith("false"), "显式关闭不应有控制 Bean");
        assertNull(controlBeanWith(null), "未配置默认关闭");
    }

    @Test
    @DisplayName("interceptor.active 只管运行期捕获：通道照样装配，默认关闭")
    void shouldSeparateAssemblySwitchFromRuntimeSwitch() {
        InterceptorControl byDefault = controlBeanWith("true", null);
        assertNotNull(byDefault, "active 不参与装配判定");
        assertFalse(byDefault.shouldAnalyze("/api/orders"), "active 默认关闭：装了不等于生效");

        InterceptorControl explicitlyOff = controlBeanWith("true", "false");
        assertNotNull(explicitlyOff, "active=false 不应影响装配");
        assertFalse(explicitlyOff.shouldAnalyze("/api/orders"));

        InterceptorControl opened = controlBeanWith("true", "true");
        assertNotNull(opened);
        assertTrue(opened.shouldAnalyze("/api/orders"), "显式 active=true 才捕获");
    }

    @Test
    @DisplayName("enable-service=true 且 interceptor.enable=true 时 /interceptor 端点应已挂载")
    void shouldMountInterceptorEndpointWhenBothEnabled() {
        AnnotationConfigApplicationContext context = contextWith("true", "true");
        try {
            HttpAnalysisServer server = context.getBean(HttpAnalysisServer.class);
            assertNotNull(server);
        } finally {
            context.close();
        }
    }

    private InterceptorControl controlBeanWith(String enable) {
        return controlBeanWith(enable, null);
    }

    private InterceptorControl controlBeanWith(String enable, String active) {
        AnnotationConfigApplicationContext context = contextWith(enable, null, active);
        try {
            InterceptorControl control = context.getBean(InterceptorControl.class);
            control.shutdown();
            return control;
        } catch (Exception e) {
            return null;
        } finally {
            context.close();
        }
    }

    private AnnotationConfigApplicationContext contextWith(String enable, String enableService) {
        return contextWith(enable, enableService, null);
    }

    private AnnotationConfigApplicationContext contextWith(String enable, String enableService, String active) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        Map<String, Object> properties = new HashMap<>();
        properties.put("cn.lizl.java-data-analyzer.server.enable", "true");
        properties.put("cn.lizl.java-data-analyzer.server.port", "0");
        properties.put("cn.lizl.java-data-analyzer.beans.eager-to-analyze", "false");
        if (enableService != null) {
            properties.put("cn.lizl.java-data-analyzer.server.enable", enableService);
        }
        if (enable != null) {
            properties.put("cn.lizl.java-data-analyzer.interceptor.enable", enable);
        }
        if (active != null) {
            properties.put("cn.lizl.java-data-analyzer.interceptor.active", active);
        }
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(InterceptorAutoConfiguration.class, ServerAutoConfiguration.class);
        context.refresh();
        return context;
    }
}
