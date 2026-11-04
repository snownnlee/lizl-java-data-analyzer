package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.util.CommonUtil;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

class ClassModel extends RunningConfigCapable {

    final String id;

    final ClassModelsManager classModelsManager;

    final Class<?> sourceClass;

    final Object sourceClassInstance;

    final int depth;

    final List<FieldModel> fieldModels = new LinkedList<>();

    ClassModel superClassModel;

    boolean isComplex;

    private int fieldIdNumber = 0;

    ClassModel(RunningConfig runningConfig, ClassModelsManager classModelsManager, Class<?> sourceClass, Object sourceClassInstance, int depth) {
        super(runningConfig);
        this.id = classModelsManager.generateClassId();
        this.sourceClass = sourceClass;
        this.sourceClassInstance = sourceClassInstance;
        this.classModelsManager = classModelsManager;
        this.depth = depth;
        if (sourceClass != null) {
            this.classModelsManager.add(this);
            analyzing();
            completeLinkClassModels();
        }
    }

    private void analyzing() {
        if (shouldAnalyze(sourceClass, false)) {
            analyzeFields();
            analyzeSuperClass();
        } else if (sourceClassInstance != null) {
            // 自身节点必须先建完再注销登记：反过来会让自引用的集合/映射找不到自己这个节点而重建成新节点，
            // 而新建走「按根建节点」分支时深度被写死为 1，深度上限失效，一路递归到栈溢出。
            fieldModels.add(new FieldModel.SelfFieldModel<>(runningConfig, sourceClassInstance, this));
            if (shouldAddToStringField()) {
                fieldModels.add(new FieldModel.ToStringFieldModel(runningConfig, sourceClassInstance.toString(), this));
            }
            classModelsManager.remove(this);
        }
    }

    private void analyzeSuperClass() {
        Class<?> superClass = sourceClass.getSuperclass();
        if (shouldAnalyze(superClass, sourceClassInstance != null)) {
            superClassModel = getClassModel(superClass, sourceClassInstance, this.depth);
        }
    }

    private void analyzeFields() {
        Field[] declaredFields;
        try {
            declaredFields = sourceClass.getDeclaredFields();
        } catch (Throwable e) {
            String errorMessage = "错误：" + e.getClass().getName() + sourceClass.getName() + "获取声明字段列表发生异常：" + e.getMessage();
            fieldModels.add(new FieldModel.InfoFieldModel(runningConfig, this, errorMessage));
            return;
        }
        boolean inaccessibleHintAdded = false;
        for (Field field : declaredFields) {
            if (shouldAnalyze(field)) {
                Object sourceFieldValue = null;
                try {
                    if (Modifier.isStatic(field.getModifiers())) {
                        sourceFieldValue = readFieldValue(field, sourceClass);
                    } else if (sourceClassInstance != null) {
                        sourceFieldValue = readFieldValue(field, sourceClassInstance);
                    }
                    fieldModels.add(new FieldModel(runningConfig, field, sourceFieldValue, this));
                } catch (IllegalAccessException e) {
                    // 模块封装：JDK 16 起默认拒绝反射读 JDK 内部私有成员，属运行环境限制而非分析错误，
                    // 字段照常展示、只是没有值。同类只提示一次，避免几十个字段刷满报告。
                    fieldModels.add(new FieldModel(runningConfig, field, null, this));
                    if (!inaccessibleHintAdded) {
                        fieldModels.add(new FieldModel.InfoFieldModel(runningConfig, this, buildInaccessibleHint()));
                        inaccessibleHintAdded = true;
                    }
                } catch (Exception e) {
                    String errorMessage = "错误：获取字段值 " + e.getClass().getName() + "." + field.getName() + " 时发生异常 ---> " + e.getMessage();
                    fieldModels.add(new FieldModel.InfoFieldModel(runningConfig, this, errorMessage));
                }
            }
        }
        if (sourceClassInstance != null && shouldAddToStringField()) {
            fieldModels.add(new FieldModel.ToStringFieldModel(runningConfig, sourceClassInstance.toString(), this));
        }
    }

    /**
     * 是否补一个 {@code toString} 字段：配置开启、非本库内部类，且（未开脱敏或该类不含敏感字段名）。
     *
     * <p>{@code toString()} 是整对象字符串，渲染层的按字段名脱敏对它无效，所以开了脱敏就跳过
     * 含敏感字段的对象，避免凭证借这一行明文落盘。
     */
    private boolean shouldAddToStringField() {
        return runningConfig.addToStringField
                && CommonUtil.isNotProjectInnerClass(sourceClass)
                && !(runningConfig.maskSensitiveValues && FieldModel.hasSensitiveField(sourceClass));
    }

