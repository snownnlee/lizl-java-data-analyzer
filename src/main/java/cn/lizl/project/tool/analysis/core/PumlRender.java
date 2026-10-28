package cn.lizl.project.tool.analysis.core;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static cn.lizl.project.tool.analysis.util.CommonUtil.appendLine;

/**
 * PlantUML 渲染器：输出「类声明 + Creole 成员行 + 关系边」，视觉语义对齐 HTML 版。
 *
 * <p>必须先闭合当前类体再递归展开字段类型与父类，边拼边递归会让子类声明落进父类花括号，破坏结构。
 *
 * <p>静态图摊平实例树必然成毛线团，因此做三处降噪：集合包装层不建节点、深度提示内联到字段行尾、
 * 回边画灰色虚线。
 */
class PumlRender extends BasePumlRender {

    /** 回边（指向已出现节点）的虚线颜色：边框蓝的暗化版，既与主色同源又退到背景之后 */
    private static final String COLOR_BACK_LINK = "#3a5a99";

    /** 字段因超出解析深度而未能展开时的行尾标记 */
    private static final String MARK_DEPTH_LIMIT = "⚠ 深度受限";

    PumlRender(RunningConfig runningConfig) {
        super(runningConfig, ".puml");
    }

    @Override
    public String render(ClassModel[] classModels) {
        RenderContext context = new RenderContext(classModels);

        appendHeader(context, classModels);
        for (ClassModel classModel : classModels) {
            renderClassModel(classModel, null, null, null, context);
        }
        context.result.append(context.links);
        appendLine(context.result, "@enduml");
        return context.result.toString();
    }

    private void appendHeader(RenderContext context, ClassModel[] classModels) {
        appendLine(context.result, "@startuml");
        appendLine(context.result, "set namespaceSeparator none");
        // 根节点在左、引用逐层向右，贴合 HTML 的深度展开顺序
        appendLine(context.result, "left to right direction");
        appendLine(context.result, "skinparam backgroundColor #0a0e17");
        appendLine(context.result, "skinparam classBackgroundColor #0f141e");
        appendLine(context.result, "skinparam classBorderColor #6495ed");
        appendLine(context.result, "skinparam classHeaderBackgroundColor #1d263b");
        appendLine(context.result, "skinparam classFontColor #c1c1c1");
        appendLine(context.result, "skinparam classAttributeFontColor #c1c1c1");
        appendLine(context.result, "skinparam classArrowColor #6495ed");
        appendLine(context.result, "skinparam titleFontColor #c1c1c1");
        appendLine(context.result, "skinparam defaultFontName \"Microsoft YaHei UI\"");
        appendLine(context.result, "skinparam shadowing false");
        appendLine(context.result, "skinparam classAttributeIconSize 0");
        // polyline 把控制点压到端点、几何退化为直线，但沿用同一套布局，成员级锚点 C::field --> D 仍生效
        // （ortho / elk 会丢掉锚点）
        appendLine(context.result, "skinparam linetype polyline");
        appendLine(context.result, "hide circle");
        appendLine(context.result, "hide empty members");
        appendLine(context.result, "title ", buildTitle(classModels));
    }

    private String buildTitle(ClassModel[] classModels) {
        StringBuilder title = new StringBuilder("【");
        for (int i = 0; i < classModels.length; i++) {
            if (i > 0) {
                title.append('、');
            }
            title.append(qualifiedDisplayName(classModels[i]));
        }
        return title.append('】').toString();
    }

