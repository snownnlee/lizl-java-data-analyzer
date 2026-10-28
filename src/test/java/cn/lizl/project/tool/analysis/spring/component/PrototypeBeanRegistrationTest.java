package cn.lizl.project.tool.analysis.spring.component;

import cn.lizl.project.tool.analysis.spring.beans.JavaDataAnalyzerLifecycleHandler;
import cn.lizl.project.tool.analysis.spring.beans.BeansProperties;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 原型作用域 Bean 登记的回归用例（对应分析报告 P3-7）。
 *
 * <p>历史缺陷：{@code beanNameToAnalysisFactoryMap} 按 Bean 名登记，碰到同名就直接复用已有工厂，
 * 而工厂里强引用着当时那个实例。原型作用域每次 {@code getBean()} 都是新实例、名字却不变，
 * 于是第一个实例被永久钉在 map 里（连同它的整个字段图），后续按名称分析出来的也一直是它。</p>
 */
class PrototypeBeanRegistrationTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("原型 Bean 再次创建后，按名称分析的是最新实例")
    void shouldAnalyzeLatestPrototypeInstance(@TempDir Path tempDir) throws Exception {
        BeansProperties properties = new BeansProperties();
        properties.setOutputRootDir(tempDir.toString());
        properties.setEagerToAnalyze(true);
        // 登记时先不分析，确保两次登记都落定后再触发，避免把异步时序混进断言
        properties.setLazy(true);

        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("proto", BeanDefinitionBuilder
                .genericBeanDefinition(PrototypeSample.class)
                .setScope("prototype")
                .getBeanDefinition());

        JavaDataAnalyzerLifecycleHandler handler =
                new JavaDataAnalyzerLifecycleHandler(properties::newRunningConfigurer, properties);
        handler.setBeanFactory(beanFactory);
        try {
            PrototypeSample first = beanFactory.getBean("proto", PrototypeSample.class);
            first.value = "instance-1";
            handler.postProcessAfterInitialization(first, "proto");

            PrototypeSample second = beanFactory.getBean("proto", PrototypeSample.class);
            second.value = "instance-2";
            assertNotSame(first, second, "前提：原型作用域每次拿到新实例");
            handler.postProcessAfterInitialization(second, "proto");

            File resultFile = waitForContent(handler, "instance-2");
            String html = read(resultFile);

            assertTrue(html.contains("instance-2"), "按名称分析出来的不是最新实例，而是 " + resultFile.getName());
            assertFalse(html.contains("instance-1"), "产物里混着上一个原型实例的值");
            assertEquals(1, handler.getBeanNames(false).size(), "同名原型 Bean 只应占一个登记位");
        } finally {
            handler.destroy();
        }
    }

    /** 登记是异步的，等到产物反映新实例（或超时）为止 */
    private File waitForContent(JavaDataAnalyzerLifecycleHandler handler, String expected) throws Exception {
        File resultFile = null;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            resultFile = handler.getAnalyzedResult("proto", false);
            if (resultFile != null && read(resultFile).contains(expected)) {
                return resultFile;
            }
            Thread.sleep(10);
        }
        assertNotNull(resultFile, "3 秒内没有产出任何结果");
        return resultFile;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    static class PrototypeSample {

        private String value = "unset";
    }
}