    private boolean shouldAnalyze(Field field) {
        if (field.getDeclaringClass().isEnum()) {
            return true;
        }
        return !Modifier.isStatic(field.getModifiers()) || runningConfig.analyzeStaticField;
    }

    /**
     * 读字段值。
     *
     * <p>{@code setAccessible(true)} 在 JDK 16+ 对未开放模块会抛 {@code InaccessibleObjectException}，
     * 那是 JDK 9 才有的类型，target 1.8 无法直接 catch，只能按 {@link RuntimeException} 接住后忽略；
     * 到底能不能读交给后续 {@code get} 判定。
     */
    private static Object readFieldValue(Field field, Object target) throws IllegalAccessException {
        try {
            field.setAccessible(true);
        } catch (RuntimeException ignored) {
            // 模块未开放或安全管理器拒绝，交由下面的 get 判定到底能不能读
        }
        return field.get(target);
    }

    /** 模块封装的提示文案：给出可直接加的启动参数，避免用户以为这是本工具的 bug */
    private String buildInaccessibleHint() {
        String moduleAndPackage = getModuleAndPackageName();
        String base = "提示：该类部分字段因模块封装无法反射读取（JDK 16+ 默认拒绝，属运行环境限制，非分析错误），"
                + "字段本身仍会展示，只是没有值";
        if (moduleAndPackage == null) {
            // JDK 8 没有模块系统，--add-opens 不存在；此时只能是安全管理器拒绝了反射
            return base + "；当前运行环境无模块系统（JDK 8），请检查安全管理器是否拒绝了反射。";
        }
        return base + "；如需读取可加启动参数 --add-opens " + moduleAndPackage + "=ALL-UNNAMED";
    }

    /**
     * 拼出 {@code 模块名/包名}，用于 {@code --add-opens}；没有模块系统（JDK 8）时返回 {@code null}。
     *
     * <p>未命名模块（classpath 应用）的 {@code Module#getName()} 返回 {@code null}，模块名要写
     * {@code ALL-UNNAMED}——不能兜底成 {@code java.base}，否则参数指向的模块里根本没有这个类。
     */
    @SuppressWarnings("JavaReflectionMemberAccess")
    private String getModuleAndPackageName() {
        String packageName = sourceClass.getPackage() != null ? sourceClass.getPackage().getName() : "";
        try {
            Object module = Class.class.getMethod("getModule").invoke(sourceClass);
            Object name = module.getClass().getMethod("getName").invoke(module);
            return (name == null ? "ALL-UNNAMED" : String.valueOf(name)) + "/" + packageName;
        } catch (Throwable ignored) {
            // JDK 8：Class#getModule 不存在
            return null;
        }
    }

    boolean shouldAnalyze(Class<?> clazz, boolean considerInstance) {
        if (clazz == null || clazz == Object.class) {
            return false;
        }
        if (considerInstance && isCollectionOrMap(clazz)) {
            return true;
        }
        if (clazz.isPrimitive()) {
            return false;
        }
        String clazzName = clazz.getName();
        for (String includePackage : runningConfig.commonIncludePackages) {
            if (includePackage != null && clazzName.startsWith(includePackage)) {
                return true;
            }
        }
        for (String excludePackage : runningConfig.commonExcludePackages) {
            if (excludePackage != null && clazzName.startsWith(excludePackage)) {
                return false;
            }
        }
        for (String includePackage : GlobalConfig.commonIncludePackages) {
            if (includePackage != null && clazzName.startsWith(includePackage)) {
                return true;
            }
        }
        for (String excludePackage : GlobalConfig.commonExcludePackages) {
            if (excludePackage != null && clazzName.startsWith(excludePackage)) {
                return false;
            }
        }
        return true;
    }

    private boolean isCollectionOrMap(Class<?> clazz) {
        try {
            return Collection.class.isAssignableFrom(clazz)
                    || Enumeration.class.isAssignableFrom(clazz)
                    || Iterator.class.isAssignableFrom(clazz)
                    || Map.class.isAssignableFrom(clazz)
                    || clazz.getDeclaringClass() != null && isCollectionOrMap(clazz.getDeclaringClass());
        } catch (Throwable throwable) {
            return false;
        }
    }

