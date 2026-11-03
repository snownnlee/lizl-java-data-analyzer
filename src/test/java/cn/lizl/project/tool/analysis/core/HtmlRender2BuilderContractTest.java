package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.util.HtmlTemplates;
import cn.lizl.project.tool.analysis.util.HtmlTreeAssets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTML2 的「模板 + 建树脚本」与渲染端常量之间的契约校验。
 *
 * <p>HTML2 把拼标签搬到前端，类名与属性名因此横跨三处：{@link HtmlRender.Tokens} 声明、
 * {@link HtmlTreeAssets#BUILDER} 使用、{@link HtmlTreeAssets#STYLE} 取样式。
 * 任一处改名都不会报错——页面只是变成无样式、按钮点的没反应、导航跳不动，所以全部落成断言。
 */
class HtmlRender2BuilderContractTest {

    /** 与 {@code HtmlTemplateContractTest} 同口径：只有这些前缀算「渲染端自有类名」 */
    private static final String[] RENDERER_PREFIXES = {"class_model", "root_class_model", "super_class_model",
            "link_class_model", "common_info_model", "field_", "field_model"};

    private static String template;

    private static String builder;

    @BeforeAll
    static void loadResources() {
        template = HtmlTemplates.load(HtmlRender2.OUTPUT_TEMPLATE_NAME);
        builder = HtmlTreeAssets.text(HtmlTreeAssets.BUILDER);
    }

    @Test
    @DisplayName("模板里的五个占位符都必须在，缺一个就是产物少一整块")
    void templateShouldKeepAllPlaceholders() {
        String[] placeholders = {"${title}", "${styleFile}", "${scriptFile}", "${data}", "${builderFile}"};
        Set<String> missing = new TreeSet<>();
        for (String placeholder : placeholders) {
            if (countOf(template, placeholder) != 1) {
                missing.add(placeholder);
            }
        }
        assertTrue(missing.isEmpty(), "模板占位符缺失或重复：" + missing);
    }

    @Test
    @DisplayName("数据块标记必须与建树脚本读的 id 一致，否则脚本读不到数据、页面空白")
    void dataElementIdShouldMatchBuilder() {
        String expected = "<script type=\"application/json\" id=\"" + HtmlRender2.DATA_ELEMENT_ID + "\">";
        assertTrue(template.contains(expected), "模板里没有约定的数据块标记：" + expected);
        assertTrue(builder.contains("getElementById('" + HtmlRender2.DATA_ELEMENT_ID + "')"),
                "建树脚本读取的 id 与模板不一致");
    }

    @Test
    @DisplayName("建树脚本只用常量表声明过的渲染端类名，拼错就是无样式或按钮失灵")
    void builderShouldOnlyUseDeclaredClasses() {
        Set<String> declared = tokenClasses();
        Set<String> undeclared = new TreeSet<>();
        Matcher matcher = Pattern.compile("'([^']*)'").matcher(builder);
        while (matcher.find()) {
            for (String token : matcher.group(1).split("\\s+")) {
                if (isRendererClass(token) && !declared.contains(token)) {
                    undeclared.add(token);
                }
            }
        }
        assertTrue(undeclared.isEmpty(),
                "建树脚本用了 HtmlRender.Tokens 未声明的类名（真渲染端不会产出它）：" + undeclared);
    }

    @Test
    @DisplayName("建树脚本只写常量表声明过的 data-* 属性")
    void builderShouldOnlyWriteDeclaredAttributes() {
        Set<String> declared = new LinkedHashSet<>(Arrays.asList(
                HtmlRender.Tokens.ATTR_EXPANDABLE,
                HtmlRender.Tokens.ATTR_ROOT,
                HtmlRender.Tokens.ATTR_DATA_CLASS_ID,
                HtmlRender.Tokens.ATTR_DATA_NAME,
                HtmlRender.Tokens.ATTR_DATA_KIND,
                HtmlRender.Tokens.ATTR_SUPPORT_CONTROL));
        Set<String> unknown = new TreeSet<>();
        Matcher matcher = Pattern.compile("setAttribute\\('([^']+)'").matcher(builder);
        while (matcher.find()) {
            String attribute = matcher.group(1);
            if (!attribute.startsWith("data-") && !attribute.equals(HtmlRender.Tokens.ATTR_SUPPORT_CONTROL)) {
                continue;
            }
            if (!declared.contains(attribute)) {
                unknown.add(attribute);
            }
        }
        assertTrue(unknown.isEmpty(), "建树脚本写了未声明的属性（渲染端可能根本没产出过它）：" + unknown);
    }

    @Test
    @DisplayName("建树脚本的 data-name 取值与交互脚本的入口函数名必须取自常量表")
    void builderShouldUseDeclaredControlNamesAndFunctions() {
        String[] controlNames = {
                HtmlRender.Tokens.CONTROL_EXPAND, HtmlRender.Tokens.CONTROL_COLLAPSE,
                HtmlRender.Tokens.CONTROL_EXPAND_ALL, HtmlRender.Tokens.CONTROL_COLLAPSE_ALL};
        Set<String> missingControls = new TreeSet<>();
        for (String controlName : controlNames) {
            if (!builder.contains("'" + controlName + "'")) {
                missingControls.add(controlName);
            }
        }
        assertTrue(missingControls.isEmpty(), "建树脚本没产出这些 data-name 取值，按钮点了不会有效果："
                + missingControls);

        assertTrue(builder.contains(HtmlRender.Tokens.FN_TOGGLE),
                "控制按钮的 onclick 没有指向交互脚本的处理函数");
        assertTrue(builder.contains(HtmlRender.Tokens.FN_NAVIGATE),
                "标题/链接的 onclick 没有指向交互脚本的导航入口");
    }

    @Test
    @DisplayName("两份模板共用的骨架节点必须一致：功能按钮、正文容器、粒子层")
    void bothTemplatesShouldShareTheSameSkeleton() {
        String html1 = HtmlTemplates.load("JavaDataAnalyzer.html");
        String[] anchors = {"<div id=\"functionArea\">", "<div id=\"container\"", "<div id=\"particles\">"};
        for (String anchor : anchors) {
            assertTrue(template.contains(anchor), "HTML2 模板缺少骨架节点：" + anchor);
            assertTrue(html1.contains(anchor), "HTML1 模板缺少骨架节点：" + anchor);
        }
    }

    // ---------------------------------------------------------------- 解析

    private static Set<String> tokenClasses() {
        Set<String> classes = new LinkedHashSet<>();
        for (java.lang.reflect.Field field : HtmlRender.Tokens.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String name = field.getName();
            if (!name.startsWith("ROOT") && !name.startsWith("SUPER") && !name.startsWith("CLASS")
                    && !name.startsWith("LINK") && !name.startsWith("INFO") && !name.startsWith("TITLE")
                    && !name.startsWith("FIELD")) {
                continue;
            }
            try {
                Object value = field.get(null);
                if (value instanceof String) {
                    classes.add((String) value);
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("无法读取常量 " + name, e);
            }
        }
        assertFalse(classes.isEmpty(), "未反射到任何类名常量，说明常量命名或结构已变，需更新本用例");
        return classes;
    }

    private static boolean isRendererClass(String candidate) {
        for (String prefix : RENDERER_PREFIXES) {
            if (candidate.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
