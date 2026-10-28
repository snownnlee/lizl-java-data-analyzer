package cn.lizl.project.tool.analysis.spring.beans;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 忽略规则判定的回归用例。
 *
 * <p>历史缺陷：{@code shouldIgnore} 在配置了名称正则后直接 {@code return beanName.matches(...)}，
 * 正则不匹配就返回 false，后面的包名判断被整段跳过——只要配了正则，{@code ignoreBeanPackages}
 * 就永久失效。</p>
 */
class BeansPropertiesTest {

    @Test
    @DisplayName("名称正则不匹配时仍继续按包名判断")
    void shouldStillApplyPackageFilterWhenPatternConfigured() {
        BeansProperties properties = new BeansProperties();
        properties.setIgnoreBeanNamePattern("^neverMatch.*");
        properties.setIgnoreBeanPackages(new String[]{getClass().getPackage().getName()});

        assertTrue(properties.shouldIgnore(new SampleBean(), "someBean"),
                "正则不匹配时应继续按包名判断，而不是直接返回 false");
    }

    @Test
    @DisplayName("三条规则各自生效：精确名、正则、包名")
    void shouldApplyEachIgnoreRule() {
        BeansProperties properties = new BeansProperties();
        properties.setIgnoreBeanNames(Collections.singleton("exactBean"));
        properties.setIgnoreBeanNamePattern("^skip.*");
        properties.setIgnoreBeanPackages(new String[]{getClass().getPackage().getName()});

        assertTrue(properties.shouldIgnore(new SampleBean(), "exactBean"), "精确名应命中");
        assertTrue(properties.shouldIgnore(new SampleBean(), "skipMe"), "正则应命中");
        assertTrue(properties.shouldIgnore(new SampleBean(), "otherBean"), "包名应命中");
    }

    @Test
    @DisplayName("未配置任何规则时不忽略")
    void shouldNotIgnoreWithoutRules() {
        BeansProperties properties = new BeansProperties();

        assertFalse(properties.shouldIgnore(new SampleBean(), "anyBean"));
    }

    @SuppressWarnings("unused")
    static class SampleBean {

        private String value = "x";
    }
}