    /**
     * @param fromAlias  发起引用的节点别名，为 null 表示根节点
     * @param memberName 发起引用的字段名；非 null 且该字段确实写在类体里时，边从该字段行引出
     * @param linkLabel  边标签，通常是字段名，父类引用为 {@code [super]}
     */
    private void renderClassModel(ClassModel classModel, String fromAlias, String memberName,
                                  String linkLabel, RenderContext context) {
        if (classModel == null) {
            return;
        }
        if (classModel instanceof ClassModel.LinkClassModel) {
            ClassModel origin = ((ClassModel.LinkClassModel) classModel).originClassModel;
            if (fromAlias != null && isInstanceEnum(origin)) {
                // 枚举节点本身不画，指向它的回边也就没有落点；画出去会变出一条指向隐式空节点的线
                return;
            }
            appendLink(context, fromAlias, memberName, origin, linkLabel);
            return;
        }
        if (classModel instanceof ClassModel.InfoClassModel) {
            // 深度提示已内联到字段行尾，不再单独建节点与边
            return;
        }
        if (fromAlias != null && isInstanceEnum(classModel)) {
            return;
        }
        if (isTransparent(classModel)) {
            // 集合包装层不建节点，把它的元素直接接到最外层字段上
            for (FieldModel fieldModel : classModel.fieldModels) {
                for (ClassModel fieldTypeClassModel : fieldModel.fieldTypeClassesModels) {
                    renderClassModel(fieldTypeClassModel, fromAlias, memberName, linkLabel, context);
                }
            }
            return;
        }
        appendClassModel(classModel, context);
        appendLink(context, fromAlias, memberName, classModel, linkLabel);
    }

    private void appendClassModel(ClassModel classModel, RenderContext context) {
        ClassBody body = new ClassBody();
        String bodyOwnerId = classModel.id;
        appendFieldLines(body, classModel, bodyOwnerId, context);
        appendSuperFieldLines(body, classModel.superClassModel, bodyOwnerId, context);

        if (body.isEmpty()) {
            appendLine(context.result, classKeyword(classModel), " \"",
                    classDisplayName(classModel, context.instanceSuffix(classModel)),
                    "\" as ", classModel.id, classStereotype(classModel));
        } else {
            appendLine(context.result, classKeyword(classModel), " \"",
                    classDisplayName(classModel, context.instanceSuffix(classModel)),
                    "\" as ", classModel.id, classStereotype(classModel), " {");
            context.result.append(body.lines);
            appendLine(context.result, "}");
        }
        context.markDeclared(classModel.id);
        renderChildren(classModel, context);
    }

    /**
     * 只拼文本行，不触发递归。
     *
     * @param bodyOwnerId 类体归属别名：父类字段内联进子类框，登记到子类名下
     */
    private void appendFieldLines(ClassBody body, ClassModel owner, String bodyOwnerId, RenderContext context) {
        for (FieldModel fieldModel : owner.fieldModels) {
            if (fieldModel instanceof FieldModel.InfoFieldModel) {
                body.appendRow(wrapAndColorize(COLOR_WARN, ((FieldModel.InfoFieldModel) fieldModel).info, INFO_WRAP_WIDTH));
                continue;
            }
            if (fieldModel.sourceField == null) {
                continue;
            }
            boolean renderValue = fieldModel.fieldTypeClassesModels.isEmpty()
                    || fieldModel.sourceField.getType().isEnum()
                    || fieldModel.sourceFieldValue instanceof Enum;
            String line = buildFieldLine(fieldModel, renderValue);
            if (isDepthLimited(fieldModel)) {
                line += " " + colorize(COLOR_WARN, MARK_DEPTH_LIMIT);
            }
            context.addMember(bodyOwnerId, cleanText(fieldModel.getFieldName()));
            body.appendRow(line);
        }
    }

    /** 该字段是否有目标因超出解析深度而未能展开。 */
    private static boolean isDepthLimited(FieldModel fieldModel) {
        for (ClassModel fieldTypeClassModel : fieldModel.fieldTypeClassesModels) {
            if (fieldTypeClassModel instanceof ClassModel.InfoClassModel) {
                return true;
            }
        }
        return false;
    }

