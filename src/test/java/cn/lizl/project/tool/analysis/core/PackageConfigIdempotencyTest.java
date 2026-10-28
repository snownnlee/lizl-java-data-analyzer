package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;
import cn.lizl.project.tool.analysis.support.TestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * 包名配置 add 操作的幂等性用例（对应分析报告 P3-4 后半）。
 *
 * <p>历史缺陷：{@code addCommon*Packages} 直接做数组拼接，已在列表里的包会被再追加一次。
 * 而 {@code add*} 系列没有清除入口，调用方按「每次分析前补一遍配置」的写法反复调用时，
 * 数组会无限增长——每个被分析的类都要遍历这个数组判包名，后期既费内存又拖慢分析。</p>
 */
class PackageConfigIdempotencyTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetGlobalConfig();
    }

    @Test
    @DisplayName("重复 add 同一个排除包，全局配置数组不增长")
    void shouldNotGrowGlobalExcludePackages() {
        JavaDataAnalyzer.globalConfigurer().setCommonExcludePackages("java.").apply();

        for (int i = 0; i < 5; i++) {
            JavaDataAnalyzer.globalConfigurer().addCommonExcludePackages("com.example.demo").apply();
        }

        assertArrayEquals(new String[]{"java.", "com.example.demo"}, GlobalConfig.commonExcludePackages,
                "重复 add 同一个包不应产生重复项");
    }

    @Test
    @DisplayName("重复 add 同一个纳入包，全局配置数组不增长")
    void shouldNotGrowGlobalIncludePackages() {
        JavaDataAnalyzer.globalConfigurer().setCommonIncludePackages("cn.a.").apply();

        JavaDataAnalyzer.globalConfigurer()
                .addCommonIncludePackages("cn.b.")
                .addCommonIncludePackages("cn.a.")
                .addCommonIncludePackages("cn.b.")
                .apply();

        assertArrayEquals(new String[]{"cn.a.", "cn.b."}, GlobalConfig.commonIncludePackages,
                "去重的同时要保持首次出现的顺序");
    }

    @Test
    @DisplayName("运行配置上重复 add 同样幂等，且不去掉不同的包")
    void shouldNotGrowRunningConfigPackages() {
        RunningConfigurer configurer = JavaDataAnalyzer.runningConfigurer()
                .setCommonExcludePackages("java.")
                .addCommonExcludePackages("cn.x.")
                .addCommonExcludePackages("cn.x.")
                .addCommonExcludePackages("cn.y.");

        assertArrayEquals(new String[]{"java.", "cn.x.", "cn.y."},
                ((BaseRunningConfigurer) configurer).runningConfig.commonExcludePackages,
                "单次分析的配置也应去重，且不误删不同的包");
    }
}
