package cn.lizl.project.tool.analysis.util;

/**
 * 模板压缩器：只压内联 {@code <style>}/{@code <script>} 段内的注释与首尾空白，不动 HTML 骨架、
 * 不动字符串字面量。
 *
 * <p>模板加载时压一次，之后复用压缩结果，源模板保持可读可维护。
 *
 * <p>公开在 {@code util} 包（叶子包）供两类调用方共用：{@code core.HtmlRender} 压产物模板，
 * {@code http.ClasspathResource.readHtml} 压页面模板。此前的包私有位置在 {@code core}，http 包够不到。
 *
 * <p>刻意不做的事：不删除 {@code :} 前的空格（{@code a :hover} 语义不同）；不合并 JS 行（影响 ASI）；
 * 不动 {@code +} {@code -} 两侧空白（{@code calc()} 依赖它）；字符串字面量逐字符保留。
 */
public final class TemplateCompressor {

    private static final String STYLE = "style";
    private static final String SCRIPT = "script";

    private TemplateCompressor() {
    }

    /**
     * 压缩整个模板中的内联 CSS / JS，HTML 骨架与占位符保持原样。
     *
     * @param html 模板全文
     * @return 压缩后的模板；入参为空时原样返回
     */
    public static String compact(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        StringBuilder out = new StringBuilder(html.length());
        int cursor = 0;
        while (true) {
            int styleStart = indexOfIgnoreCase(html, "<" + STYLE, cursor);
            int scriptStart = indexOfIgnoreCase(html, "<" + SCRIPT, cursor);
            boolean isStyle;
            int start;
            if (styleStart < 0 && scriptStart < 0) {
                out.append(stripLineIndent(html.substring(cursor)));
                return out.toString();
            } else if (styleStart < 0) {
                isStyle = false;
                start = scriptStart;
            } else if (scriptStart < 0) {
                isStyle = true;
                start = styleStart;
            } else {
                isStyle = styleStart < scriptStart;
                start = Math.min(styleStart, scriptStart);
            }

            String tag = isStyle ? STYLE : SCRIPT;
            int openEnd = html.indexOf('>', start);
            int closeStart = openEnd < 0 ? -1 : indexOfIgnoreCase(html, "</" + tag, openEnd);
            if (closeStart < 0) {
                // 标签不完整：不猜测标签结构，只做行首空白压缩
                out.append(stripLineIndent(html.substring(cursor)));
                return out.toString();
            }

            out.append(stripLineIndent(html.substring(cursor, openEnd + 1)));
            String body = html.substring(openEnd + 1, closeStart);
            out.append(isStyle ? compactCss(body) : compactJs(body));
            cursor = closeStart;
        }
    }

    /**
     * 去掉 HTML 骨架每行的行首空白并把 CRLF 归一为 LF。换行本身保留：普通流内容里换行等价于一个空格。
     *
     * <p>{@code <pre>}/{@code <textarea>} 不能这样处理，{@code TemplateCompressorTest} 会断言模板里没有它们。
     */
    public static String stripLineIndent(String html) {
        StringBuilder out = new StringBuilder(html.length());
        int start = 0;
        int length = html.length();
        while (start <= length) {
            int lineEnd = html.indexOf('\n', start);
            boolean hasNewline = lineEnd >= 0;
            if (!hasNewline) {
                lineEnd = length;
            }
            int contentEnd = lineEnd;
            if (contentEnd > start && html.charAt(contentEnd - 1) == '\r') {
                contentEnd--;
            }
            int i = start;
            while (i < contentEnd && (html.charAt(i) == ' ' || html.charAt(i) == '\t')) {
                i++;
            }
            out.append(html, i, contentEnd);
            if (hasNewline) {
                out.append('\n');
                start = lineEnd + 1;
            } else {
                break;
            }
        }
        return out.toString();
    }

    /**
     * CSS：去注释、空白折叠、与结构符号相邻的空白直接删除。
     */
    public static String compactCss(String css) {
        StringBuilder out = new StringBuilder(css.length());
        boolean pendingSpace = false;
        char stringQuote = 0;
        int length = css.length();
        for (int i = 0; i < length; i++) {
            char c = css.charAt(i);

            if (stringQuote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < length) {
                    out.append(css.charAt(++i));
                } else if (c == stringQuote) {
                    stringQuote = 0;
                }
                continue;
            }

            if (c == '/' && i + 1 < length && css.charAt(i + 1) == '*') {
                int commentEnd = css.indexOf("*/", i + 2);
                i = commentEnd < 0 ? length - 1 : commentEnd + 1;
                continue;
            }

            if (Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0 && !isTightBoundary(out.charAt(out.length() - 1));
                continue;
            }

            if (c == '"' || c == '\'') {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(c);
                stringQuote = c;
                continue;
            }

            if (isStructureChar(c)) {
                pendingSpace = false;
                out.append(c);
                continue;
            }

            if (pendingSpace) {
                out.append(' ');
                pendingSpace = false;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * JS：逐行去行首缩进与行尾空白、删整行注释与空行。换行一律保留，避免影响自动分号插入。
     *
     * <p>跨行模板字符串（反引号）内部不做任何修剪：那里的缩进与空行是字面量内容。
     */
    public static String compactJs(String js) {
        String[] lines = js.split("\n", -1);
        StringBuilder out = new StringBuilder(js.length());
        boolean inComment = false;
        boolean inTemplate = false;
        for (String rawLine : lines) {
            String line;
            if (inTemplate) {
                line = stripTrailingCr(rawLine);
            } else {
                line = rawLine.trim();
                if (inComment) {
                    if (line.contains("*/")) {
                        inComment = false;
                        String rest = line.substring(line.indexOf("*/") + 2).trim();
                        if (rest.isEmpty()) {
                            continue;
                        }
                        line = rest;
                    } else {
                        continue;
                    }
                }
                if (line.startsWith("/*")) {
                    int commentEnd = line.indexOf("*/", 2);
                    if (commentEnd < 0) {
                        inComment = true;
                        continue;
                    }
                    line = line.substring(commentEnd + 2).trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                }
                if (line.isEmpty()) {
                    continue;
                }
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
            if (countBackticks(line) % 2 == 1) {
                inTemplate = !inTemplate;
            }
        }
        return out.toString();
    }

    private static int countBackticks(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '`') {
                count++;
            }
        }
        return count;
    }

    private static String stripTrailingCr(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\r' || line.charAt(end - 1) == ' ')) {
            end--;
        }
        return line.substring(0, end);
    }

    /**
     * 与这些字符相邻的空白可安全删除。冒号不在其中：选择器里 {@code :} 前的空白有语义。
     */
    private static boolean isStructureChar(char c) {
        return c == '{' || c == '}' || c == ';' || c == ',' || c == '>';
    }

    private static boolean isTightBoundary(char c) {
        return isStructureChar(c) || c == ':';
    }

    private static int indexOfIgnoreCase(String source, String target, int fromIndex) {
        int max = source.length() - target.length();
        for (int i = Math.max(fromIndex, 0); i <= max; i++) {
            if (source.regionMatches(true, i, target, 0, target.length())) {
                return i;
            }
        }
        return -1;
    }
}
