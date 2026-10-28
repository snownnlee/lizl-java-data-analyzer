package cn.lizl.project.tool.analysis.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CommonUtil.read} 与安全辅助方法用例。
 *
 * <p>历史缺陷（对应 v2.7.6 修复）：{@code read} 旧实现用 {@code available()} 定长读——
 * 它只是「当前不阻塞可读的字节数」，对进程错误流/网络流常只返回部分，一次 {@code read}
 * 少读时剩余内容被静默丢掉。修复后统一走缓冲循环，本用例用「available() 撒谎」的流验证。
 */
class CommonUtilReadTest {

    /** available() 声称只有 3 字节、且一次 read(byte[]) 只吐一部分的流：模拟进程错误流/网络流 */
    static final class LyingStream extends InputStream {

        private final byte[] data;
        private int position;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        LyingStream(String content) {
            this.data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public int available() {
            // 撒谎：永远只承认 3 字节可读（旧实现会据此只读 3 字节就返回）
            return Math.min(3, data.length - position);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            // 一次最多吐 5 字节，逼出「多次 read 才能读完」的路径
            int chunk = Math.min(length, Math.min(5, data.length - position));
            System.arraycopy(data, position, buffer, offset, chunk);
            position += chunk;
            return chunk;
        }

        @Override
        public int read() {
            return position >= data.length ? -1 : (data[position++] & 0xFF);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @Test
    @DisplayName("available() 撒谎 + 分块少读时，read 仍应读出完整内容（旧实现会丢数据）")
    void shouldReadFullContentWhenAvailableLies() {
        String content = "0123456789abcdefghijklmnopqrstuvwxyz";

        String result = CommonUtil.read(new LyingStream(content), java.nio.charset.StandardCharsets.UTF_8, false);

        assertEquals(content, result, "少读的剩余内容被静默丢掉了");
    }

    @Test
    @DisplayName("close=true 时读完关闭流，close=false 时保持打开")
    void shouldCloseStreamAccordingToFlag() {
        LyingStream closeable = new LyingStream("data");
        CommonUtil.read(closeable, java.nio.charset.StandardCharsets.UTF_8, true);
        assertTrue(closeable.closed.get(), "close=true 应关闭流");

        LyingStream keepOpen = new LyingStream("data");
        CommonUtil.read(keepOpen, java.nio.charset.StandardCharsets.UTF_8, false);
        assertFalse(keepOpen.closed.get(), "close=false 不应关闭流");
    }

    @Test
    @DisplayName("空流返回空字符串")
    void shouldReturnEmptyStringForEmptyStream() {
        assertEquals("", CommonUtil.read(new ByteArrayInputStream(new byte[0]),
                java.nio.charset.StandardCharsets.UTF_8, false));
    }

    @Test
    @DisplayName("sanitizeFileName 替换 cmd 元字符与非法字符，保留中文与常见安全字符")
    void shouldSanitizeUnsafeFileNameCharacters() {
        assertEquals("a？b？c？d？e", CommonUtil.sanitizeFileName("a&b^c%d!e"),
                "& ^ % ! 等 cmd 元字符应被替换（防文件名进入 shell 命令行时被二次解释）");
        assertEquals("bad？name？1", CommonUtil.sanitizeFileName("bad/name:1"),
                "路径分隔符与 Windows 非法字符应被替换");
        assertEquals("类型结构图-2026_10", CommonUtil.sanitizeFileName("类型结构图-2026_10"),
                "中文、字母数字与 - _ . 应原样保留");
    }

    @Test
    @DisplayName("isUnsafeFileNameSegment 识别 . 与 .. 目录段")
    void shouldDetectDotSegments() {
        assertTrue(CommonUtil.isUnsafeFileNameSegment(".."));
        assertTrue(CommonUtil.isUnsafeFileNameSegment("."));
        assertFalse(CommonUtil.isUnsafeFileNameSegment("normal-dir"));
        assertFalse(CommonUtil.isUnsafeFileNameSegment(""));
    }

    @Test
    @DisplayName("isSensitiveName 大小写不敏感子串匹配敏感字段名")
    void shouldDetectSensitiveNames() {
        assertTrue(CommonUtil.isSensitiveName("password"));
        assertTrue(CommonUtil.isSensitiveName("userPassword"));
        assertTrue(CommonUtil.isSensitiveName("AUTH_TOKEN"));
        assertTrue(CommonUtil.isSensitiveName("clientSecret"));
        assertTrue(CommonUtil.isSensitiveName("Authorization"));
        assertTrue(CommonUtil.isSensitiveName("apikey"));

        assertFalse(CommonUtil.isSensitiveName("userName"));
        assertFalse(CommonUtil.isSensitiveName("count"));
        assertFalse(CommonUtil.isSensitiveName(null));
        assertFalse(CommonUtil.isSensitiveName(""));
    }

    @Test
    @DisplayName("isSensitiveName 不误伤普通业务名")
    void shouldNotMatchPlainNamesContainingNoSensitivePart() {
        assertFalse(CommonUtil.isSensitiveName("total"));
        assertFalse(CommonUtil.isSensitiveName("signatureAlgorithm"));
    }
}
