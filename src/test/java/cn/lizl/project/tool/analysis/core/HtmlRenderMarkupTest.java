package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.util.ResourcePaths;
import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染产物标记的良构性校验。
 *
 * <p>起因（2026-10-25）：删按钮上的 {@code data-ripple} 属性时，连带删掉了 {@code data-name}
 * 的闭合引号，产物变成 {@code data-name='+>}。浏览器把后续标签一路吞进这个属性值，
 * DOM 结构散掉、页面整体错位，但编译通过、渲染用例（只断言"内容非空"）全绿，
 * 只有人眼能发现。本用例把"标签内的引号必须成对"变成断言。</p>
 */
class HtmlRenderMarkupTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("渲染产物中的标签引号必须成对，避免属性吞掉后续标记")
    void tagQuotesShouldBeBalanced() {
        String html = renderSample();
        String withoutCodeBlocks = stripCodeBlocks(html);
        List<String> broken = collectBrokenTags(withoutCodeBlocks);
        assertTrue(broken.isEmpty(), "以下标签的引号未闭合（会吞掉后续标记，导致 DOM 结构错乱）：" + broken);
    }

    /**
     * 展开/收起按钮嵌在类标题的 {@code <pre onclick="setNaviHash">} 内，点击会冒泡到标题触发导航，
     * 而导航会沿祖先链把刚收起的内容重新展开——表现为「点收起没反应」，且只在导航的 100ms
     * 防抖窗口外才复现（所以看起来是"有时失效"）。这里守住两个必要条件：按钮把 event 传进去、
     * 处理函数里拦掉冒泡。
     */
    @Test
    @DisplayName("控制按钮必须阻止冒泡，否则收起会被标题的导航重新展开")
    void controlButtonShouldStopPropagation() {
        String html = renderSample();
        assertTrue(html.contains("changeDisplayForClassDiv(this, event)"),
                "按钮 onclick 未把 event 传给处理函数，无法阻止冒泡");

        String template = readTemplate();
        String handler = template.substring(template.indexOf("function changeDisplayForClassDiv"));
        String body = handler.substring(0, handler.indexOf("function ", 10));
        assertTrue(body.contains("stopPropagation()"),
                "changeDisplayForClassDiv 未调用 stopPropagation，点收起会被标题导航立刻展开");
    }

    private static String readTemplate() {
        try (java.io.InputStream in = HtmlRenderMarkupTest.class.getClassLoader()
                .getResourceAsStream(ResourcePaths.html("JavaDataAnalyzer.html"))) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("模板读取失败", e);
        }
    }

    private static String renderSample() {
        StringBuilder content = new StringBuilder();
        JavaDataAnalyzer.runningConfigurer()
                .setAnalysisMode(AnalysisMode.ONLY_CLASS)
                .addAnalyzingObject(new Holder())
                .run((OutputContentReceiver) content::append);
        String html = content.toString();
        assertFalse(html.isEmpty(), "渲染产物为空");
        return html;
    }

    /** 模板里的 CSS/JS 含有 {@code <} 比较运算与大量引号，不属于标记范围，先剥掉。 */
    private static String stripCodeBlocks(String html) {
        return html.replaceAll("(?s)<style>.*?</style>", "").replaceAll("(?s)<script>.*?</script>", "");
    }

    /**
     * 逐标签扫描：每个引号必须在本标签内闭合。
     *
     * <p>不能只数引号奇偶——{@code data-name='+>} 这种漏闭合会让引号态延续到下一个标签，
     * 由后续引号"配平"，奇偶检测就失效了（实测确实漏过）。这里要求闭合引号必须出现在
     * 下一个 {@code <} 之前，命中即判该标签坏了。</p>
     */
    private static List<String> collectBrokenTags(String html) {
        List<String> broken = new ArrayList<>();
        int length = html.length();
        int i = 0;
        while (i < length && broken.size() < 5) {
            if (html.charAt(i) != '<') {
                i++;
                continue;
            }
            int j = i + 1;
            boolean bad = false;
            while (j < length) {
                char c = html.charAt(j);
                if (c == '\'' || c == '"') {
                    int close = html.indexOf(c, j + 1);
                    if (close < 0 || html.lastIndexOf('<', close) > j) {
                        bad = true;
                        break;
                    }
                    j = close + 1;
                    continue;
                }
                if (c == '>') {
                    break;
                }
                if (c == '<') {
                    bad = true;
                    break;
                }
                j++;
            }
            if (bad || j >= length) {
                broken.add(html.substring(i, Math.min(length, i + 70)));
            }
            i = j + 1;
        }
        return broken;
    }

    @SuppressWarnings("unused")
    static class Holder {

        private String name = "a";

        private List<Inner> items = new ArrayList<>();

        Holder() {
            items.add(new Inner());
            items.add(new Inner());
        }
    }

    @SuppressWarnings("unused")
    static class Inner {

        private int value = 1;
    }
}
