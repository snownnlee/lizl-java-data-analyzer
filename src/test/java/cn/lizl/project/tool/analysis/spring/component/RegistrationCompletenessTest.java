package cn.lizl.project.tool.analysis.spring.component;

import cn.lizl.project.tool.analysis.spring.beans.BeansAutoConfiguration;
import cn.lizl.project.tool.analysis.spring.beans.JavaDataAnalyzerLifecycleHandler;
import cn.lizl.project.tool.analysis.spring.beans.BeansProperties;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登记完整性的回归用例（对应分析报告 P2-7 前半）。
 *
 * <p>历史缺陷：「登记 + 分析」整包丢给单线程 executor，而 {@code getBeanNames} 直接读 map。
 * 于是 {@code refresh()} 一返回就去请求 {@code /beans}，拿到的可能是空列表或残缺列表——
 * 排队的任务还没轮到。登记只是建一个工厂对象，没有理由跟着分析一起异步。</p>
 */
class RegistrationCompletenessTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("执行器被耗时分析占住时，后登记的 Bean 依然立刻可见")
    void shouldRegisterOnCallingThread(@TempDir Path tempDir) throws Exception {
        BeansProperties properties = new BeansProperties();
        properties.setOutputRootDir(tempDir.toString());
        properties.setEagerToAnalyze(true);
        // lazy=false 让分析也排进执行器：单线程被执行器占住，异步登记就必然滞后
        properties.setLazy(false);

        JavaDataAnalyzerLifecycleHandler handler =
                new JavaDataAnalyzerLifecycleHandler(properties::newRunningConfigurer, properties);
        try {
            handler.postProcessAfterInitialization(new HeavyGraph(), "heavy");
            // 等到 "heavy" 出现在列表里，此时执行器正在分析它（这一步新旧实现都能等到）
            waitUntilRegistered(handler, "heavy");

            handler.postProcessAfterInitialization(new FollowUp(), "followUp");

            assertTrue(handler.getBeanNames(false).contains("followUp"),
                    "登记被排到了耗时分析之后：刷新后立刻请求 /beans 会拿到残缺列表");
        } finally {
            handler.destroy();
        }
    }

    @Test
    @DisplayName("上下文刷新完成后，列表已经是完整的")
    void shouldBeCompleteRightAfterRefresh(@TempDir Path tempDir) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("cn.lizl.java-data-analyzer.beans.enable", "true");
        properties.put("cn.lizl.java-data-analyzer.beans.output-root-dir", tempDir.toString());
        properties.put("cn.lizl.java-data-analyzer.server.port", "0");
        properties.put("cn.lizl.java-data-analyzer.beans.eager-to-analyze", "true");
        properties.put("cn.lizl.java-data-analyzer.beans.lazy", "true");

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(BeansAutoConfiguration.class, SampleConfig.class);
        context.refresh();
        try {
            JavaDataAnalyzerLifecycleHandler handler = context.getBean(JavaDataAnalyzerLifecycleHandler.class);

            // 不 sleep、不轮询：refresh 返回时就该是全量
            List<String> beanNames = handler.getBeanNames(false);
            assertTrue(beanNames.contains("sampleAlpha"), "刷新后列表缺少 sampleAlpha，实际：" + beanNames);
            assertTrue(beanNames.contains("sampleBeta"), "刷新后列表缺少 sampleBeta，实际：" + beanNames);
        } finally {
            context.close();
        }
    }

    private void waitUntilRegistered(JavaDataAnalyzerLifecycleHandler handler, String beanName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!handler.getBeanNames(false).contains(beanName) && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, handler.getBeanNames(false).size(), "第一个 Bean 应已登记");
    }

    @Configuration
    static class SampleConfig {

        @Bean
        SampleAlpha sampleAlpha() {
            return new SampleAlpha();
        }

        @Bean
        SampleBeta sampleBeta() {
            return new SampleBeta();
        }
    }

    static class SampleAlpha {

        private String value = "alpha";
    }

    static class SampleBeta {

        private String value = "beta";
    }

    static class FollowUp {

        private String value = "follow-up";
    }

    /** 30 × 30 的对象图，分析耗时可观，足以占住单线程执行器 */
    static class HeavyGraph {

        private final List<Object> list = new LinkedList<>();

        HeavyGraph() {
            for (int i = 0; i < 30; i++) {
                Map<String, Object> map = new HashMap<>();
                for (int j = 0; j < 30; j++) {
                    map.put("k" + j, new TenFields());
                }
                list.add(map);
            }
        }
    }

    @SuppressWarnings("unused")
    static class TenFields {

        private String a = "value-a";

        private String b = "value-b";

        private int c = 1;

        private long d = 2L;

        private double e = 3.0;

        private boolean f = true;

        private byte g = 4;

        private short h = 5;

        private char i = 'x';

        private float j = 6.0f;
    }
}
