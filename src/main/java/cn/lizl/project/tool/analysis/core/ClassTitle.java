package cn.lizl.project.tool.analysis.core;

import java.util.regex.Pattern;

/**
 * 类标题的四个展示片段：修饰符标签、显示名、实例标记、锚点 id。
 *
 * <p>HTML（服务端拼标签）与 HTML2（输出 JSON 由前端拼标签）都要这份口径，抽出来避免漂移：
 * 差异只在「显示名要不要做 HTML 转义」——HTML 侧转，HTML2 侧原样进 JSON 交给 {@code textContent}。
 */
final class ClassTitle {

    /** 类声明头拆分：渲染热路径每类一次，预编译避免 String.split 每次重编译 */
    private static final Pattern CLASS_HEADER_SPLIT = Pattern.compile("(?<=(class|enum|interface|record))\\s+");

    private ClassTitle() {
    }

    /**
     * 按 {@code sourceClass.toGenericString()} 拆出展示片段。
     *
     * <p>前置条件：{@code classModel.sourceClass} 非空。链接类与 info 类构造时传 {@code null}，
     * 本方法依赖「链接类指向的 origin 必为真实模型」这一不变量，不再重复判空。
     *
     * @return {@code [修饰符标签(可 null), 显示名(未转义), 实例标记 "*"(可 null), "#id#"]}
     */
    static String[] of(ClassModel classModel) {
        String classModifierType = null;
        String classDisplayName;
        String classInstanceFlag = classModel.sourceClassInstance != null ? "*" : null;
        String classDisplayId = "#" + classModel.id + "#";

        String classGenericName = ItemTypeNames.of(classModel.sourceClass);
        if (classGenericName == null) {
            classGenericName = classModel.sourceClass.toGenericString();
        }

        String[] contents = CLASS_HEADER_SPLIT.split(classGenericName, 2);
        if (contents.length == 1) {
            classDisplayName = contents[0];
        } else {
            classModifierType = fixClassModifierType(contents[0], classModel.sourceClass.isAnnotation());
            classDisplayName = contents[1];
        }
        return new String[]{classModifierType, classDisplayName, classInstanceFlag, classDisplayId};
    }

    private static String fixClassModifierType(String value, boolean isAnnotation) {
        if (value.endsWith("enum")) {
            return "enum";
        }
        if (value.endsWith("record")) {
            return "record";
        }
        if (value.endsWith("interface")) {
            return isAnnotation ? "@interface" : "interface";
        }
        if (value.endsWith("class")) {
            if (value.contains("abstract")) {
                return "abstract";
            }
            return null;
        }
        return value;
    }
}
