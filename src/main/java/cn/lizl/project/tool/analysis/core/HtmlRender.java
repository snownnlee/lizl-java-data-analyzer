package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.util.CommonUtil;
import cn.lizl.project.tool.analysis.util.HtmlTemplates;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Pattern;

class HtmlRender extends BaseRender {

    /** 类声明头拆分：渲染热路径每类一次，预编译避免 String.split 每次重编译 */
    private static final Pattern CLASS_HEADER_SPLIT = Pattern.compile("(?<=(class|enum|interface|record))\\s+");

    private static final String FILE_EXTENSION = ".html";

    private static final String OUTPUT_TEMPLATE_NAME = JavaDataAnalyzer.class.getSimpleName() + FILE_EXTENSION;

    private static final String TITLE_PLACEHOLDER = "${title}";

    private static final String CONTENT_PLACEHOLDER = "${content}";

    HtmlRender(RunningConfig runningConfig) {
        super(runningConfig, FILE_EXTENSION);
    }

    @Override
    public String render(ClassModel[] classModels) {
        StringBuilder content = new StringBuilder();
        for (ClassModel classModel : classModels) {
            renderClassModel(classModel, true, false, content);
        }
        return HtmlTemplates.fill(HtmlTemplates.load(OUTPUT_TEMPLATE_NAME),
                new String[]{TITLE_PLACEHOLDER, CONTENT_PLACEHOLDER},
                new String[]{buildTitle(classModels), content.toString()});
    }

    private String buildTitle(ClassModel[] classModels) {
        return escapeContent(Stream.of(classModels)
                .map(ClassModel::getClassSimpleName)
                .collect(Collectors.joining("、", "【", "】")));
    }

    private void renderClassModel(ClassModel classModel, boolean isRoot, boolean isSuper, StringBuilder result) {
        if (classModel instanceof ClassModel.InfoClassModel) {
            renderInfoClassModel((ClassModel.InfoClassModel) classModel, result);
            return;
        }
        if (classModel instanceof ClassModel.LinkClassModel) {
            renderLinkClassModel((ClassModel.LinkClassModel) classModel, isSuper, result);
            return;
        }
        String[] classGenericName = buildClassModelDisplayName(classModel);
        result.append("<div")
                .append(" id='").append(classModel.id).append("'")
                .append(" class='").append(isRoot ? Tokens.ROOT : (isSuper ? Tokens.SUPER : Tokens.CLASS)).append("'")
                .append(Tokens.ATTR_EXPANDABLE_TRUE)
                .append(Tokens.ATTR_DATA_KIND_CLASS);
        if (isRoot) {
            result.append(Tokens.ATTR_ROOT_TRUE);
        }
        result.append(">");
        renderClassGenericName(classGenericName, classModel.id, isSuper, false, classModel.fieldModels.isEmpty(), classModel.isComplex, result);
        renderClassFieldsAndSuperClass(classModel, result);
        result.append("</div>");
    }

    private void renderInfoClassModel(ClassModel.InfoClassModel infoClassModel, StringBuilder result) {
        result.append("<div id='").append(infoClassModel.id)
                .append("' class='").append(Tokens.CLASS).append(' ').append(Tokens.INFO).append("'")
                .append(Tokens.ATTR_DATA_KIND_CLASS).append(">")
                .append("<pre>").append(escapeContent(infoClassModel.info)).append("</pre>")
                .append("</div>");
    }

    private void renderClassFieldsAndSuperClass(ClassModel classModel, StringBuilder result) {
        result.append("<div id='").append(classModel.id).append("-content'")
                .append(" class='").append(Tokens.CLASS_CONTENT).append("'")
                .append(runningConfig.defaultToExpand ? "" : " style='display:none;'")
                .append(' ').append(Tokens.ATTR_SUPPORT_CONTROL).append(">");
        for (FieldModel fieldModel : classModel.fieldModels) {
            renderFieldModel(fieldModel, result);
        }
        if (classModel.superClassModel != null) {
            renderClassModel(classModel.superClassModel, false, true, result);
        }
        result.append("</div>");
    }

