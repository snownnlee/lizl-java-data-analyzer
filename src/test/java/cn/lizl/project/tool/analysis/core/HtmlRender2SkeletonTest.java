package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import cn.lizl.project.tool.analysis.support.TestSupport;
import cn.lizl.project.tool.analysis.util.HtmlTreeAssets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML2 数据骨架的良构性、转义与体积收益。
 *
 * <p>HTML2 把「拼标签」从服务端搬到浏览器，代价是产物的正确性不再能靠肉眼扫标签发现：
 * JSON 少一个引号、值里混进 {@code </script>}、某个节点没被写进数据，页面都只是空着或少了几个节点，
 * 而 Java 侧编译、渲染调用全部正常。本用例把这几类失败变成断言。
 */
class HtmlRender2SkeletonTest {

    private static final String DATA_START = "<script type=\"application/json\" id=\""
            + HtmlRender2.DATA_ELEMENT_ID + "\">";

    private static final String CONTAINER_START = "<div id=\"container\">";

    private static final String PARTICLES_START = "<div id=\"particles\">";

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("产物只带数据骨架：正文区为空，节点信息全在 JSON 里")
    void productCarriesJsonSkeletonOnly() {
        String html = render(RenderType.HTML2, new Report());
        String data = extractData(html);

        assertTrue(html.contains(CONTAINER_START + "</div>"),
                "HTML2 产物仍在服务端拼了 DOM，正文区应为空容器");
        assertFalse(data.contains("class_model"), "数据块里出现了 DOM 标记：骨架没起到「只传数据」的作用");
        assertTrue(data.contains("\"type\":\"class\""), "数据块里没有任何类节点");
        assertWellFormedJson(data);
    }

    @Test
    @DisplayName("值里的 <、</script>、引号都必须转义，否则数据块被提前截断或 JSON 解析失败")
    void dangerousCharactersAreEscaped() {
        String html = render(RenderType.HTML2, new Dangerous());
        String data = extractData(html);

        assertFalse(data.contains("<"), "数据块里出现裸 <，值中的 </script> 会截断数据块");
        assertTrue(data.contains("\\u003c/script>"), "值里的 </script> 未被转义");
        assertTrue(data.contains("\\\""), "值里的双引号未转义，JSON 会被截断");
        assertWellFormedJson(data);
        // 数据块必须仍然完整地以 </script> 收尾（截断的话这一句就不成立）
        assertTrue(html.substring(html.indexOf(DATA_START)).contains("</script>"), "数据块没有正常闭合");
    }

    @Test
    @DisplayName("同一批模型：数据骨架远小于服务端拼出的标记")
    void skeletonIsSmallerThanMarkup() {
        String html1 = render(RenderType.HTML, new Report(), new Child());
        String html2 = render(RenderType.HTML2, new Report(), new Child());

        int markupSize = utf8Length(extractMarkup(html1));
        int skeletonSize = utf8Length(extractData(html2));
        int product1 = utf8Length(html1);
        int product2 = utf8Length(html2);
        System.out.println("[HTML2 体积] 正文标记 " + markupSize + " B -> 数据骨架 " + skeletonSize + " B"
                + "；整份产物 " + product1 + " B -> " + product2 + " B");

        assertTrue(skeletonSize * 2 < markupSize,
                "数据骨架没有明显更小：" + skeletonSize + " B vs 标记 " + markupSize + " B");
        // 建树脚本是固定开销（约 8 KB），只有节点多到标签开销超过它时整份产物才更小；
        // 这个样本代表的就是 HTML2 的目标场景——大对象
        assertTrue(product2 < product1 * 0.95,
                "HTML2 的整份产物没有更小：" + product2 + " B vs " + product1 + " B");
    }

