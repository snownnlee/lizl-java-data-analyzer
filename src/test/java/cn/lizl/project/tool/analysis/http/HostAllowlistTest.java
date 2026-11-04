package cn.lizl.project.tool.analysis.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host 白名单：防 DNS rebinding 让第三方页面的 Origin 与 Host 头一致、绕过跨站判定。
 */
class HostAllowlistTest {

    private static final String PREFIX = HttpAnalysisServer.DEFAULT_PATH_PREFIX;

    private HttpAnalysisServer server;

    @BeforeEach
    void setUp() {
        server = new HttpAnalysisServer().register(new BeansHttpServiceHandler(new EmptyProvider())).setPort(0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("非白名单 Host 的请求应被拒绝（DNS rebinding 防护）")
    void shouldRejectUnknownHost() throws IOException {
        server.start();
        assertEquals(403, statusWithHost("evil.example.com:" + server.getBoundPort()),
                "攻击者域名解析到回环时 Host 头不在白名单，应返回 403");
    }

    @Test
    @DisplayName("回环 Host 正常放行")
    void shouldAllowLoopbackHost() throws IOException {
        server.start();
        assertEquals(200, statusWithHost("127.0.0.1:" + server.getBoundPort()));
    }

    @Test
    @DisplayName("localhost、IPv6 方括号与末尾点归一化后按回环放行，非白名单大小写不敏感")
    void shouldNormalizeHostHeader() throws IOException {
        server.start();
        int port = server.getBoundPort();
        assertEquals(200, statusWithHost("localhost:" + port));
        assertEquals(200, statusWithHost("[::1]:" + port));
        assertEquals(200, statusWithHost("localhost.:" + port));
        assertEquals(403, statusWithHost("EVIL.EXAMPLE.COM:" + port));
    }

    @Test
    @DisplayName("显式配置的 Host 放行，未配置的仍拒绝")
    void shouldAllowConfiguredHost() throws IOException {
        server.setAllowedHosts("proxy.internal", "10.0.0.7").start();
        int port = server.getBoundPort();
        assertEquals(200, statusWithHost("proxy.internal:" + port));
        assertEquals(200, statusWithHost("10.0.0.7:" + port));
        assertEquals(403, statusWithHost("other.internal:" + port));
    }

    @Test
    @DisplayName("Host 头缺失时不因白名单被拒（非浏览器/内部客户端口径）")
    void shouldAllowMissingHost() throws IOException {
        server.start();
        int status = statusWithHost(null);
        assertEquals(200, status, "Host 缺失应放行，实际状态码：" + status);
    }

    @Test
    @DisplayName("回环绑定且无凭证时启动打 WARN")
    void shouldWarnWithoutCredential() throws IOException {
        Logger logger = Logger.getLogger(HttpAnalysisServer.class.getName());
        List<String> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            server.start();
        } finally {
            logger.removeHandler(handler);
        }
        assertTrue(warnings.stream().anyMatch(message -> message.contains("未配置任何凭证")),
                "应提示无凭证风险，实际：" + warnings);
    }

    @Test
    @DisplayName("非回环绑定且无凭证时拒绝启动")
    void shouldRejectNonLoopbackBindingWithoutCredential() {
        server.setBindAddress("0.0.0.0");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, server::start);
        assertTrue(thrown.getMessage().contains("未配置任何凭证"));
        assertFalse(server.isRunning(), "拒绝启动后不应留下半启动实例");
    }

    @Test
    @DisplayName("非回环绑定配了凭证可正常启动")
    void shouldStartNonLoopbackBindingWithCredential() throws IOException {
        server.setBindAddress("0.0.0.0").setToken("s3cr3t").start();
        assertTrue(server.isRunning());
    }

    private int statusWithHost(String hostHeader) throws IOException {
        int port = server.getBoundPort();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            StringBuilder request = new StringBuilder("GET " + PREFIX + "/beans HTTP/1.1\r\n");
            if (hostHeader != null) {
                request.append("Host: ").append(hostHeader).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();
            assertNotNull(statusLine, "应读到 HTTP 状态行");
            return Integer.parseInt(statusLine.split(" ")[1]);
        }
    }

    private static final class EmptyProvider implements AnalysisProvider {

        @Override
        public String getName() {
            return "empty";
        }

        @Override
        public List<String> getBeanNames(boolean rebuild) {
            return Collections.emptyList();
        }

        @Override
        public File getAnalyzedResult(String beanName, boolean rebuild) {
            return null;
        }
    }
}
