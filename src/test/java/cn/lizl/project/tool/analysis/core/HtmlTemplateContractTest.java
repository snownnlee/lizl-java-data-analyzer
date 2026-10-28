package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.util.ResourcePaths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染端（{@link HtmlRender}）与模板 {@code JavaDataAnalyzer.html} 之间的契约校验。
 *
 * <p>背景：类名的定义天然分居两处——Java 产出标签与类名，CSS/JS 在模板里按类名取样式与行为。
 * 任一侧改名而另一侧没跟上都不会报错，页面只静默变成无样式或按钮失灵，而现有渲染用例
 * 只断言「内容非空 / 含某些值」，抓不到这类漂移。本用例把两处的对应关系变成断言：</p>
 *
 * <ol>
 *   <li>常量 → CSS：{@link HtmlRender.Tokens} 里每个类名都必须在模板 {@code <style>} 中有定义，
 *       否则产物无样式；</li>
 *   <li>CSS → 常量：模板里凡以渲染端前缀命名的类，必须在常量里有声明，否则是拼错或死样式；</li>
 *   <li>JS 不依赖类名：{@code <script>} 段不得出现任何渲染端类名，交互只准读 data-* 属性；</li>
 *   <li>JS 依赖的属性与 {@code data-name} 取值，必须在常量表里有对应项。</li>
 * </ol>
 */
class HtmlTemplateContractTest {

    /** 渲染端自有类名的前缀：只有这些开头的 CSS 类才要求「必须有 Java 声明」。 */
    private static final String[] RENDERER_PREFIXES = {"class_model", "root_class_model", "super_class_model",
            "link_class_model", "common_info_model", "field_", "field_model"};

    private static String styleSection;

    private static String scriptSection;

    @BeforeAll
    static void loadTemplate() throws IOException {
        String template = readTemplate();
        styleSection = requireSection(template, "<style>", "</style>");
        scriptSection = requireSection(template, "<script>", "</script>");
    }

    @Test
    @DisplayName("常量里的每个类名都必须在模板 CSS 中有定义，否则产物无样式")
    void everyTokenClassShouldBeStyled() {
        Set<String> cssClasses = cssClasses();
        Set<String> missing = new TreeSet<>();
        for (String tokenClass : tokenClasses()) {
            if (!cssClasses.contains(tokenClass)) {
                missing.add(tokenClass);
            }
        }
        assertTrue(missing.isEmpty(), "以下类名由渲染端产出但模板 CSS 未定义，产物会是无样式的元素：" + missing);
    }

    @Test
    @DisplayName("模板里渲染端前缀的类必须全部有常量声明，否则是拼错或死样式")
    void everyRendererClassInTemplateShouldBeDeclared() {
        Set<String> tokenClasses = tokenClasses();
        Set<String> undeclared = new TreeSet<>();
        for (String cssClass : cssClasses()) {
            if (isRendererClass(cssClass) && !tokenClasses.contains(cssClass)) {
                undeclared.add(cssClass);
            }
        }
        assertTrue(undeclared.isEmpty(),
                "模板 CSS 里出现了未在 HtmlRender.Tokens 声明的渲染端类名（拼写漂移或死样式）：" + undeclared);
    }

    @Test
    @DisplayName("模板 JS 不得引用任何渲染端类名，交互只能依赖 data-* 属性")
    void scriptShouldNotDependOnRendererClasses() {
        Set<String> leaked = new TreeSet<>();
        for (String tokenClass : tokenClasses()) {
            if (scriptSection.contains(tokenClass)) {
                leaked.add(tokenClass);
            }
        }
        assertTrue(leaked.isEmpty(),
                "模板 JS 里引用了渲染端类名：" + leaked + "；类名只应服务样式，交互请改读 data-* 属性");
    }

    @Test
    @DisplayName("JS 读取的属性必须来自常量表，避免渲染端与模板各自记一份属性名")
    void scriptShouldOnlyReadDeclaredAttributes() {
        Set<String> declared = new LinkedHashSet<>(Arrays.asList(
                HtmlRender.Tokens.ATTR_EXPANDABLE,
                HtmlRender.Tokens.ATTR_ROOT,
                HtmlRender.Tokens.ATTR_DATA_CLASS_ID,
                HtmlRender.Tokens.ATTR_DATA_NAME,
                HtmlRender.Tokens.ATTR_DATA_KIND,
                HtmlRender.Tokens.ATTR_SUPPORT_CONTROL));

        Set<String> used = attributesReadByScript();
        Set<String> unknown = new TreeSet<>(used);
        unknown.removeAll(declared);
        assertTrue(unknown.isEmpty(),
                "JS 读取了未在 HtmlRender.Tokens 声明的属性（渲染端可能根本没输出它）：" + unknown);
        assertFalse(used.isEmpty(), "未解析到任何属性读取，说明本用例的解析口径已与模板脱节，需更新");
    }

