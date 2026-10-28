package cn.lizl.project.tool.analysis.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TemplateCompressor} 的行为约束。
 *
 * <p>模板压缩是纯空白级改写，一旦误删语义字符（选择器空格、{@code calc} 里的空白、
 * 字符串内容），产物页面会静默变形且不影响编译，所以必须用"压缩前后等价"来守。</p>
 */
class TemplateCompressorTest {

    private static final String TEMPLATE_NAME = ResourcePaths.html("JavaDataAnalyzer.html");

    private static final String[] JS_FUNCTIONS = {
            "changeDisplayForClassDiv", "collapseAll", "collapseClassDiv",
            "createParticles", "describeNavTarget", "expandAll", "expandClassDiv", "highlightAndMoveTo",
            "naviToTarget", "setClassDivVisible", "setNaviHash", "showNotification",
            "switchEffectsLevel", "writeHash"
    };

    private static String readTemplate() throws Exception {
        InputStream in = TemplateCompressorTest.class.getClassLoader().getResourceAsStream(TEMPLATE_NAME);
        assertNotNull(in, "模板资源缺失：" + TEMPLATE_NAME);
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        in.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 与实现无关的等价判定：把"两个词字符之间的空白"折成一个空格，其余空白丢弃。
     * 这样 {@code a b} 与 {@code ab} 能被判为不等，而缩进、换行、逗号两侧空白不影响判定。
     *
     * <p>字符串（含反引号模板字符串）内部逐字符比对——压缩器若动了字面量内容，这里必然报错。
     * CRLF 先归一为 LF：浏览器解析 HTML 时本来就会做这个归一。</p>
     */
    private static String normalize(String source) {
        String text = source.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(text.length());
        char quote = 0;
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    out.append(text.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 1;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                // 引号前的空格无意义（font-family: "x" 与 font-family:"x" 等价）
                pendingSpace = false;
                out.append(c);
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace && isWordChar(out.charAt(out.length() - 1)) && isWordChar(c)) {
                out.append(' ');
            }
            pendingSpace = false;
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '%' || c == '.' || c == '#';
    }

    @Test
    @DisplayName("模板压缩后体积明显下降")
    void compactReducesSize() throws Exception {
        String original = readTemplate();
        String compacted = TemplateCompressor.compact(original);

        int before = original.getBytes(StandardCharsets.UTF_8).length;
        int after = compacted.getBytes(StandardCharsets.UTF_8).length;
        System.out.println("[模板压缩] " + before + " B -> " + after + " B（-"
                + String.format("%.1f", (1 - (double) after / before) * 100) + "%）");

        assertTrue(after < before * 0.8, "压缩后体积未明显下降：" + after + " / " + before);
    }

    @Test
    @DisplayName("压缩前后除空白与注释外完全等价")
    void compactKeepsSemantics() throws Exception {
        String original = readTemplate();
        String compacted = TemplateCompressor.compact(original);

        assertEquals(normalize(original), normalize(compacted), "压缩改动的不只是空白/注释");
    }

    @Test
    @DisplayName("危险构造：选择器冒号前空白、calc 空白、字符串内空白")
    void compactKeepsRiskyConstructs() {
        String css = "a :hover { color : red; width: calc(100% - 10px); content: \"x  y\"; }\n"
                + "/* note */\n"
                + "b>c, d  e { margin: 0 !important; }";
        String out = TemplateCompressor.compactCss(css);

        assertTrue(out.contains("a :hover"), "选择器后代空格被误删：" + out);
        assertTrue(out.contains("calc(100% - 10px)"), "calc 内空白被误删：" + out);
        assertTrue(out.contains("\"x  y\""), "字符串内空白被误删：" + out);
        assertTrue(out.contains("!important"), "!important 丢失：" + out);
        assertFalse(out.contains("/*"), "注释未清除：" + out);
        assertTrue(out.contains("b>c"), "子选择器两侧空白未清除：" + out);
        assertTrue(out.contains("d e") && !out.contains("d  e"), "后代选择器应折叠为单个空格：" + out);
    }

    @Test
    @DisplayName("JS 只逐行修剪，不合并任何行")
    void compactJsKeepsLineBreaks() {
        String js = "function f() {\n    const a = 1\n    return a\n}\n/* x */\n\n//keep\n";
        String out = TemplateCompressor.compactJs(js);

        assertTrue(out.contains("const a = 1\nreturn a"), "删除了换行，可能改变 ASI 结果：" + out);
        assertFalse(out.startsWith("/*"), "注释行未清除：" + out);
        assertTrue(out.contains("//keep"), "行内注释被误删（保守策略应保留）：" + out);
    }

    @Test
    @DisplayName("占位符与对外暴露的 JS 函数名保持完整")
    void compactKeepsPlaceholdersAndFunctions() throws Exception {
        String compacted = TemplateCompressor.compact(readTemplate());

        assertEquals(1, countOf(compacted, "${title}"), "${title} 占位符数量异常");
        assertEquals(1, countOf(compacted, "${content}"), "${content} 占位符数量异常");
        for (String function : JS_FUNCTIONS) {
            assertTrue(compacted.contains("function " + function), "JS 函数丢失：" + function);
        }
        assertTrue(compacted.contains("\"Microsoft YaHei UI\""), "字体名（含空格的字符串）被破坏");
        assertTrue(compacted.contains("class_model_title_control_button"), "CSS 类名被破坏");
    }

    @Test
    @DisplayName("跨行模板字符串内部一个字符都不改")
    void compactKeepsTemplateLiteralContent() {
        String js = "const a = `\n    one\n\n    two\n`;\nconst b = 1;\n";
        String out = TemplateCompressor.compactJs(js);

        assertTrue(out.contains("    one\n\n    two"), "模板字符串内容被改写：" + out);
        assertTrue(out.contains("const b = 1;"), "模板字符串之后的代码被影响：" + out);
    }

    @Test
    @DisplayName("HTML 骨架行首空白被去掉，且不残留 CR")
    void compactStripsLineIndent() throws Exception {
        String compacted = TemplateCompressor.compact(readTemplate());

        assertFalse(compacted.contains("\r"), "CRLF 未归一为 LF");
        String skeleton = compacted
                .replaceAll("(?s)<style>.*?</style>", "<style/>")
                .replaceAll("(?s)<script>.*?</script>", "<script/>");
        for (String line : skeleton.split("\n", -1)) {
            assertFalse(line.startsWith(" ") || line.startsWith("\t"), "行首仍有空白：" + line);
        }
    }

    @Test
    @DisplayName("模板不得引入 <pre>/<textarea>（行首空白压缩的前提）")
    void templateHasNoWhiteSpaceSensitiveTags() throws Exception {
        String lower = readTemplate().toLowerCase();
        assertFalse(lower.contains("<pre"), "模板新增了 <pre>，行首空白压缩需重新评估");
        assertFalse(lower.contains("<textarea"), "模板新增了 <textarea>，行首空白压缩需重新评估");
    }

    private static int countOf(String source, String token) {
        int count = 0;
        int index = source.indexOf(token);
        while (index >= 0) {
            count++;
            index = source.indexOf(token, index + token.length());
        }
        return count;
    }
}
