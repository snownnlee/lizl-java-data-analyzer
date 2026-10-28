package cn.lizl.project.tool.analysis.core;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import cn.lizl.project.tool.analysis.util.CommonUtil;
import static cn.lizl.project.tool.analysis.util.CommonUtil.appendLine;

/**
 * Mermaid 渲染器：输出 classDiagram 文本，GitHub / Typora / Obsidian 等环境原生出图。
 *
 * <p>遍历与降噪口径（集合包装层折叠、带实例的枚举不展开、回边虚线）与 {@link PumlRender} 一致。
 * 没有 PlantUML 的成员级锚点与分段着色，连线只能落在类框边缘。
 */
class MermaidRender extends BasePumlRender {

    private static final String MARK_DEPTH_LIMIT = "⚠ 深度受限";

    MermaidRender(RunningConfig runningConfig) {
        super(runningConfig, ".mmd");
    }

    @Override
    public String render(ClassModel[] classModels) {
        RenderContext context = new RenderContext(classModels);

        appendHeader(context, classModels);
        for (ClassModel classModel : classModels) {
            renderClassModel(classModel, null, null, context);
        }
        context.result.append(context.links);
        return context.result.toString();
    }

    private void appendHeader(RenderContext context, ClassModel[] classModels) {
        appendLine(context.result, "---");
        appendLine(context.result, "title: ", buildTitle(classModels));
        appendLine(context.result, "---");
        appendLine(context.result, "classDiagram");
        appendLine(context.result, "direction LR");
    }

    private String buildTitle(ClassModel[] classModels) {
        StringBuilder title = new StringBuilder("【");
        for (int i = 0; i < classModels.length; i++) {
            if (i > 0) {
                title.append('、');
            }
            title.append(cleanText(qualifiedDisplayName(classModels[i])));
        }
        return title.append('】').toString();
    }

    private void renderClassModel(ClassModel classModel, String fromAlias,
                                  String linkLabel, RenderContext context) {
        if (classModel == null) {
            return;
        }
        if (classModel instanceof ClassModel.LinkClassModel) {
            ClassModel origin = ((ClassModel.LinkClassModel) classModel).originClassModel;
            if (fromAlias != null && isInstanceEnum(origin)) {
                // 枚举节点不画，指向它的边就没有落点，画出去会连到隐式空节点
                return;
            }
            appendLink(context, fromAlias, origin, linkLabel);
            return;
        }
        if (classModel instanceof ClassModel.InfoClassModel) {
            return;
        }
        if (fromAlias != null && isInstanceEnum(classModel)) {
            return;
        }
        if (isTransparent(classModel)) {
            // 集合包装层不建节点，元素直接接到最外层字段上
            for (FieldModel fieldModel : classModel.fieldModels) {
                for (ClassModel fieldTypeClassModel : fieldModel.fieldTypeClassesModels) {
                    renderClassModel(fieldTypeClassModel, fromAlias, linkLabel, context);
                }
            }
            return;
        }
        appendClassModel(classModel, context);
        appendLink(context, fromAlias, classModel, linkLabel);
    }

    private void appendClassModel(ClassModel classModel, RenderContext context) {
        ClassBody body = new ClassBody();
        if (classModel.sourceClass != null && classModel.sourceClass.isEnum()) {
            body.appendRow("<<enumeration>>");
        }
        appendFieldLines(body, classModel, context);
        appendSuperFieldLines(body, classModel.superClassModel, context);

        String label = mermaidClassDisplayName(classModel, context.instanceSuffix(classModel));
        if (body.isEmpty()) {
            appendLine(context.result, "class ", classModel.id, "[\"", label, "\"]");
        } else {
            appendLine(context.result, "class ", classModel.id, "[\"", label, "\"] {");
            context.result.append(body.lines);
            appendLine(context.result, "}");
        }
        context.markDeclared(classModel.id);
        renderChildren(classModel, context);
    }

    private void appendFieldLines(ClassBody body, ClassModel owner, RenderContext context) {
        for (FieldModel fieldModel : owner.fieldModels) {
            if (fieldModel instanceof FieldModel.InfoFieldModel) {
                body.appendRow(escapeMermaid(cleanText(((FieldModel.InfoFieldModel) fieldModel).info)));
                continue;
            }
            if (fieldModel.sourceField == null) {
                continue;
            }
            boolean renderValue = fieldModel.fieldTypeClassesModels.isEmpty()
                    || fieldModel.sourceField.getType().isEnum()
                    || fieldModel.sourceFieldValue instanceof Enum;
            String line = mermaidFieldLine(fieldModel, renderValue);
            if (isDepthLimited(fieldModel)) {
                line += " " + MARK_DEPTH_LIMIT;
            }
            body.appendRow(line);
        }
    }