    private void renderLinkClassModel(ClassModel.LinkClassModel linkClassModel, boolean isSuper, StringBuilder result) {
        String[] classGenericName = buildClassModelDisplayName(linkClassModel.originClassModel);
        if (isSuper) {
            result.append("<div class='").append(Tokens.SUPER).append("'>");
        }
        result.append("<div")
                .append(" id='").append(linkClassModel.id).append("'")
                .append(" class='").append(Tokens.LINK).append("'")
                .append(">");
        renderLinkClassGenericName(classGenericName, linkClassModel.id, linkClassModel.originClassModel.id, isSuper, result);
        result.append("</div>");
        if (isSuper) {
            result.append("</div>");
        }
    }

    /**
     * 拼出类标题的四个展示片段：修饰符标签、显示名、实例标记、锚点 id。
     *
     * <p>前置条件：{@code classModel.sourceClass} 非空。链接类与 info 类构造时传 {@code null}，本方法
     * 依赖「链接类指向的 origin 必为真实模型」这一不变量，不再重复判空。
     */
    private String[] buildClassModelDisplayName(ClassModel classModel) {
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
            classDisplayName = escapeContent(contents[0]);
        } else {
            classModifierType = fixClassModifierType(contents[0], classModel.sourceClass.isAnnotation());
            classDisplayName = escapeContent(contents[1]);
        }