    /** 父类字段内联在子类框内，用段落标题标注起点；父类没有独立节点，因此不带编号。 */
    private void appendSuperFieldLines(ClassBody body, ClassModel superClassModel,
                                       String bodyOwnerId, RenderContext context) {
        if (superClassModel == null) {
            return;
        }
        body.appendSection("== [super] " + qualifiedDisplayName(
                superClassModel instanceof ClassModel.LinkClassModel
                        ? ((ClassModel.LinkClassModel) superClassModel).originClassModel
                        : superClassModel) + " ==");
        if (superClassModel instanceof ClassModel.InfoClassModel) {
            body.appendRow(wrapAndColorize(COLOR_WARN, ((ClassModel.InfoClassModel) superClassModel).info, INFO_WRAP_WIDTH));
            return;
        }
        if (superClassModel instanceof ClassModel.LinkClassModel) {
            return;
        }
        appendFieldLines(body, superClassModel, bodyOwnerId, context);
        appendSuperFieldLines(body, superClassModel.superClassModel, bodyOwnerId, context);
    }

    /** 类体写完之后才展开子节点，保证类声明之间不会互相嵌套。 */
    private void renderChildren(ClassModel owner, RenderContext context) {
        for (FieldModel fieldModel : owner.fieldModels) {
            String fieldName = fieldModel.sourceField == null ? null : fieldModel.getFieldName();
            for (ClassModel fieldTypeClassModel : fieldModel.fieldTypeClassesModels) {
                renderClassModel(fieldTypeClassModel, owner.id, fieldName, fieldName, context);
            }
        }
        renderSuperChildren(owner, owner.superClassModel, context);
    }

    private void renderSuperChildren(ClassModel owner, ClassModel superClassModel, RenderContext context) {
        if (superClassModel == null) {
            return;
        }
        if (superClassModel instanceof ClassModel.LinkClassModel) {
            appendLink(context, owner.id, null, ((ClassModel.LinkClassModel) superClassModel).originClassModel, "[super]");
            return;
        }
        if (superClassModel instanceof ClassModel.InfoClassModel) {
            return;
        }
        for (FieldModel fieldModel : superClassModel.fieldModels) {
            String fieldName = fieldModel.sourceField == null ? null : fieldModel.getFieldName();
            for (ClassModel fieldTypeClassModel : fieldModel.fieldTypeClassesModels) {
                renderClassModel(fieldTypeClassModel, owner.id, fieldName, fieldName, context);
            }
        }
        renderSuperChildren(owner, superClassModel.superClassModel, context);
    }

    /**
     * 边：{@code C1::field --> C2}，字段未写进类体时退化为 {@code C1 --> C2}；指向已声明节点的是回边，画灰虚线。
     *
     * <p>成员名写错时 PlantUML 不报错、只静默画出怪边，因此起点必须用「已写入类体的成员集合」校验。
     * 成员名含 {@code ::} 锚定语法不接受的字符（如 {@code [this]} 的方括号）时 PlantUML 直接报
     * 「Syntax Error? (Assumed diagram type: class)」，须把 {@code C1::field} 整体加引号——
     * 引号写法实测（1.2025.0/1.2026.0）保留成员锚定语义；而 {@code C1::"field"} 非法，不能只引成员名。
     */
    private void appendLink(RenderContext context, String fromAlias, String memberName,
                            ClassModel target, String label) {
        if (fromAlias == null || target == null) {
            return;
        }
        String cleanMember = memberName == null ? null : cleanText(memberName);
        boolean hasMember = cleanMember != null && context.hasMember(fromAlias, cleanMember);
        if (!context.addLink(fromAlias, hasMember ? cleanMember : null, target.id, label)) {
            return;
        }
        StringBuilder link = new StringBuilder(48);
        if (hasMember && needsQuotedAnchor(cleanMember)) {
            link.append('"').append(fromAlias).append("::").append(cleanMember).append('"');
        } else {
            link.append(fromAlias);
            if (hasMember) {
                link.append("::").append(cleanMember);
            }
        }
        link.append(context.isBackEdge(fromAlias, target.id)
                ? " -[" + COLOR_BACK_LINK + ",dashed]-> "
                : " --> ");
        link.append(target.id);
        String cleanLabel = cleanText(label);
        if (!cleanLabel.isEmpty()) {
            link.append(" : ").append(colorize(COLOR_LINK, cleanLabel));
        }
        appendLine(context.links, link);
    }