    private static boolean isDepthLimited(FieldModel fieldModel) {
        for (ClassModel fieldTypeClassModel : fieldModel.fieldTypeClassesModels) {
            if (fieldTypeClassModel instanceof ClassModel.InfoClassModel) {
                return true;
            }
        }
        return false;
    }

    /** 父类没有独立节点，字段内联进子类框，用标注行标起点。 */
    private void appendSuperFieldLines(ClassBody body, ClassModel superClassModel,
                                       RenderContext context) {
        if (superClassModel == null) {
            return;
        }
        ClassModel display = superClassModel instanceof ClassModel.LinkClassModel
                ? ((ClassModel.LinkClassModel) superClassModel).originClassModel
                : superClassModel;
        body.appendRow("※ [super] " + cleanText(qualifiedDisplayName(display)));
        if (superClassModel instanceof ClassModel.InfoClassModel) {
            body.appendRow(escapeMermaid(cleanText(((ClassModel.InfoClassModel) superClassModel).info)));
            return;
        }
        if (superClassModel instanceof ClassModel.LinkClassModel) {
            return;
        }
        appendFieldLines(body, superClassModel, context);
        appendSuperFieldLines(body, superClassModel.superClassModel, context);
    }

    /** 类体闭合后才展开子节点，保证类声明之间不会互相嵌套。 */
    private void renderChildren(ClassModel owner, RenderContext context) {
        for (FieldModel fieldModel : owner.fieldModels) {
            for (ClassModel fieldTypeClassModel : fieldModel.fieldTypeClassesModels) {
                renderClassModel(fieldTypeClassModel, owner.id, fieldNameOf(fieldModel), context);
            }
        }
        renderSuperChildren(owner, owner.superClassModel, context);
    }

    private void renderSuperChildren(ClassModel owner, ClassModel superClassModel, RenderContext context) {
        if (superClassModel == null) {
            return;
        }
        if (superClassModel instanceof ClassModel.LinkClassModel) {
            appendLink(context, owner.id, ((ClassModel.LinkClassModel) superClassModel).originClassModel,
                    "[super]");
            return;
        }
        if (superClassModel instanceof ClassModel.InfoClassModel) {
            return;
        }
        for (FieldModel fieldModel : superClassModel.fieldModels) {
            for (ClassModel fieldTypeClassModel : fieldModel.fieldTypeClassesModels) {
                renderClassModel(fieldTypeClassModel, owner.id, fieldNameOf(fieldModel), context);
            }
        }
        renderSuperChildren(owner, superClassModel.superClassModel, context);
    }

    private static String fieldNameOf(FieldModel fieldModel) {
        return fieldModel.sourceField == null ? null : fieldModel.getFieldName();
    }

    private void appendLink(RenderContext context, String fromAlias, ClassModel target, String label) {
        if (fromAlias == null || target == null) {
            return;
        }
        if (!context.addLink(fromAlias, target.id, label)) {
            return;
        }
        StringBuilder link = new StringBuilder(48);
        link.append(fromAlias)
                .append(context.isBackEdge(fromAlias, target.id) ? " -.-> " : " --> ")
                .append(target.id);
        String cleanLabel = cleanText(label);
        if (!cleanLabel.isEmpty()) {
            link.append(" : ").append(escapeMermaid(cleanLabel));
        }
        appendLine(context.links, link);
    }

    /** {@code 全限定名 * @2 #C1#}；实例标记与后缀的口径同 PUML。 */
    private String mermaidClassDisplayName(ClassModel classModel, String instanceSuffix) {
        StringBuilder result = new StringBuilder(cleanText(qualifiedDisplayName(classModel)));
        if (classModel.sourceClassInstance != null) {
            result.append(" *");
            if (instanceSuffix != null) {
                result.append(' ').append(escapeMermaid(instanceSuffix));
            }
        }
        result.append(" #").append(classModel.id).append('#');
        return escapeMermaid(result.toString());
    }

