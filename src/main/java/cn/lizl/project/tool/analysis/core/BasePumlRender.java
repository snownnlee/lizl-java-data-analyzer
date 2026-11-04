package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.util.CommonUtil;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 静态图渲染基类（PlantUML / Mermaid）：抽出与具体图语法无关的「模型 → 文本」语义层，
 * 以及两类静态图共用的降噪口径。PUML 侧的颜色常量逐项对齐 {@code JavaDataAnalyzer.html} 的
 * CSS 规则，保证同一语义在 HTML 与 PUML_HTML 里同色。
 */
abstract class BasePumlRender extends BaseRender {

    /** 字段名，对齐 HTML {@code .field_model_info_name} */
    protected static final String COLOR_FIELD_NAME = "#00ddff";

    /** 字段类型，对齐 HTML {@code .field_model_info_type} */
    protected static final String COLOR_FIELD_TYPE = "#ffff55";

    /** 字段值，对齐 HTML {@code .field_model_info_value}（白字） */
    protected static final String COLOR_VALUE = "#ffffff";

    /** 等号，对齐 HTML {@code .field_model_info_equal_flag} */
    protected static final String COLOR_EQUAL = "#ff0000";

    /** static / final 修饰符，对齐 HTML {@code .field_model_info_static_flag} 与 {@code _final_flag} */
    protected static final String COLOR_MODIFIER = "#ffaa66";

    /** 集合大小，对齐 HTML {@code .field_model_info_size} */
    protected static final String COLOR_SIZE = "#ff5555";

    /** 类显示名，对齐 HTML {@code .class_model_title_display_name} */
    protected static final String COLOR_CLASS_NAME = "#ffff55";

    /** 实例标记 {@code *}，对齐 HTML {@code .class_model_title_instance_flag} */
    protected static final String COLOR_INSTANCE = "#ff0000";

    /** 关系线标签：语义上就是来源字段名，沿用字段名颜色 */
    protected static final String COLOR_LINK = COLOR_FIELD_NAME;

    /** 警告 / 未解析提示，对齐 HTML {@code .common_info_model} */
    protected static final String COLOR_WARN = "#ff5555";

    /** 目标因深度/预算受限未能展开时的行内标注（PlantUML 与 Mermaid 共用文案） */
    protected static final String MARK_DEPTH_LIMIT = "⚠ 深度受限";

    /** 警告文本折行宽度，避免超长单行把类框撑变形。 */
    protected static final int INFO_WRAP_WIDTH = 72;

    /** 字段值折行宽度，只影响超长值（路径、JSON、SQL）。 */
    protected static final int VALUE_WRAP_WIDTH = 64;

    /** JDK16+ 才有 Class#isRecord；低版本编译时不能直接引用，用反射探测。 */
    private static final Method IS_RECORD = resolveIsRecord();

    protected BasePumlRender(RunningConfig runningConfig, String fileExtension) {
        super(runningConfig, fileExtension);
    }

    @SuppressWarnings("JavaReflectionMemberAccess")
    private static Method resolveIsRecord() {
        try {
            return Class.class.getMethod("isRecord");
        } catch (Exception e) {
            return null;
        }
    }

    protected static boolean isRecord(Class<?> clazz) {
        if (IS_RECORD == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(IS_RECORD.invoke(clazz));
        } catch (Exception e) {
            return false;
        }
    }

    protected static String colorize(String color, String text) {
        return "<color:" + color + ">" + text + "</color>";
    }

    /**
     * 按字符宽度折行，每段独立着色并各自闭合 color 标签：{@code \n} 落在标签内会截断标签，{@code <br>} 无效。
     */
    protected static String wrapAndColorize(String color, String text, int width) {
        return wrapAndColorize(color, text, width, false);
    }

    /**
     * 值专用：先按原文宽度折行，再逐段转义着色。不能先转义再折行，否则 {@code <} 膨胀成 8 个字符会让折行提前。
     */
    protected static String wrapEscapedColorize(String text) {
        return wrapAndColorize(BasePumlRender.COLOR_VALUE, text, BasePumlRender.VALUE_WRAP_WIDTH, true);
    }