        return new String[]{classModifierType, classDisplayName, classInstanceFlag, classDisplayId};
    }

    private String fixClassModifierType(String value, boolean isAnnotation) {
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

    private void renderFieldModel(FieldModel fieldModel, StringBuilder result) {
        if (fieldModel instanceof FieldModel.InfoFieldModel) {
            renderInfoFieldModel((FieldModel.InfoFieldModel) fieldModel, result);
            return;
        }
        int fieldModifiers = fieldModel.sourceField.getModifiers();
        String staticFlag = Modifier.isStatic(fieldModifiers) ? "static" : null;
        String finalFlag = Modifier.isFinal(fieldModifiers) ? "final" : null;
        String fieldName = fieldModel.getFieldName();
        String fieldType = fieldModel.getFieldType();
        result.append("<div")
                .append(" id='").append(fieldModel.id).append("'")
                .append(" class='").append(Tokens.FIELD).append("'")
                .append(Tokens.ATTR_DATA_KIND_FIELD)
                .append(">");
        boolean renderValue = fieldModel.fieldTypeClassesModels.isEmpty()
                || fieldModel.sourceField.getType().isEnum()
                || fieldModel.sourceFieldValue instanceof Enum;
        renderFieldNameTypeAndValue(fieldModel.id, staticFlag, finalFlag, fieldName, fieldType, fieldModel.sourceFieldValue, renderValue, fieldModel.simpleViewElementValues(), result);
        renderFieldClasses(fieldModel, result);
        result.append("</div>");
    }

    private static void renderInfoFieldModel(FieldModel.InfoFieldModel infoFieldModel, StringBuilder result) {
        result.append("<div id='").append(infoFieldModel.id)
                .append("' class='").append(Tokens.FIELD).append(' ').append(Tokens.INFO).append("'")
                .append(Tokens.ATTR_DATA_KIND_FIELD).append(">")
                .append("<pre class='").append(Tokens.FIELD_INFO).append("'>")
                .append(escapeContent(infoFieldModel.info)).append("</pre>")
                .append("</div>");
    }

    private void renderFieldClasses(FieldModel fieldModel, StringBuilder result) {
        int fieldClassSize = fieldModel.fieldTypeClassesModels.size();
        if (fieldClassSize == 0) {
            return;
        }
        if (fieldClassSize == 1) {
            renderClassModel(fieldModel.fieldTypeClassesModels.get(0), false, false, result);
            return;
        }
        result.append("<div class='").append(Tokens.FIELD_CONTAINER).append("'").append(Tokens.ATTR_EXPANDABLE_TRUE).append(">");
        renderClassGenericName(null, fieldModel.id, false, false, false, true, result);

        result.append("<div class='").append(Tokens.FIELD_PANEL).append("'")
                .append(' ').append(Tokens.ATTR_SUPPORT_CONTROL)
                .append(runningConfig.defaultToExpand ? "" : " style='display:none'")
                .append(">");
        for (int i = 0; i < fieldClassSize; i++) {
            renderClassModel(fieldModel.fieldTypeClassesModels.get(i), false, false, result);
        }
        result.append("</div>");

        result.append("</div>");
    }

    /**
     * 链接类（该对象/类型已在别处展开）的标题，点击后跳到目标类。
     *
     * <p>onclick 只调 {@code setNaviHash}：模板里的导航函数没有形参、只读当前 hash，直接传目标 id 会被丢弃。
     */
    private void renderLinkClassGenericName(String[] classGenericName, String linkClassId, String originClassId, boolean isSuper, StringBuilder result) {
        result.append("<a class='").append(Tokens.LINK_CONTAINER)
                .append("' href='javascript:' onclick='").append(Tokens.FN_NAVIGATE)
                .append("(\"#").append(originClassId).append("\")'>");
        renderClassGenericName(classGenericName, linkClassId, isSuper, true, false, false, result);
        result.append("</a>");
    }

    private void renderClassGenericName(String[] classGenericName, String classId,
                                        boolean isSuper, boolean isLinkClass, boolean isEmpty, boolean isComplex,
                                        StringBuilder result) {
        if (isLinkClass) {
            result.append("<pre>");
        } else {
            result.append("<pre class='").append(Tokens.TITLE).append("' onclick=\"")
                    .append(Tokens.FN_NAVIGATE).append("('#").append(classId).append("')\"").append(">");
        }
        if (isSuper) {
            result.append("<span class='").append(Tokens.TITLE_SUPER_FLAG).append("'>[super]</span>");
        }
        if (classGenericName != null) {
            String classModifierType = classGenericName[0];
            String classDisplayName = classGenericName[1];
            String classInstanceFlag = classGenericName[2];
            String classDisplayId = classGenericName[3];
            if (classModifierType != null) {
                result.append("<span class='").append(Tokens.TITLE_MODIFIER_TYPE).append("'>").append(classModifierType).append("</span>");
            }
            if (classDisplayName != null) {
                result.append("<span class='").append(Tokens.TITLE_DISPLAY_NAME).append("'>").append(classDisplayName).append("</span>");
            }
            if (classInstanceFlag != null) {
                result.append("<span class='").append(Tokens.TITLE_INSTANCE_FLAG).append("'>").append(classInstanceFlag).append("</span>");
            }
            if (classDisplayId != null) {
                result.append("<span class='").append(Tokens.TITLE_DISPLAY_ID).append("'>").append(classDisplayId).append("</span>");
            }
        }
        if (!isLinkClass) {
            if (!isEmpty || isComplex) {
                renderControlButton(classId, Tokens.CONTROL_EXPAND, "[展开]", "zoom-in", !runningConfig.defaultToExpand, result);
                renderControlButton(classId, Tokens.CONTROL_COLLAPSE, "[收起]", "zoom-out", runningConfig.defaultToExpand, result);
            }
            if (isComplex) {
                renderControlButton(classId, Tokens.CONTROL_EXPAND_ALL, "[全部展开]", "zoom-in", !runningConfig.defaultToExpand, result);
                renderControlButton(classId, Tokens.CONTROL_COLLAPSE_ALL, "[全部收起]", "zoom-out", runningConfig.defaultToExpand, result);
            }
        }
        result.append("</pre>");
    }

    /**
     * 展开/收起控制按钮，{@code controlName} 即 {@code data-name} 取值（{@code +} {@code -} {@code ++}
     * {@code --}），同时用作按钮 id 后缀。
     *
     * <p>必须把 {@code event} 传进处理函数并 {@code stopPropagation}：按钮嵌在标题的 onclick 内，冒泡会
     * 触发导航，而导航沿祖先链展开当前元素的子孙，把刚收起的内容又摊开，表现为「点收起没反应」。
     */
    private void renderControlButton(String classId, String controlName, String text, String cursor,
                                     boolean visible, StringBuilder result) {
        result.append("<a id='").append(classId).append(controlName)
                .append("' class='").append(Tokens.TITLE_BUTTON)
                .append("' href='javascript:' onclick='").append(Tokens.FN_TOGGLE)
                .append("(this, event)' style='cursor:").append(cursor).append(';')
                .append(visible ? "" : "display:none;")
                .append("' data-classId='").append(classId)
                .append("' data-name='").append(controlName).append('\'')
                .append('>').append(text).append("</a>");
    }

    private void renderFieldNameTypeAndValue(String fieldId, String fieldStaticFlag, String fieldFinalFlag, String fieldName, String fieldType, Object fieldValue, boolean renderValue, List<String> simpleViewValues, StringBuilder result) {
        result.append("<pre class='").append(Tokens.FIELD_INFO).append("'>");
        result.append("<span class='").append(Tokens.FIELD_INFO_TITLE).append("' onclick=\"")
                .append(Tokens.FN_NAVIGATE).append("('#").append(fieldId).append("')\"").append(">");
        if (fieldStaticFlag != null) {
            result.append("<span class='").append(Tokens.FIELD_INFO_STATIC).append("'>").append(fieldStaticFlag).append("</span>");
        }
        if (fieldFinalFlag != null) {
            result.append("<span class='").append(Tokens.FIELD_INFO_FINAL).append("'>").append(fieldFinalFlag).append("</span>");
        }
        result.append("<b class='").append(Tokens.FIELD_INFO_NAME).append("'>").append(escapeContent(fieldName)).append("</b>");
        result.append("<i class='").append(Tokens.FIELD_INFO_TYPE).append("'>").append(escapeContent(fieldType)).append("</i>");
        if (fieldValue != null) {
            result.append("<span class='").append(Tokens.FIELD_INFO_EQUAL).append("'>=").append("</span>");
            // 敏感字段（password/token/secret 等）只挡值不挡结构：报告里渲染 ***，分析与展开照常
            if (runningConfig.maskSensitiveValues && CommonUtil.isSensitiveName(fieldName)) {
                result.append("<span class='").append(Tokens.FIELD_INFO_VALUE).append("'>")
                        .append(escapeContent(CommonUtil.SENSITIVE_VALUE_MARK)).append("</span>");
                result.append("</span>");
                result.append("</pre>");
                return;
            }
            if (renderValue) {
                String valueString = escapeContent(CommonUtil.getStringFrom(fieldValue, runningConfig.valueDisplayLengthLimit));
                result.append("<span class='").append(Tokens.FIELD_INFO_VALUE).append("'>").append(valueString).append("</span>");
            } else {
                int size = -1;
                if (fieldValue.getClass().isArray()) {
                    size = Array.getLength(fieldValue);
                } else if (fieldValue instanceof Collection) {
                    size = ((Collection<?>) fieldValue).size();
                } else if (fieldValue instanceof Map) {
                    size = ((Map<?, ?>) fieldValue).size();
                }
                if (size >= 0) {
                    result.append("<i class='").append(Tokens.FIELD_INFO_SIZE).append("'>(").append(size).append(")</i>");
                }
                // 简单视图下被包排除的集合元素值内联展示，口径与 PUML 的 [size] = [v1, v2] 一致
                if (simpleViewValues != null && !simpleViewValues.isEmpty()) {
                    String joined = String.join(", ", simpleViewValues);
                    int limit = runningConfig.valueDisplayLengthLimit;
                    if (limit > 0 && joined.length() > limit) {
                        joined = joined.substring(0, limit) + "…";
                    }
                    result.append("<span class='").append(Tokens.FIELD_INFO_EQUAL).append("'>=")
                            .append("</span>");
                    result.append("<span class='").append(Tokens.FIELD_INFO_VALUE).append("'>[")
                            .append(escapeContent(joined)).append("]</span>");
                }
            }
        }
        result.append("</span>");
        result.append("</pre>");
    }

    /**
     * HTML 文本节点转义：只处理 {@code &}、{@code <}、{@code >}，不处理引号（调用点都在元素内容里）。
     *
     * <p>必须单次扫描，不能链式 {@code replace}，否则新生成的 {@code &lt;} 会被二次转义。{@code null}
     * 原样返回，调用方依赖它判断是否输出对应的 {@code span}。
     */
    static String escapeContent(String content) {
        if (content == null) {
            return null;
        }
        int length = content.length();
        StringBuilder escaped = null;
        for (int i = 0; i < length; i++) {
            char c = content.charAt(i);
            String replacement;
            if (c == '&') {
                replacement = "&amp;";
            } else if (c == '<') {
                replacement = "&lt;";
            } else if (c == '>') {
                replacement = "&gt;";
            } else {
                replacement = null;
            }
            if (replacement == null) {
                if (escaped != null) {
                    escaped.append(c);
                }
                continue;
            }
            if (escaped == null) {
                escaped = new StringBuilder(length + 16).append(content, 0, i);
            }
            escaped.append(replacement);
        }
        return escaped == null ? content : escaped.toString();
    }

    /**
     * 渲染端与模板之间的契约常量：类名、属性名、{@code data-name} 取值。
     *
     * <p>类名只在 Java 侧声明，模板 CSS 按名取样式；JS 只读 {@code data-*} 属性，改类名不影响交互。
     * 两侧对应关系由 {@code HtmlTemplateContractTest} 双向校验，漂移即失败。
     */
    static final class Tokens {

        // ---- 类模型 ----
        static final String ROOT = "root_class_model";
        static final String SUPER = "super_class_model";
        static final String CLASS = "class_model";
        static final String CLASS_CONTENT = "class_model_content";
        static final String LINK = "link_class_model";
        static final String LINK_CONTAINER = "link_class_model_container";
        static final String INFO = "common_info_model";

        // ---- 类标题 ----
        static final String TITLE = "class_model_title";
        static final String TITLE_SUPER_FLAG = "class_model_title_super_flag";
        static final String TITLE_MODIFIER_TYPE = "class_model_title_modifier_type";
        static final String TITLE_DISPLAY_NAME = "class_model_title_display_name";
        static final String TITLE_INSTANCE_FLAG = "class_model_title_instance_flag";
        static final String TITLE_DISPLAY_ID = "class_model_title_display_id";
        static final String TITLE_BUTTON = "class_model_title_control_button";

        // ---- 字段 ----
        static final String FIELD = "field_model";
        static final String FIELD_INFO = "field_model_info";
        static final String FIELD_INFO_TITLE = "field_model_info_title";
        static final String FIELD_INFO_STATIC = "field_model_info_static_flag";
        static final String FIELD_INFO_FINAL = "field_model_info_final_flag";
        static final String FIELD_INFO_NAME = "field_model_info_name";
        static final String FIELD_INFO_TYPE = "field_model_info_type";
        static final String FIELD_INFO_EQUAL = "field_model_info_equal_flag";
        static final String FIELD_INFO_VALUE = "field_model_info_value";
        static final String FIELD_INFO_SIZE = "field_model_info_size";
        static final String FIELD_CONTAINER = "field_classes_container";
        static final String FIELD_PANEL = "field_classes_container_panel";

        // ---- 属性名：模板 JS 只允许依赖这些 ----
        /** 可折叠容器标记：导航时沿祖先链展开带此属性的元素。 */
        static final String ATTR_EXPANDABLE = "data-expandable";
        /** 根类模型标记：全部展开/收起的作用起点。 */
        static final String ATTR_ROOT = "data-root";
        /** 按钮目标容器 id。 */
        static final String ATTR_DATA_CLASS_ID = "data-classId";
        /** 按钮动作类型，取值见下方 CONTROL_*。 */
        static final String ATTR_DATA_NAME = "data-name";
        /** 节点种类，供导航提示区分类与字段；取值见 KIND_*。 */
        static final String ATTR_DATA_KIND = "data-kind";
        /** 受控面板标记，值为空。 */
        static final String ATTR_SUPPORT_CONTROL = "support-control";

        /** 以下 *_TRUE / *_KIND_* 片段自带前导空格，调用处直接拼接。 */
        static final String ATTR_EXPANDABLE_TRUE = ' ' + ATTR_EXPANDABLE + "='true'";
        static final String ATTR_ROOT_TRUE = ' ' + ATTR_ROOT + "='true'";

        static final String KIND_CLASS = "class";
        static final String KIND_FIELD = "field";
        static final String ATTR_DATA_KIND_CLASS = ' ' + ATTR_DATA_KIND + "='" + KIND_CLASS + "'";
        static final String ATTR_DATA_KIND_FIELD = ' ' + ATTR_DATA_KIND + "='" + KIND_FIELD + "'";

        // ---- data-name 取值 ----
        static final String CONTROL_EXPAND = "+";
        static final String CONTROL_COLLAPSE = "-";
        static final String CONTROL_EXPAND_ALL = "++";
        static final String CONTROL_COLLAPSE_ALL = "--";

        // ---- 模板 JS 入口函数名 ----
        // 只由本类生成的 onclick 调用，模板内无自身调用点；按「未引用函数」清理模板会让交互静默失效。
        /** 展开/收起按钮的处理函数。 */
        static final String FN_TOGGLE = "changeDisplayForClassDiv";
        /** 类标题、字段标题、链接类的导航入口。 */
        static final String FN_NAVIGATE = "setNaviHash";

        private Tokens() {
        }
    }
}
