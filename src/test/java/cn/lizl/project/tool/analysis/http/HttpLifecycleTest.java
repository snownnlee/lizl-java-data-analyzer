package cn.lizl.project.tool.analysis.http;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.support.TestSupport;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生命周期联动：server → handler → provider 三层的 onStart/onStop 顺序、回滚与重复启停。
 */
class HttpLifecycleTest {

    /** 契约要求 getPathPrefixes 返回固定集合，这里做成常量 */
    private static final Collection<String> PREFIXES = Collections.singletonList("/stub");

    @TempDir
    Path tempDir;

    private final List<String> events = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 全局配置是静态字段，用例之间必须显式复位
        TestSupport.resetGlobalConfig();
        events.clear();
    }

    @Test
    @DisplayName("服务启停驱动处理器生命周期：启动正序、停止反序，且可重复启停")
    void shouldDriveHandlerLifecycleInOrder() throws IOException {
        HttpAnalysisServer server = new HttpAnalysisServer()
                .register(new RecordingHandler("a", events))
                .register(new RecordingHandler("b", events))
                .setPort(0);
        try {
            server.start();
            assertEquals(Arrays.asList("a.onStart", "b.onStart"), events);

            server.stop();
            assertEquals(Arrays.asList("a.onStart", "b.onStart", "b.onStop", "a.onStop"), events);

            server.start();
            assertEquals(Arrays.asList("a.onStart", "b.onStart", "b.onStop", "a.onStop",
                    "a.onStart", "b.onStart"), events, "停止后应能再次启动并重新回调 onStart");
        } finally {
            server.stop();
        }

        assertFalse(server.isRunning());
        assertEquals("b.onStop", events.get(events.size() - 2), "最后一次停止同样按反序");
        assertEquals("a.onStop", events.get(events.size() - 1));
    }

    @Test
    @DisplayName("某个处理器 onStart 抛异常：已启动的按反序回滚，服务不进入运行状态")
    void shouldRollbackHandlerStartFailure() {
        HttpAnalysisServer server = new HttpAnalysisServer()
                .register(new RecordingHandler("a", events))
                .register(new FailingHandler("boom", events))
                .setPort(0);

        IllegalStateException error = assertThrows(IllegalStateException.class, server::start);

        assertEquals("boom", error.getMessage());
        assertFalse(server.isRunning(), "启动失败不应留下运行中的服务");
        assertEquals(Arrays.asList("a.onStart", "boom.onStart", "a.onStop"), events,
                "已启动的处理器要按反序收尾，失败的处理器本身不进回滚列表");
    }

    @Test
    @DisplayName("beans 处理器把生命周期同步给 provider：服务停止即释放 provider 资源")
    void shouldDriveProviderLifecycleThroughHandler() throws IOException {
        RecordingProvider provider = new RecordingProvider("manual", events);
        HttpAnalysisServer server = new HttpAnalysisServer()
                .register(new BeansHttpServiceHandler(provider))
                .setPort(0);
        try {
            server.start();
            assertEquals(Collections.singletonList("manual.onStart"), events);

            server.stop();
            assertEquals(Arrays.asList("manual.onStart", "manual.onStop"), events,
                    "server.stop() 应一路传到 provider，无需手工 close");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("运行期增删容器：新容器立即启动，下线容器立即停止，同名覆盖会停掉旧容器")
    void shouldFollowLifecycleOnProviderAddAndRemove() throws IOException {
        RecordingProvider first = new RecordingProvider("first", events);
        BeansHttpServiceHandler handler = new BeansHttpServiceHandler(first);
        HttpAnalysisServer server = new HttpAnalysisServer().register(handler).setPort(0);
        try {
            server.start();
            assertEquals(Collections.singletonList("first.onStart"), events);

            RecordingProvider second = new RecordingProvider("second", events);
            handler.addProvider(second);
            assertEquals("second.onStart", last(), "运行期新容器应立即 start");

            handler.removeProvider("second");
            assertEquals("second.onStop", last(), "下线容器应立即 stop");

            handler.addProvider(new RecordingProvider("first", events));
            assertEquals("first.onStop", last(), "同名覆盖要停掉被替换的旧容器");
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("SimpleAnalysisProvider 停止后仍可同步分析，重新启动后继续可用")
    void shouldSupportStopAndRestartForSimpleProvider() {
        try (SimpleAnalysisProvider provider = new SimpleAnalysisProvider("manual",
                JavaDataAnalyzer.runningConfigurer().setOutputRootDir(tempDir.toString()))) {
            Sample sample = new Sample();
            sample.value = "demo";
            provider.add("sample", sample);
            File first = provider.getAnalyzedResult("sample", false);
            assertNotNull(first);

            provider.onStop();
            assertEquals(Collections.singletonList("sample"), provider.getBeanNames(true),
                    "停止状态下 rebuild 只返回名单，不抛异常");
            assertNotNull(provider.getAnalyzedResult("sample", true), "停止后仍可同步重算");

            provider.onStart();
            assertNotNull(provider.getAnalyzedResult("sample", true), "重新启动后仍可分析");
            assertTrue(provider.getBeanNames(false).contains("sample"));
        }
    }

    private String last() {
        return events.get(events.size() - 1);
    }

    /** 会记录回调的处理器桩 */
    private static class RecordingHandler implements HttpServiceHandler {

        private final String name;

        private final List<String> events;

        RecordingHandler(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public String getEntryPath() {
            return "/" + name;
        }

        @Override
        public Collection<String> getPathPrefixes() {
            return PREFIXES;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String unavailableMessage() {
            return "unavailable";
        }

        @Override
        public void handle(HttpExchange exchange, String relativePath) {
            // 生命周期用例不发请求
        }

        @Override
        public void onStart() {
            events.add(name + ".onStart");
        }

        @Override
        public void onStop() {
            events.add(name + ".onStop");
        }
    }

    /** onStart 必定失败的处理器，用于验证启动回滚 */
    private static final class FailingHandler extends RecordingHandler {

        FailingHandler(String name, List<String> events) {
            super(name, events);
        }

        @Override
        public void onStart() {
            super.onStart();
            throw new IllegalStateException("boom");
        }
    }

    /** 会记录回调的 provider 桩 */
    private static final class RecordingProvider implements AnalysisProvider {

        private final String name;

        private final List<String> events;

        RecordingProvider(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public List<String> getBeanNames(boolean rebuild) {
            return Collections.emptyList();
        }

        @Override
        public File getAnalyzedResult(String beanName, boolean rebuild) {
            return null;
        }

        @Override
        public void onStart() {
            events.add(name + ".onStart");
        }

        @Override
        public void onStop() {
            events.add(name + ".onStop");
        }
    }

    /** 供 SimpleAnalysisProvider 分析的最小对象 */
    static class Sample {

        String value = "demo";
    }
}