    /**
     * 成员名是否不能直接用于 {@code C::member} 锚定（如 {@code [this]}、{@code [toString()]} 的方括号、圆括号），
     * 需把 {@code C::member} 整体加引号。字母、数字、{@code _}、{@code $}、{@code .} 实测均可裸写。
     */
    static boolean needsQuotedAnchor(String member) {
        for (int i = 0; i < member.length(); i++) {
            char c = member.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '_' || c == '$' || c == '.')) {
                return true;
            }
        }
        return false;
    }

    /**
     * 类体拼装器：字段之间自动插 {@code --} 分隔线，每个字段在框内独立成行且不影响锚点定位。
     * 段标题本身已起分隔作用，其后不再叠线，否则会出现双线。
     */
    private static final class ClassBody {

        private final StringBuilder lines = new StringBuilder(256);

        /** 上一行是否是字段行，用于判断是否需要补分隔线 */
        private boolean hasRow;

        void appendRow(String line) {
            if (hasRow) {
                appendLine(lines, "  --");
            }
            appendLine(lines, "  ", line);
            hasRow = true;
        }

        void appendSection(String line) {
            appendLine(lines, "  ", line);
            hasRow = false;
        }

        boolean isEmpty() {
            return lines.length() == 0;
        }
    }

    /** 单次渲染的上下文：类声明、关系边、实例计数分块存放，渲染器复用时不会残留上次内容。 */
    private static final class RenderContext {

        private final StringBuilder result = new StringBuilder(512);

        private final StringBuilder links = new StringBuilder(128);

        /** 类型 → 实例 → 出现序号；按遍历顺序编号，保证同一输入多次渲染结果稳定可 diff */
        private final Map<Class<?>, Map<Object, Integer>> instanceOrdinals = new HashMap<>();

        /** 类别名 → 已写入该类体的成员名；只有命中的成员才能作为边的起点 */
        private final Map<String, Set<String>> emittedMembers = new HashMap<>();

        /** 类别名 → 声明顺序；用于区分「向后展开」与「回指已出现的节点」 */
        private final Map<String, Integer> declarationOrder = new HashMap<>();

        /** 已输出的边（from|member|to|label）；折叠包装层后同一字段可能重复指向同一目标 */
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

        /** 同类型多实例时返回 {@code @1} / {@code @2} 这类区分后缀，否则返回 null。 */
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

        void addMember(String classAlias, String memberName) {
            emittedMembers.computeIfAbsent(classAlias, key -> new LinkedHashSet<>()).add(memberName);
        }

        boolean hasMember(String classAlias, String memberName) {
            Set<String> members = emittedMembers.get(classAlias);
            return members != null && members.contains(memberName);
        }

        void markDeclared(String classAlias) {
            if (!declarationOrder.containsKey(classAlias)) {
                declarationOrder.put(classAlias, declarationOrder.size());
            }
        }

        /** 目标比源更早声明则为环引用，画虚线；任一方未声明时按实线处理。 */
        boolean isBackEdge(String fromAlias, String targetAlias) {
            Integer from = declarationOrder.get(fromAlias);
            Integer target = declarationOrder.get(targetAlias);
            return from != null && target != null && target < from;
        }

        /** @return 是否是一条新边（重复边直接丢弃，避免折叠后同一关系画出多条） */
        boolean addLink(String fromAlias, String memberName, String targetAlias, String label) {
            // 标签参与去重：同字段同目标但标签不同（如 Map 的 key/value 指向同一类型）是语义不同的两条边
            return emittedLinks.add(fromAlias + '|' + memberName + '|' + targetAlias + '|' + label);
        }
    }
}