    ClassModel getClassModel(Class<?> clazz, Object clazzInstance, int nextDepth) {
        if (clazz.isInterface()) {
            return null;
        }
        ClassModel exitedClassModel = classModelsManager.findClassModelByClassAndInstance(clazz, clazzInstance);
        if (exitedClassModel != null) {
            return new LinkClassModel(exitedClassModel, this.depth + 1);
        }
        // 总量/耗时上限先于深度判定：触顶后必须立刻收敛，否则「深度没到但节点爆炸」照样能跑几分钟
        String budgetWarning = classModelsManager.requestNodeBudget();
        if (budgetWarning != null) {
            return new InfoClassModel(runningConfig, classModelsManager, buildLimitWarning(budgetWarning, clazz, clazzInstance), this.depth + 1);
        }
        if (reachedMaxDepth(nextDepth)) {
            return new InfoClassModel(runningConfig, classModelsManager, buildLimitWarning("已达到最大分析深度" + runningConfig.maxDepth, clazz, clazzInstance), this.depth + 1);
        }
        return new ClassModel(runningConfig, classModelsManager, clazz, clazzInstance, nextDepth);
    }

    private String buildLimitWarning(String reason, Class<?> clazz, Object clazzInstance) {
        Class<?> actualAnalysisClass = getActualAnalysisClass(clazz, clazzInstance);
        String warningInfo = "警告：" + reason;
        if (actualAnalysisClass != null) {
            warningInfo += "，未解析类型 " + actualAnalysisClass.getName();
        }
        return warningInfo;
    }

    private Class<?> getActualAnalysisClass(Class<?> clazz, Object clazzInstance) {
        if (clazzInstance == null) {
            return clazz;
        }
        return FieldModel.resolveActualAnalysisClass(clazzInstance, this);
    }

    boolean reachedMaxDepth(int nextDepth) {
        return nextDepth > runningConfig.maxDepth;
    }

    List<Class<?>> selectAnalyzedClasses(List<Class<?>> classList) {
        List<Class<?>> result = new LinkedList<>();
        for (Class<?> clazz : classList) {
            if (classModelsManager.hasClassModel(clazz)) {
                result.add(clazz);
            }
        }
        return result;
    }

    /**
     * 补链：把分析当时还无解的字段补成链接或子节点。
     *
     * @return 本次是否真的改动过。补链会新建节点，可能让其他根上的待补字段变得可解，调用方据此决定是否再跑一轮
     */
    boolean completeLinkClassModels() {
        boolean changed = false;
        for (FieldModel fieldModel : fieldModels) {
            changed |= fieldModel.completeLinkClassModels();
        }
        if (superClassModel != null) {
            changed |= superClassModel.completeLinkClassModels();
        }
        return changed;
    }

    String generateFieldId() {
        return this.id + "F" + ++fieldIdNumber;
    }

    String getClassSimpleName() {
        return sourceClass != null ? sourceClass.getSimpleName() : null;
    }

    void determineIsComplex() {
        // 置位是幂等的：isComplex 已为 true 说明本节点的子树已在此前某次遍历中处理完，直接短路，
        // 共享 DAG（同一节点被多个父指向）不再指数级重复递归
        if (isComplex) {
            return;
        }
        for (FieldModel fieldModel : fieldModels) {
            for (ClassModel fieldTypeClassesModel : fieldModel.fieldTypeClassesModels) {
                if (fieldTypeClassesModel instanceof LinkClassModel) {
                    continue;
                }
                isComplex = true;
                fieldTypeClassesModel.determineIsComplex();
            }
        }
        if (superClassModel != null) {
            isComplex = true;
            superClassModel.determineIsComplex();
        }
    }

    static class LinkClassModel extends ClassModel {

        final ClassModel originClassModel;

        LinkClassModel(ClassModel originClassModel, int depth) {
            super(originClassModel.runningConfig, originClassModel.classModelsManager, null, null, depth);
            this.originClassModel = originClassModel;
        }

        @Override
        boolean completeLinkClassModels() {
            return false;
        }

        @Override
        String getClassSimpleName() {
            return originClassModel.getClassSimpleName();
        }
    }

    static class InfoClassModel extends ClassModel {

        final String info;

        InfoClassModel(RunningConfig runningConfig, ClassModelsManager classModelsManager, String info, int depth) {
            super(runningConfig, classModelsManager, null, null, depth);
            this.info = info;
        }

        @Override
        boolean completeLinkClassModels() {
            return false;
        }
    }
}
