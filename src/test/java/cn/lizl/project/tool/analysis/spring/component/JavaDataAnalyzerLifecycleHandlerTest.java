package cn.lizl.project.tool.analysis.spring.component;

import cn.lizl.project.tool.analysis.spring.beans.JavaDataAnalyzerLifecycleHandler;
import cn.lizl.project.tool.analysis.spring.beans.BeansProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring 侧并发安全的回归用例。
 *
 * <p>历史缺陷：{@code RunningConfigurer} 被注册成单例 Bean 并注入 handler，所有分析任务共享同一个实例。
 * 它的待分析对象列表是可变状态（{@code addAnalyzingObject} 累积、{@code clear} 清空），
 * 两个线程同时分析不同 bean 时，A 刚加进去的对象会被 B 的 {@code clear} 抹掉，
 * 结果是产物内容与实际 bean 名错配。</p>
 */
class JavaDataAnalyzerLifecycleHandlerTest {

    @Test
    @DisplayName("工厂方法每次调用都给出独立实例（handler 只调一次并全程复用）")
    void shouldCreateIndependentConfigurerPerCall() {
        BeansProperties properties = new BeansProperties();

        assertNotSame(properties.newRunningConfigurer(), properties.newRunningConfigurer(),
                "工厂语义是每次给一个干净实例，复用由调用方决定");
    }

    @Test
    @DisplayName("并发取不同对象的产物时，各自产物只含自己的类名，且全程复用同一个 configurer")
    void shouldNotMixUpConcurrentAnalyses(@TempDir Path tempDir) throws Exception {
        BeansProperties properties = new BeansProperties();
        properties.setOutputRootDir(tempDir.toString());
        properties.setEagerToAnalyze(true);
        properties.setLazy(true);

        AtomicInteger created = new AtomicInteger();
        JavaDataAnalyzerLifecycleHandler handler = new JavaDataAnalyzerLifecycleHandler(() -> {
            created.incrementAndGet();
            return properties.newRunningConfigurer();
        }, properties);
        try {
            handler.postProcessAfterInitialization(new Alpha(), "alpha");
            handler.postProcessAfterInitialization(new Beta(), "beta");
            waitUntilRegistered(handler);

            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<File>> futures = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    futures.add(pool.submit(() -> handler.getAnalyzedResult("alpha", false)));
                    futures.add(pool.submit(() -> handler.getAnalyzedResult("beta", false)));
                }
                for (Future<File> future : futures) {
                    File file = future.get(20, TimeUnit.SECONDS);
                    assertNotNull(file, "并发取结果不应返回 null");
                    assertTrue(file.isFile(), "并发取结果应产出真实文件");
                }
            } finally {
                pool.shutdownNow();
            }

            String alphaHtml = read(handler.getAnalyzedResult("alpha", false));
            String betaHtml = read(handler.getAnalyzedResult("beta", false));

            assertTrue(alphaHtml.contains("Alpha"), "alpha 的产物应包含自己的类名");
            assertFalse(alphaHtml.contains("Beta"), "alpha 的产物混进了 beta 的分析结果");
            assertTrue(betaHtml.contains("Beta"), "beta 的产物应包含自己的类名");
            assertFalse(betaHtml.contains("Alpha"), "beta 的产物混进了 alpha 的分析结果");

            assertEquals(1, created.get(), "handler 应只创建一个 configurer 并复用，而不是每次分析新建");
        } finally {
            handler.destroy();
        }
    }

    private void waitUntilRegistered(JavaDataAnalyzerLifecycleHandler handler) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (handler.getBeanNames(false).size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, handler.getBeanNames(false).size(), "两个对象应已完成登记");
    }

    private String read(File file) throws Exception {
        assertNotNull(file);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    static class Alpha {

        private String value = "alpha-only";
    }

    @SuppressWarnings("unused")
    static class Beta {

        private String value = "beta-only";
    }
}