    @Test
    @DisplayName("两个通道覆盖同一批节点：标记里的每个节点 id 都要在骨架里出现，反之亦然")
    void bothChannelsCoverTheSameNodes() {
        // 样本覆盖四种节点形态：普通类、继承出来的 [super] 类、共享实例产生的 link 节点、
        // 以及一个字段对应多个类模型时的容器
        Set<String> markupIds = extractIds(extractMarkup(render(RenderType.HTML, new Report(), new Child())));
        Set<String> skeletonIds = extractIds(extractData(render(RenderType.HTML2, new Report(), new Child())));

        assertFalse(markupIds.isEmpty(), "未从标记里解析到任何 id，说明解析口径已与产物脱节");
        Set<String> onlyInMarkup = new LinkedHashSet<>(markupIds);
        onlyInMarkup.removeAll(skeletonIds);
        Set<String> onlyInSkeleton = new LinkedHashSet<>(skeletonIds);
        onlyInSkeleton.removeAll(markupIds);

        assertTrue(onlyInMarkup.isEmpty(), "这些节点只在服务端标记里出现，骨架漏了它们：" + onlyInMarkup);
        assertTrue(onlyInSkeleton.isEmpty(), "这些节点只在骨架里出现，服务端标记里没有：" + onlyInSkeleton);
    }

    @Test
    @DisplayName("产物按同目录文件名引用样式与脚本，不再内联")
    void productReferencesSidecarAssets() {
        String html = render(RenderType.HTML, new Report());
        String html2 = render(RenderType.HTML2, new Report());
        String styleTag = "<link rel=\"stylesheet\" href=\"./"
                + HtmlTreeAssets.servedName(HtmlTreeAssets.STYLE) + "\">";
        String scriptTag = "<script src=\"./"
                + HtmlTreeAssets.servedName(HtmlTreeAssets.INTERACTION) + "\"></script>";

        for (String product : new String[]{html, html2}) {
            assertTrue(product.contains(styleTag), "产物没有引用样式文件，页面会是无样式的裸标签");
            assertTrue(product.contains(scriptTag), "产物没有引用交互脚本，展开/收起与导航会全部失效");
            assertFalse(product.contains("<style>"), "产物里仍内联了样式");
        }
        assertTrue(html2.contains("<script src=\"./" + HtmlTreeAssets.servedName(HtmlTreeAssets.BUILDER) + "\"></script>"),
                "HTML2 没有引用建树脚本，页面会只剩空容器");
        assertFalse(html.contains(HtmlTreeAssets.BUILDER), "HTML 不该引用建树脚本");
    }

    @Test
    @DisplayName("HTML2 走独立渲染器：同一个 configurer 换类型后产物形态跟着变")
    void html2IsWiredToItsOwnRender() {
        RunningConfigurer configurer = JavaDataAnalyzer.runningConfigurer()
                .addAnalyzingObject(new Report());
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();

        configurer.setRenderType(RenderType.HTML).run((OutputContentReceiver) first::append);
        configurer.setRenderType(RenderType.HTML2).run((OutputContentReceiver) second::append);

        assertFalse(first.toString().contains(DATA_START), "HTML 产物不应该是数据骨架形态");
        assertTrue(second.toString().contains(DATA_START), "换到 HTML2 后仍沿用 HTML 渲染器");
    }

    // ---------------------------------------------------------------- 解析

    private static String render(RenderType renderType, Object... analyzingObjects) {
        StringBuilder content = new StringBuilder();
        RunningConfigurer configurer = JavaDataAnalyzer.runningConfigurer()
                .setRenderType(renderType);
        for (Object analyzingObject : analyzingObjects) {
            configurer.addAnalyzingObject(analyzingObject);
        }
        configurer.run((OutputContentReceiver) content::append);
        assertFalse(content.length() == 0, "渲染产物为空：" + renderType);
        return content.toString();
    }

    private static String extractData(String html) {
        int start = html.indexOf(DATA_START);
        assertTrue(start >= 0, "产物里找不到数据块，HTML2 模板占位符缺失或被改名");
        start += DATA_START.length();
        int end = html.indexOf("</script>", start);
        assertTrue(end > start, "数据块没有闭合标签");
        return html.substring(start, end);
    }