    private static String wrapAndColorize(String color, String text, int width, boolean escape) {
        String singleLine = cleanText(text);
        if (singleLine.isEmpty()) {
            return colorize(color, "");
        }
        StringBuilder result = new StringBuilder(singleLine.length() + 32);
        int index = 0;
        while (index < singleLine.length()) {
            int end = Math.min(index + width, singleLine.length());
            if (end < singleLine.length()) {
                int space = singleLine.lastIndexOf(' ', end);
                // 断点太靠前会切出极短行（如值前缀 "= " 后的空格），宁可硬断也不采用
                if (space > index + width / 3) {
                    end = space;
                }
            }
            if (index > 0) {
                result.append('\n').append("  ");
            }
            String segment = singleLine.substring(index, end).trim();
            result.append(colorize(color, escape ? escapeCreole(segment) : segment));
            index = end;
            while (index < singleLine.length() && singleLine.charAt(index) == ' ') {
                index++;
            }
        }
        return result.toString();
    }

    /**
     * 转义字段值里的尖括号，防止用户对象里的 {@code <b>}、{@code <color:red>} 被当成样式解析。
     *
     * <p>用 Unicode 字符转义而非 Creole 的 {@code ~<}：实测后者只转义左尖括号。
     */
    protected static String escapeCreole(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf('<') < 0 && text.indexOf('>') < 0) {
            return text;
        }
        // 必须单次扫描：链式 replace 会把 <U+003C> 自身的 > 再替换一次，产出 <U+003C<U+003E>
        StringBuilder result = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<') {
                result.append("<U+003C>");
            } else if (c == '>') {
                result.append("<U+003E>");
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /** 清洗成员行文本：\n 会截断 Creole 标签，统一替换为可见的 ⏎。 */
    protected static String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                if (result.length() > 0 && result.charAt(result.length() - 1) != '⏎') {
                    result.append('⏎');
                }
                continue;
            }
            if (c == '\t') {
                result.append(' ');
                continue;
            }
            if (c < 0x20 || c == 0x7f) {
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }

    /**
     * 带实例的枚举节点不展开：实例分析时字段行已有 {@code = PAID}，常量清单是冗余；
     * 纯类型分析时字段行没有值，常量清单正是要看的信息。判据取节点自身有无实例，
     * 因为 AUTO 模式下同一张图可能混合两种节点。
     */
    protected static boolean isInstanceEnum(ClassModel classModel) {
        return classModel != null
                && classModel.sourceClass != null
                && classModel.sourceClass.isEnum()
                && classModel.sourceClassInstance != null;
    }

    /** 集合包装类（{@code [List-Item]} 等）在静态图里只会多一层节点与边。 */
    protected static boolean isTransparent(ClassModel classModel) {
        return ItemTypeNames.of(classModel.sourceClass) != null;
    }

    /** enum / abstract class / class 三种关键字，对应 HTML 的修饰符徽标。 */
    protected String classKeyword(ClassModel classModel) {
        Class<?> sourceClass = classModel.sourceClass;
        if (sourceClass.isEnum()) {
            return "enum";
        }
        if (Modifier.isAbstract(sourceClass.getModifiers())) {
            return "abstract class";
        }
        return "class";
    }

    /** record 无原生关键字，退化为版型（stereotype）。 */
    protected String classStereotype(ClassModel classModel) {
        Class<?> sourceClass = classModel.sourceClass;
        if (sourceClass.isEnum() || Modifier.isAbstract(sourceClass.getModifiers())) {
            return "";
        }
        return isRecord(sourceClass) ? " <<record>>" : "";
    }

    /**
     * 类显示名：全限定名 + 实例标记 + 实例区分后缀 + 编号，例 {@code com.test.Big$Order * @2 #C1#}。
     *
     * <p>着色与 HTML 标题栏一致：类型名与编号同色（{@code .class_model_title_display_name}），
     * 实例标记 {@code *} 单独标红（{@code .class_model_title_instance_flag}）。
     *
     * @param instanceSuffix 同一类型存在多个实例时的区分后缀，单实例场景传 null
     */
    protected String classDisplayName(ClassModel classModel, String instanceSuffix) {
        StringBuilder result = new StringBuilder(
                colorize(COLOR_CLASS_NAME, qualifiedDisplayName(classModel)));
        if (classModel.sourceClassInstance != null) {
            result.append(' ').append(colorize(COLOR_INSTANCE, "*"));
            if (instanceSuffix != null) {
                result.append(' ').append(colorize(COLOR_INSTANCE, instanceSuffix));
            }
        }
        result.append(' ').append(colorize(COLOR_CLASS_NAME, "#" + classModel.id + "#"));
        return result.toString();
    }

    /**
     * 节点标题用的类型名：全限定名，内部类保留 {@code $} 写法。字段类型列仍用短名，避免重复包名把类框撑宽。
     */
    protected String qualifiedDisplayName(ClassModel classModel) {
        Class<?> sourceClass = classModel.sourceClass;
        if (sourceClass == null) {
            return "提示";
        }
        String itemTypeName = ItemTypeNames.of(sourceClass);
        if (itemTypeName != null) {
            return itemTypeName;
        }
        return qualifiedNameOf(sourceClass);
    }

    /** 数组类的 {@code getName()} 返回 {@code [Lcom.foo.Bar;}，这里还原成 {@code com.foo.Bar[]}。 */
    private static String qualifiedNameOf(Class<?> clazz) {
        if (clazz.isArray()) {
            return qualifiedNameOf(clazz.getComponentType()) + "[]";
        }
        return clazz.getName();
    }

    /**
     * 字段行：{@code [static] name : Type [final] [= value | [size] [= [v1, v2, ...]]]}，
     * 每段各自着色，与 HTML 的 {@code .field_model_info_*} 规则一一对应。
     *
     * @param renderValue 是否直接展示字段值；{@code false} 表示该字段已展开成子节点、字面量交给
     *                    子节点呈现，此处只补集合大小 {@code [size]}（非容器不显示），若元素走简单
     *                    视图（未建节点但收集了显示值）再补一段 {@code = [元素值...]}；
     *                    {@code true} 时直接展示字面量（枚举也走这一支）
     */
    protected String buildFieldLine(FieldModel fieldModel, boolean renderValue) {
        StringBuilder line = new StringBuilder(64);
        int modifiers = fieldModel.sourceField.getModifiers();
        if (Modifier.isStatic(modifiers)) {
            line.append(colorize(COLOR_MODIFIER, "static")).append(' ');
        }
        line.append(colorize(COLOR_FIELD_NAME, "<b>" + cleanText(fieldModel.getFieldName()) + "</b>"));
        line.append(" : ").append(colorize(COLOR_FIELD_TYPE,
                "<i>" + cleanText(fieldModel.getFieldType()) + "</i>"));
        if (Modifier.isFinal(modifiers)) {
            line.append(' ').append(colorize(COLOR_MODIFIER, "final"));
        }
        Object value = fieldModel.sourceFieldValue;
        if (value != null) {
            // 敏感字段（password/token/secret 等）只挡值不挡结构：报告里渲染 ***，分析与展开照常
            if (runningConfig.maskSensitiveValues && CommonUtil.isSensitiveName(fieldModel.getFieldName())) {
                line.append(' ').append(colorize(COLOR_EQUAL, "=")).append(' ')
                        .append(wrapEscapedColorize(CommonUtil.SENSITIVE_VALUE_MARK));
                return line.toString();
            }
            if (renderValue) {
                // 等号与值分色：HTML 里 "=" 是红色标记，值本身是白字
                line.append(' ').append(colorize(COLOR_EQUAL, "=")).append(' ')
                        .append(wrapEscapedColorize(valueText(value)));
            } else {
                int size = sizeOf(value);
                if (size >= 0) {
                    // 圆括号会被当作方法签名，把该行挤进方法段并在字段中间插分隔线；方括号无此语义
                    line.append(' ').append(colorize(COLOR_SIZE, "[" + size + "]"));
                }
                appendSimpleViewValues(line, fieldModel, size >= 0);
            }
        }
        return line.toString();
    }

    protected String valueText(Object value) {
        return cleanText(CommonUtil.getStringFrom(value, runningConfig.valueDisplayLengthLimit));
    }

    /** 该字段是否直接展示字面量值：没有展开成子节点，或本身就是枚举。 */
    protected static boolean renderFieldValueDirectly(FieldModel fieldModel) {
        return fieldModel.fieldTypeClassesModels.isEmpty()
                || fieldModel.sourceField.getType().isEnum()
                || fieldModel.sourceFieldValue instanceof Enum;
    }

    /** 该字段是否有目标因超出解析深度/预算而未能展开。 */
    protected static boolean isDepthLimited(FieldModel fieldModel) {
        for (ClassModel fieldTypeClassModel : fieldModel.fieldTypeClassesModels) {
            if (fieldTypeClassModel instanceof ClassModel.InfoClassModel) {
                return true;
            }
        }
        return false;
    }

    /**
     * 简单视图下集合元素值内联：{@code tags : List<String> [2] = [alpha, beta]}。分析不建节点是设计
     * 行为，但被包排除规则挡掉的元素值必须在这里可见。格式沿用 size → = → 值的分段着色。
     */
    private void appendSimpleViewValues(StringBuilder line, FieldModel fieldModel, boolean hasSize) {
        List<String> values = fieldModel.simpleViewElementValues();
        if (values == null || values.isEmpty()) {
            return;
        }
        if (hasSize) {
            line.append(' ');
        }
        line.append(colorize(COLOR_EQUAL, "=")).append(' ');
        line.append(wrapEscapedColorize("[" + simpleViewInlineText(values) + "]"));
    }

    protected static int sizeOf(Object value) {
        if (value.getClass().isArray()) {
            return Array.getLength(value);
        }
        if (value instanceof Collection) {
            return ((Collection<?>) value).size();
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).size();
        }
        return -1;
    }

    /** 同类型多实例的出现序号：按遍历顺序编号，保证同一输入多次渲染结果稳定可 diff。 */
    protected static final class InstanceOrdinals {

        /** 类型 → 实例 → 出现序号；实例按引用区分（IdentityHashMap），避免大对象 equals 代价 */
        private final Map<Class<?>, Map<Object, Integer>> ordinals = new HashMap<>();

        InstanceOrdinals(ClassModel[] rootClassModels) {
            for (ClassModel rootClassModel : rootClassModels) {
                collectInstances(rootClassModel);
            }
        }

        private void collectInstances(ClassModel classModel) {
            if (classModel == null || classModel instanceof ClassModel.LinkClassModel) {
                return;
            }
            if (classModel.sourceClass != null && classModel.sourceClassInstance != null) {
                Map<Object, Integer> perClass = ordinals
                        .computeIfAbsent(classModel.sourceClass, key -> new IdentityHashMap<>());
                if (!perClass.containsKey(classModel.sourceClassInstance)) {
                    perClass.put(classModel.sourceClassInstance, perClass.size() + 1);
                }
            }
            for (FieldModel fieldModel : classModel.fieldModels) {
                for (ClassModel fieldTypeClassModel : fieldModel.fieldTypeClassesModels) {
                    collectInstances(fieldTypeClassModel);
                }
            }
            collectInstances(classModel.superClassModel);
        }

        /** 同类型多实例时返回 {@code @1} / {@code @2} 这类区分后缀，否则返回 null。 */
        String instanceSuffix(ClassModel classModel) {
            if (classModel.sourceClass == null || classModel.sourceClassInstance == null) {
                return null;
            }
            Map<Object, Integer> perClass = ordinals.get(classModel.sourceClass);
            if (perClass == null || perClass.size() <= 1) {
                return null;
            }
            Integer ordinal = perClass.get(classModel.sourceClassInstance);
            return ordinal == null ? null : "@" + ordinal;
        }
    }

    /** 类别名 → 声明顺序：用于区分「向后展开」与「回指已出现的节点」。 */
    protected static final class DeclarationOrder {

        private final Map<String, Integer> order = new HashMap<>();

        void markDeclared(String classAlias) {
            if (!order.containsKey(classAlias)) {
                order.put(classAlias, order.size());
            }
        }

        /** 目标比源更早声明则为环引用，画虚线；任一方未声明时按实线处理。 */
        boolean isBackEdge(String fromAlias, String targetAlias) {
            Integer from = order.get(fromAlias);
            Integer target = order.get(targetAlias);
            return from != null && target != null && target < from;
        }
    }
}
