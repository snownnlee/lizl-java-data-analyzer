package cn.lizl.project.tool.analysis.support;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.api.RenderType;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;

/**
 * 用例公共前置。
 *
 * <p>本库的全局配置放在静态字段里，同一个 JVM 内所有用例共用一份：前一个用例改过排除包、
 * 渲染类型或深度，后一个用例读到的就是被改过的值。所以每个用例执行前都要把全部全局项
 * 显式写回默认值，不能依赖执行顺序。</p>
 *
 * <p>复位一律用 {@code set*} 而不是 {@code add*}：{@code add*} 只追加、没有清除入口，
 * 用它复位只会让历史累积越滚越大（对应分析报告 P3-4）。</p>
 */
public final class TestSupport {

    /**
     * 用例默认输出目录，固定在构建目录内，避免污染用户目录与源码树。
     */
    public static final String TEST_OUTPUT_DIR = "target/test-output";

    private TestSupport() {
    }

    /**
     * 把所有全局配置项复位为出厂默认值。
     *
     * <p>这里必须逐项写全，而不是只改本用例关心的几项——任何一项漏写，都会让上一个用例的
     * 设置悄悄渗进来。
     * </p>
     * 读出某个 configurer 当前生效的排除包。
     *
     * <p>本库没有对外暴露读取这些字段的方法（不为此扩 API），用例按字段名反射取；
     * 字段改名会让用例直接失败，不会静默漏测。
     */
    public static String[] commonExcludePackagesOf(RunningConfigurer configurer) {
        try {
            Object runningConfig = declaredField(configurer.getClass(), "runningConfig").get(configurer);
            return (String[]) declaredField(runningConfig.getClass(), "commonExcludePackages").get(runningConfig);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取排除包失败", e);
        }
    }

    /**
     * runningConfig 声明在祖先类上，必须沿继承链找
     */
    private static java.lang.reflect.Field declaredField(Class<?> type, String name)
            throws ReflectiveOperationException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 继续向上找
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }

    public static void resetGlobalConfig() {
        JavaDataAnalyzer.globalConfigurer()
                .setAnalyzeStaticField(false)
                .setValueDisplayLengthLimit(800)
                .setCollectionsAnalyzingCount(10)
                .setShouldAnalyzeNullValue(true)
                .setAnalyzingMaxDepth(7)
                .setAnalyzingMaxNodeCount(2000)
                .setAnalyzingTimeoutMillis(0)
                .setDefaultToExpand(false)
                .setAnalysisMode(AnalysisMode.AUTO_BY_COMPUTE)
                .setEnableCollectionSimpleView(true)
                .setAddToStringField(false)
                .setMaskSensitiveValues(true)
                .setRenderType(RenderType.HTML)
                .setGlobalOutputRootDir(TEST_OUTPUT_DIR)
                .setThrowExceptionWhenStringClassNotFound(false)
                .setClassLoader(TestSupport.class.getClassLoader())
                .setCommonIncludePackages()
                .setCommonExcludePackagesToDefault()
                .apply();
    }
}