    /** 模板里 {@code #container} 到 {@code #particles} 之间就是渲染端拼出来的那棵树 */
    private static String extractMarkup(String html) {
        int start = html.indexOf(CONTAINER_START);
        int end = html.indexOf(PARTICLES_START);
        assertTrue(start >= 0 && end > start, "未定位到正文区，模板结构可能已变");
        return html.substring(start + CONTAINER_START.length(), end);
    }

    /**
     * 收集节点 id：标记侧读 {@code id='...'}，骨架侧读 {@code "id":"..."}。
     *
     * <p>按钮 id（{@code 类id+} 之类）与正文面板 id（{@code 类id-content}）在两个通道里都是推导出来的，
     * 不属于节点本身，比对前先剔除。
     */
    private static Set<String> extractIds(String source) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("id='([^']+)'|\"id\":\"([^\"]+)\"").matcher(source);
        while (matcher.find()) {
            String id = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (!isDerivedId(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static boolean isDerivedId(String id) {
        return id.endsWith("-content") || id.endsWith("+") || id.endsWith("-");
    }

    private static int utf8Length(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * 与实现无关的 JSON 良构校验：字符串闭合、转义合法、括号配平。
     *
     * <p>运行期零依赖，不为一个用例引入 JSON 库；这里只需要判定「不会被前端 JSON.parse 拒绝」。
     */
    private static void assertWellFormedJson(String json) {
        int braceDepth = 0;
        int bracketDepth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                    continue;
                }
                assertTrue(c >= 0x20, "字符串里出现未转义的控制字符，位置 " + i);
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    break;
                case '{':
                    braceDepth++;
                    break;
                case '}':
                    braceDepth--;
                    break;
                case '[':
                    bracketDepth++;
                    break;
                case ']':
                    bracketDepth--;
                    break;
                default:
                    break;
            }
            assertTrue(braceDepth >= 0, "花括号不配平，位置 " + i);
            assertTrue(bracketDepth >= 0, "方括号不配平，位置 " + i);
        }
        assertFalse(inString, "字符串没有闭合");
        assertEquals(0, braceDepth, "花括号数量不配平");
        assertEquals(0, bracketDepth, "方括号数量不配平");
    }

    // ---------------------------------------------------------------- 样本

    @SuppressWarnings("unused")
    static class Report {

        private String reportId = "R-2026-11";

        private String title = "季度报表";

        private Metadata metadata = new Metadata();

        private List<Row> rows = new ArrayList<>();

        /** 元素类型不一致的集合：渲染成「一个字段对应多个类模型」的容器 */
        private List<Object> mixed = new ArrayList<>();

        /** 与 rows 的第一个元素是同一个实例：第二次出现渲染成 link 节点 */
        private Row shared;

        Report() {
            shared = new Row("华东", 12, 345.6, true);
            rows.add(shared);
            rows.add(new Row("华南", 7, 128.0, false));
            mixed.add("text");
            mixed.add(new Row("混合", 1, 2.5, true));
        }
    }

    @SuppressWarnings("unused")
    static class Metadata {

        private String creator = "system";

        private String createdAt = "2026-11-04 10:00:00";

        private String version = "v3";

        private int revision = 7;
    }

    @SuppressWarnings("unused")
    static class Row {

        private String region;

        private int count;

        private double amount;

        private boolean enabled;

        Row(String region, int count, double amount, boolean enabled) {
            this.region = region;
            this.count = count;
            this.amount = amount;
            this.enabled = enabled;
        }
    }

    @SuppressWarnings("unused")
    static class Parent {

        private String parentName = "parent";

        private long parentId = 1L;
    }

    /** 有父类：渲染成 [super] 子节点 */
    @SuppressWarnings("unused")
    static class Child extends Parent {

        private String childName = "child";
    }

    @SuppressWarnings("unused")
    static class Dangerous {

        private String payload = "</script><b>x</b>&\"'";

        private String compare = "a < b > c";

        private List<String> tags = new ArrayList<>();

        Dangerous() {
            tags.add("</SCRIPT>");
        }
    }
}