    @Test
    @DisplayName("JS 依赖的 data-name 取值必须与常量一致")
    void scriptShouldUseDeclaredControlNames() {
        Set<String> used = new TreeSet<>();
        Matcher matcher = Pattern.compile("name === '([^']+)'").matcher(scriptSection);
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        Set<String> declared = new TreeSet<>(Arrays.asList(
                HtmlRender.Tokens.CONTROL_EXPAND,
                HtmlRender.Tokens.CONTROL_COLLAPSE,
                HtmlRender.Tokens.CONTROL_EXPAND_ALL,
                HtmlRender.Tokens.CONTROL_COLLAPSE_ALL));
        assertFalse(used.isEmpty(), "未解析到 data-name 取值比较，说明解析口径已与模板脱节");
        assertTrue(declared.containsAll(used), "JS 用了常量表之外的 data-name 取值：" + used);
    }

    /**
     * 渲染端用 {@code onclick} 调用的模板函数，在模板里没有任何自身调用点。
     *
     * <p>这类函数最容易被"清理未引用代码"误删，删了不会报错，只是按钮点了没反应。
     * 这里断言它们确实定义在模板 &lt;script&gt; 里。</p>
     */
    @Test
    @DisplayName("渲染端 onclick 调用的函数必须在模板中定义")
    void everyFunctionCalledFromRenderedMarkupShouldExist() {
        String[] functions = {HtmlRender.Tokens.FN_TOGGLE, HtmlRender.Tokens.FN_NAVIGATE};
        Set<String> missing = new TreeSet<>();
        for (String function : functions) {
            if (!Pattern.compile("function\\s+" + function + "\\s*\\(").matcher(scriptSection).find()) {
                missing.add(function);
            }
        }
        assertTrue(missing.isEmpty(),
                "渲染端会调用这些函数，但模板里没有定义（被当作未引用代码删掉了？）：" + missing);
    }

    // ---------------------------------------------------------------- 解析

    private static Set<String> tokenClasses() {
        Set<String> classes = new LinkedHashSet<>();
        for (java.lang.reflect.Field field : HtmlRender.Tokens.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!field.getName().startsWith("ROOT") && !field.getName().startsWith("SUPER")
                    && !field.getName().startsWith("CLASS") && !field.getName().startsWith("LINK")
                    && !field.getName().startsWith("INFO") && !field.getName().startsWith("TITLE")
                    && !field.getName().startsWith("FIELD")) {
                continue;
            }
            try {
                Object value = field.get(null);
                if (value instanceof String) {
                    classes.add((String) value);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("无法读取常量 " + field.getName(), e);
            }
        }
        assertFalse(classes.isEmpty(), "未反射到任何类名常量，说明常量命名或结构已变，需更新本用例");
        return classes;
    }

    private static Set<String> cssClasses() {
        Set<String> classes = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\\.([a-zA-Z_][a-zA-Z0-9_]*)").matcher(styleSection);
        while (matcher.find()) {
            classes.add(matcher.group(1));
        }
        return classes;
    }

    private static boolean isRendererClass(String cssClass) {
        for (String prefix : RENDERER_PREFIXES) {
            if (cssClass.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> attributesReadByScript() {
        Set<String> attributes = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(?:hasAttribute|getAttribute)\\('([^']+)'\\)").matcher(scriptSection);
        while (matcher.find()) {
            attributes.add(matcher.group(1));
        }
        return attributes;
    }

    private static String readTemplate() throws IOException {
        String resourceName = ResourcePaths.html("JavaDataAnalyzer.html");
        try (InputStream inputStream = HtmlTemplateContractTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull(inputStream, "classpath 里应存在 " + resourceName);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = inputStream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String requireSection(String template, String open, String close) {
        int start = template.indexOf(open);
        int end = template.indexOf(close, start + open.length());
        assertTrue(start >= 0 && end > start, "模板中缺少片段：" + open + " … " + close);
        return template.substring(start + open.length(), end);
    }
}