    /** {@code [static] name : Type [final] [= value | [size]]}，口径与 PUML 字段行一致。 */
    private String mermaidFieldLine(FieldModel fieldModel, boolean renderValue) {
        StringBuilder line = new StringBuilder(64);
        int modifiers = fieldModel.sourceField.getModifiers();
        if (Modifier.isStatic(modifiers)) {
            line.append("static ");
        }
        line.append(escapeMermaid(cleanText(fieldModel.getFieldName())));
        line.append(" : ").append(toMermaidGenerics(cleanText(fieldModel.getFieldType())));
        if (Modifier.isFinal(modifiers)) {
            line.append(" final");
        }
        Object value = fieldModel.sourceFieldValue;
        if (value != null) {
            // 敏感字段（password/token/secret 等）只挡值不挡结构：报告里渲染 ***，分析与展开照常
            if (runningConfig.maskSensitiveValues && CommonUtil.isSensitiveName(fieldModel.getFieldName())) {
                line.append(" = ").append(escapeMermaid(CommonUtil.SENSITIVE_VALUE_MARK));
                return line.toString();
            }
            if (renderValue) {
                line.append(" = ").append(escapeMermaid(valueText(value)));
            } else {
                int size = sizeOf(value);
                if (size >= 0) {
                    line.append(" [").append(size).append(']');
                }
            }
        }
        return line.toString();
    }

    private static String toMermaidGenerics(String type) {
        if (type.indexOf('<') < 0) {
            return type;
        }
        return type.replace('<', '~').replace('>', '~');
    }

    /** 成员行里的 {@code ~ ; < >} 会干扰 Mermaid 语法，统一替换成全角。 */
    protected static String escapeMermaid(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf('~') < 0 && text.indexOf(';') < 0
                && text.indexOf('<') < 0 && text.indexOf('>') < 0) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '~':
                    result.append('～');
                    break;
                case ';':
                    result.append('；');
                    break;
                case '<':
                    result.append('＜');
                    break;
                case '>':
                    result.append('＞');
                    break;
                default:
                    result.append(c);
            }
        }
        return result.toString();
    }

    private static final class ClassBody {

        private final StringBuilder lines = new StringBuilder(256);

        void appendRow(String line) {
            appendLine(lines, "  ", line);
        }

        boolean isEmpty() {
            return lines.length() == 0;
        }
    }

    /** 单次渲染的上下文，渲染器复用时不会残留上次内容。 */
    private static final class RenderContext {

        private final StringBuilder result = new StringBuilder(512);

        private final StringBuilder links = new StringBuilder(128);

        /** 类型 → 实例 → 出现序号，用于生成同类型多实例的 @1 / @2 后缀 */
        private final Map<Class<?>, Map<Object, Integer>> instanceOrdinals = new HashMap<>();

        /** 类别名 → 声明顺序，目标更早声明即为回边 */
        private final Map<String, Integer> declarationOrder = new HashMap<>();

        /** 已输出的边（from|to|label），折叠包装层后同一关系可能重复出现 */
        private final Set<String> emittedLinks = new LinkedHashSet<>();

        RenderContext(ClassModel[] rootClassModels) {
            for (ClassModel rootClassModel : rootClassModels) {
                collectInstances(rootClassModel);
            }
        }

        private void collectInstances(ClassModel classModel) {
            if (classModel == null || classModel instanceof ClassModel.LinkClassModel) {
                return;
            }
            if (classModel.sourceClass != null && classModel.sourceClassInstance != null) {
                Map<Object, Integer> ordinals = instanceOrdinals
                        .computeIfAbsent(classModel.sourceClass, key -> new IdentityHashMap<>());
                if (!ordinals.containsKey(classModel.sourceClassInstance)) {
                    ordinals.put(classModel.sourceClassInstance, ordinals.size() + 1);
                }
            }
            for (FieldModel fieldModel : classModel.fieldModels) {
                for (ClassModel fieldTypeClassModel : fieldModel.fieldTypeClassesModels) {
                    collectInstances(fieldTypeClassModel);
                }
            }
            collectInstances(classModel.superClassModel);
        }

        String instanceSuffix(ClassModel classModel) {
            if (classModel.sourceClass == null || classModel.sourceClassInstance == null) {
                return null;
            }
            Map<Object, Integer> ordinals = instanceOrdinals.get(classModel.sourceClass);
            if (ordinals == null || ordinals.size() <= 1) {
                return null;
            }
            Integer ordinal = ordinals.get(classModel.sourceClassInstance);
            return ordinal == null ? null : "@" + ordinal;
        }

        void markDeclared(String classAlias) {
            if (!declarationOrder.containsKey(classAlias)) {
                declarationOrder.put(classAlias, declarationOrder.size());
            }
        }

        boolean isBackEdge(String fromAlias, String targetAlias) {
            Integer from = declarationOrder.get(fromAlias);
            Integer target = declarationOrder.get(targetAlias);
            return from != null && target != null && target < from;
        }

        boolean addLink(String fromAlias, String targetAlias, String label) {
            return emittedLinks.add(fromAlias + '|' + targetAlias + '|' + label);
        }
    }
}
