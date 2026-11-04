package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;
import cn.lizl.project.tool.analysis.util.CommonUtil;
import cn.lizl.project.tool.analysis.util.HtmlTemplates;
import cn.lizl.project.tool.analysis.util.HtmlTreeAssets;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 交互式 HTML 树的「数据骨架」形态：产物里只写一份 JSON，DOM 由同目录的
 * {@link HtmlTreeAssets#BUILDER} 在浏览器里建。
 *
 * <p>与 {@link HtmlRender} 的关系是「同一棵树的两条通道」：样式、交互脚本、DOM 结构、类名全部共用，
 * 前端建出来的节点因此能被同一份交互脚本直接操作。差异只有两处：
 * <ol>
 *   <li>服务端不再拼标签，只输出 JSON（体积主要省在这里）；</li>
 *   <li>文本不再做 HTML 转义——JSON 里的原字符交给前端 {@code textContent}，交给浏览器转义一次即可。
 *       唯一的例外是 {@code <}：写进 {@code <script>} 的数据必须把它转成 {@code \u003c}，否则值里出现
 *       {@code </script>} 会提前截断数据块。</li>
 * </ol>
 *
 * <p>JSON 口径与前端的分工：<b>能推导的不落盘</b>。例如锚点文本 {@code #id#} 由前端用 id 拼、展开按钮的
 * 显隐取自根上的 {@code defaultToExpand}、是否出现「展开/收起」按钮由 {@code fields} 是否为空 + {@code complex}
 * 推导。这样数据里只有真正无法推导的信息，体积与漂移面同时最小。
 */
@SuppressWarnings("JavadocReference")
class HtmlRender2 extends BaseRender {

    private static final String FILE_EXTENSION = ".html";

    /** 模板与 HTML1 分开一份：骨架必须逐字一致，但占位符不同，合在一起会让 HTML1 产物残留未替换的占位符 */
    static final String OUTPUT_TEMPLATE_NAME = JavaDataAnalyzer.class.getSimpleName() + "2" + FILE_EXTENSION;

    /** 数据块所在的 {@code <script>} 元素 id，模板与建树脚本各引用一次，靠测试卡住两边一致 */
    static final String DATA_ELEMENT_ID = "analysis-data";

    private static final String[] PLACEHOLDERS = {"${title}", "${styleFile}", "${scriptFile}", "${data}", "${builderFile}"};

    HtmlRender2(RunningConfig runningConfig) {
        super(runningConfig, FILE_EXTENSION);
    }

    @Override
    public String render(ClassModel[] classModels) {
        return HtmlTemplates.fill(HtmlTemplates.load(OUTPUT_TEMPLATE_NAME),
                PLACEHOLDERS,
                new String[]{HtmlRender.buildReportTitle(classModels),
                        HtmlTreeAssets.servedName(HtmlTreeAssets.STYLE),
                        HtmlTreeAssets.servedName(HtmlTreeAssets.INTERACTION),
                        buildSkeleton(classModels),
                        HtmlTreeAssets.servedName(HtmlTreeAssets.BUILDER)});
    }

    private String buildSkeleton(ClassModel[] classModels) {
        StringBuilder skeleton = new StringBuilder(1024);
        skeleton.append("{\"defaultToExpand\":").append(runningConfig.defaultToExpand).append(",\"classes\":[");
        for (int i = 0; i < classModels.length; i++) {
            if (i > 0) {
                skeleton.append(',');
            }
            appendClassModel(classModels[i], true, false, skeleton);
        }
        skeleton.append("]}");
        return skeleton.toString();
    }

    private void appendClassModel(ClassModel classModel, boolean isRoot, boolean isSuper, StringBuilder skeleton) {
        if (classModel instanceof ClassModel.InfoClassModel) {
            appendInfoClassModel((ClassModel.InfoClassModel) classModel, skeleton);
            return;
        }
        if (classModel instanceof ClassModel.LinkClassModel) {
            appendLinkClassModel((ClassModel.LinkClassModel) classModel, isSuper, skeleton);
            return;
        }
        skeleton.append("{\"type\":\"class\",\"id\":");
        appendString(classModel.id, skeleton);
        if (isRoot) {
            skeleton.append(",\"root\":true");
        }
        if (isSuper) {
            skeleton.append(",\"super\":true");
        }
        skeleton.append(",\"title\":");
        appendTitle(ClassTitle.of(classModel), skeleton);
        if (classModel.isComplex) {
            skeleton.append(",\"complex\":true");
        }
        skeleton.append(",\"fields\":[");
        for (int i = 0; i < classModel.fieldModels.size(); i++) {
            if (i > 0) {
                skeleton.append(',');
            }
            appendFieldModel(classModel.fieldModels.get(i), skeleton);
        }
        skeleton.append(']');
        if (classModel.superClassModel != null) {
            skeleton.append(",\"superClass\":");
            appendClassModel(classModel.superClassModel, false, true, skeleton);
        }
        skeleton.append('}');
    }

    private static void appendInfoClassModel(ClassModel.InfoClassModel infoClassModel, StringBuilder skeleton) {
        skeleton.append("{\"type\":\"info\",\"id\":");
        appendString(infoClassModel.id, skeleton);
        skeleton.append(",\"text\":");
        appendString(infoClassModel.info, skeleton);
        skeleton.append('}');
    }

    /**
     * 链接类：该对象/类型已在别处展开，前端渲染成跳转锚点。
     *
     * <p>锚点文本里的 {@code [super]} 与标题片段顺序由 {@code type} + {@code super} + {@code title} 决定，
     * 与 {@link HtmlRender#renderLinkClassGenericName} 保持一致。
     */
    private static void appendLinkClassModel(ClassModel.LinkClassModel linkClassModel, boolean isSuper, StringBuilder skeleton) {
        skeleton.append("{\"type\":\"link\",\"id\":");
        appendString(linkClassModel.id, skeleton);
        skeleton.append(",\"target\":");
        appendString(linkClassModel.originClassModel.id, skeleton);
        if (isSuper) {
            skeleton.append(",\"super\":true");
        }
        skeleton.append(",\"title\":");
        appendTitle(ClassTitle.of(linkClassModel.originClassModel), skeleton);
        skeleton.append('}');
    }

    /**
     * 标题片段只写「有值」的那几个：锚点文本 {@code #id#} 前端用 id 拼，省一份重复数据。
     *
     * <p>片段本身不转义，数值来自 {@link ClassTitle#of}，交由前端 {@code textContent} 处理。
     */
    private static void appendTitle(String[] classTitle, StringBuilder skeleton) {
        skeleton.append('{');
        boolean first = true;
        if (classTitle[0] != null) {
            skeleton.append("\"modifier\":");
            appendString(classTitle[0], skeleton);
            first = false;
        }
        if (classTitle[1] != null) {
            if (!first) {
                skeleton.append(',');
            }
            skeleton.append("\"name\":");
            appendString(classTitle[1], skeleton);
            first = false;
        }
        if (classTitle[2] != null) {
            if (!first) {
                skeleton.append(',');
            }
            skeleton.append("\"instance\":true");
        }
        skeleton.append('}');
    }

    private void appendFieldModel(FieldModel fieldModel, StringBuilder skeleton) {
        if (fieldModel instanceof FieldModel.InfoFieldModel) {
            appendInfoFieldModel((FieldModel.InfoFieldModel) fieldModel, skeleton);
            return;
        }
        String fieldName = fieldModel.getFieldName();
        int fieldModifiers = fieldModel.sourceField.getModifiers();
        skeleton.append("{\"type\":\"field\",\"id\":");
        appendString(fieldModel.id, skeleton);
        if (Modifier.isStatic(fieldModifiers)) {
            skeleton.append(",\"static\":true");
        }
        if (Modifier.isFinal(fieldModifiers)) {
            skeleton.append(",\"final\":true");
        }
        skeleton.append(",\"name\":");
        appendString(fieldName, skeleton);
        skeleton.append(",\"fieldType\":");
        appendString(fieldModel.getFieldType(), skeleton);
        appendFieldValue(fieldModel, fieldName, skeleton);

        int fieldClassSize = fieldModel.fieldTypeClassesModels.size();
        if (fieldClassSize > 0) {
            skeleton.append(",\"classes\":[");
            for (int i = 0; i < fieldClassSize; i++) {
                if (i > 0) {
                    skeleton.append(',');
                }
                appendClassModel(fieldModel.fieldTypeClassesModels.get(i), false, false, skeleton);
            }
            skeleton.append(']');
        }
        skeleton.append('}');
    }

    /**
     * 字段值的四种呈现，口径与 {@link HtmlRender#renderFieldNameTypeAndValue} 逐分支对齐：
     * 脱敏、字面量、集合元素个数、简单视图内联值（后两者可同时出现）。
     *
     * <p>值为 {@code null} 时什么都不写，前端据此不输出 {@code =}。
     */
    private void appendFieldValue(FieldModel fieldModel, String fieldName, StringBuilder skeleton) {
        Object fieldValue = fieldModel.sourceFieldValue;
        if (fieldValue == null) {
            return;
        }
        if (runningConfig.maskSensitiveValues && CommonUtil.isSensitiveName(fieldName)) {
            // 脱敏标记本身也由服务端给，避免前端另记一份 *** 与 Java 侧漂移
            skeleton.append(",\"masked\":");
            appendString(CommonUtil.SENSITIVE_VALUE_MARK, skeleton);
            return;
        }
        boolean renderValue = fieldModel.fieldTypeClassesModels.isEmpty()
                || fieldModel.sourceField.getType().isEnum()
                || fieldValue instanceof Enum;
        if (renderValue) {
            skeleton.append(",\"value\":");
            appendString(CommonUtil.getStringFrom(fieldValue, runningConfig.valueDisplayLengthLimit), skeleton);
            return;
        }
        int size = -1;
        if (fieldValue.getClass().isArray()) {
            size = Array.getLength(fieldValue);
        } else if (fieldValue instanceof Collection) {
            size = ((Collection<?>) fieldValue).size();
        } else if (fieldValue instanceof Map) {
            size = ((Map<?, ?>) fieldValue).size();
        }
        if (size >= 0) {
            skeleton.append(",\"size\":").append(size);
        }
        List<String> simpleViewValues = fieldModel.simpleViewElementValues();
        if (simpleViewValues != null && !simpleViewValues.isEmpty()) {
            String joined = simpleViewInlineText(simpleViewValues);
            skeleton.append(",\"inline\":");
            appendString(joined, skeleton);
        }
    }

    private static void appendInfoFieldModel(FieldModel.InfoFieldModel infoFieldModel, StringBuilder skeleton) {
        skeleton.append("{\"type\":\"infoField\",\"id\":");
        appendString(infoFieldModel.id, skeleton);
        skeleton.append(",\"text\":");
        appendString(infoFieldModel.info, skeleton);
        skeleton.append('}');
    }

    /**
     * JSON 字符串字面量。
     *
     * <p>{@code <} 一律写成 {@code \u003c}：数据块在 {@code <script>} 里，值中出现 {@code </script>}
     * 会让浏览器提前结束数据块，整棵树静默变空。控制字符与 U+2028/U+2029 也转义，避免前端解析异常。
     */
    static void appendString(String value, StringBuilder skeleton) {
        if (value == null) {
            skeleton.append("null");
            return;
        }
        skeleton.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    skeleton.append("\\\"");
                    break;
                case '\\':
                    skeleton.append("\\\\");
                    break;
                case '<':
                    skeleton.append("\\u003c");
                    break;
                case '\n':
                    skeleton.append("\\n");
                    break;
                case '\r':
                    skeleton.append("\\r");
                    break;
                case '\t':
                    skeleton.append("\\t");
                    break;
                case '\b':
                    skeleton.append("\\b");
                    break;
                case '\f':
                    skeleton.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        skeleton.append(String.format("\\u%04x", (int) c));
                    } else {
                        skeleton.append(c);
                    }
            }
        }
        skeleton.append('"');
    }
}
