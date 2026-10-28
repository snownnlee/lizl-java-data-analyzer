package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.api.AnalysisMode;
import cn.lizl.project.tool.analysis.util.CommonUtil;

import java.lang.reflect.*;
import java.util.*;

class FieldModel extends RunningConfigCapable {

    private static final Class<?>[] EMPTY_CLASS_ARRAY = new Class<?>[0];

    final String id;

    final Field sourceField;

    final Object sourceFieldValue;

    final ClassModel parentClassModel;

    final List<ClassModel> fieldTypeClassesModels = new LinkedList<>();

    final List<Class<?>> ignoredAnalyzingClasses = new LinkedList<>();

    /**
     * 简单视图下被包排除规则挡掉的集合元素显示值（按 collectionsAnalyzingCount 截取）。
     * 分析不建节点符合设计，但渲染端要能把这些值显示出来，否则 List&lt;String&gt; 之类只剩个数标记。
     */
    private List<String> simpleViewElementValues;

    FieldModel(RunningConfig runningConfig, Field sourceField, Object sourceFieldValue, ClassModel parentClassModel) {
        super(runningConfig);
        this.id = parentClassModel.generateFieldId();
        this.sourceField = sourceField;
        this.sourceFieldValue = sourceFieldValue;
        this.parentClassModel = parentClassModel;
        if (sourceField != null) {
            analyzing();
            completeLinkClassModels();
        }
    }

    protected void analyzing() {
        if (sourceField.getType().isPrimitive()) {
            return;
        }
        if (parentClassModel.sourceClass.isEnum()) {
            return;
        }
        if (runningConfig.analysisMode == AnalysisMode.ONLY_CLASS) {
            analyzeFieldClasses(findAnalyzableClasses(getGenericType()));
        } else if (runningConfig.analysisMode == AnalysisMode.ONLY_INSTANCE) {
            if (sourceFieldValue != null) {
                analyzeSourceValue(sourceFieldValue);
            }
        } else if (runningConfig.analysisMode == AnalysisMode.AUTO_BY_CONFIG) {
            if (sourceFieldValue != null) {
                analyzeSourceValue(sourceFieldValue);
            } else if (runningConfig.shouldAnalyzeNullValue) {
                analyzeFieldClasses(findAnalyzableClasses(getGenericType()));
            }
        } else if (runningConfig.analysisMode == AnalysisMode.AUTO_BY_COMPUTE) {
            if (parentClassModel.sourceClassInstance != null) {
                if (sourceFieldValue != null) {
                    analyzeSourceValue(sourceFieldValue);
                }
            } else {
                analyzeFieldClasses(findAnalyzableClasses(getGenericType()));
            }
        }
    }

    private void analyzeSourceValue(Object sourceValue) {
        if (sourceValue.getClass().isArray()) {
            if (!sourceValue.getClass().getComponentType().isPrimitive()) {
                Object[] arrayValue = (Object[]) sourceValue;
                int count = 0;
                for (int i = 0; i < arrayValue.length && count < runningConfig.collectionsAnalyzingCount; i++) {
                    Object item = arrayValue[i];
                    if (item != null) {
                        analyzeCollectionElement(item, new ArrayItem<>(i, item));
                        count++;
                    }
                }
            }
            return;
        }
        if (runningConfig.enableCollectionSimpleView) {
            if (sourceValue instanceof List) {
                if (shouldAnalyzeAsCollection(sourceValue)) {
                    List<?> listValue = (List<?>) sourceValue;
                    int count = 0;
                    for (int i = 0; i < listValue.size() && count < runningConfig.collectionsAnalyzingCount; i++) {
                        Object item = listValue.get(i);
                        if (item != null) {
                            analyzeCollectionElement(item, new ListItem<>(i, item));
                            count++;
                        }
                    }
                    return;
                }
            }
            if (sourceValue instanceof Set) {
                if (shouldAnalyzeAsCollection(sourceValue)) {
                    Set<?> setValue = (Set<?>) sourceValue;
                    Iterator<?> iterator = setValue.iterator();
                    int count = 0;
                    while (iterator.hasNext() && count < runningConfig.collectionsAnalyzingCount) {
                        Object item = iterator.next();
                        if (item != null) {
                            analyzeCollectionElement(item, new SetItem<>(item));
                            count++;
                        }
                    }
                    return;
                }
            }
            if (sourceValue instanceof Map) {
                if (shouldAnalyzeAsCollection(sourceValue)) {
                    Map<?, ?> mapValue = (Map<?, ?>) sourceValue;
                    Iterator<? extends Map.Entry<?, ?>> iterator = mapValue.entrySet().iterator();
                    int count = 0;
                    while (iterator.hasNext() && count < runningConfig.collectionsAnalyzingCount) {
                        Map.Entry<?, ?> entry = iterator.next();
                        if (entry.getKey() != null && entry.getValue() != null) {
                            analyzeMapEntry(entry);
                            count++;
                        }
                    }
                    return;
                }
            }
            if (sourceValue instanceof Queue) {
                if (shouldAnalyzeAsCollection(sourceValue)) {
                    Queue<?> queueValue = (Queue<?>) sourceValue;
                    Iterator<?> iterator = queueValue.iterator();
                    int count = 0;
                    while (iterator.hasNext() && count < runningConfig.collectionsAnalyzingCount) {
                        Object item = iterator.next();
                        if (item != null) {
                            analyzeCollectionElement(item, new QueueItem<>(item));
                            count++;
                        }
                    }
                    return;
                }
            }
            if (sourceValue instanceof Enumeration) {
                if (shouldAnalyzeAsCollection(sourceValue)) {
                    Enumeration<?> enumerationValue = (Enumeration<?>) sourceValue;
                    int count = 0;
                    while (enumerationValue.hasMoreElements() && count < runningConfig.collectionsAnalyzingCount) {
                        Object item = enumerationValue.nextElement();
                        if (item != null) {
                            analyzeCollectionElement(item, new EnumerationItem<>(item));
                            count++;
                        }
                    }
                    return;
                }
            }
            if (sourceValue instanceof Iterator) {
                if (shouldAnalyzeAsCollection(sourceValue)) {
                    Iterator<?> iteratorValue = (Iterator<?>) sourceValue;
                    int count = 0;
                    while (iteratorValue.hasNext() && count < runningConfig.collectionsAnalyzingCount) {
                        Object item = iteratorValue.next();
                        if (item != null) {
                            analyzeCollectionElement(item, new IteratorItem<>(item));
                            count++;
                        }
                    }
                    return;
                }
            }
        }
        if (parentClassModel.shouldAnalyze(sourceValue.getClass(), true)) {
            addClassModelForSourceValue(sourceValue);
        }
    }

    private void addClassModelForSourceValue(Object sourceValue) {
        ClassModel classModel = parentClassModel.getClassModel(sourceValue.getClass(), sourceValue, parentClassModel.depth + 1);
        if (classModel == null) {
            return;
        }
        if (classModel instanceof ClassModel.LinkClassModel
                && ((ClassModel.LinkClassModel) classModel).originClassModel == parentClassModel
                && !parentClassModel.shouldAnalyze(parentClassModel.sourceClass, false)) {
            // 值节点（java.* 这类不展开的类）的 [this] 字段指回它自己，再挂一条指向自身节点的链接没有信息量；
            // 可分析类的自引用不在此列，那正是用来表达环的。
            return;
        }
        fieldTypeClassesModels.add(classModel);
    }

    /**
     * 简单视图下的元素分流：能建节点的（业务对象、嵌套集合）走原有包装分析；被包排除规则挡掉的
     * JDK 简单类型元素（String/Integer 等）收集其显示值，供渲染端在字段行内联展示。
     */
    private void analyzeCollectionElement(Object item, Object wrapper) {
        if (parentClassModel.shouldAnalyze(item.getClass(), true)) {
            analyzeSourceValue(wrapper);
        } else {
            collectSimpleValue(item);
        }
    }

    private void analyzeMapEntry(Map.Entry<?, ?> entry) {
        if (parentClassModel.shouldAnalyze(entry.getValue().getClass(), true)) {
            analyzeSourceValue(new MapEntry<>(entry));
        } else {
            collectSimpleValue(entry.getKey() + "=" + entry.getValue());
        }
    }

    private void collectSimpleValue(Object display) {
        if (simpleViewElementValues == null) {
            simpleViewElementValues = new ArrayList<>();
        }
        simpleViewElementValues.add(CommonUtil.getStringFrom(display, runningConfig.valueDisplayLengthLimit));
    }

    /** 简单视图下被排除元素的显示值；非集合元素字段为 {@code null} */
    List<String> simpleViewElementValues() {
        return simpleViewElementValues;
    }

    boolean shouldAnalyzeAsCollection(Object instance) {
        return instance.getClass().getName().startsWith("java.");
    }

    private void analyzeFieldClasses(Class<?>... analyzableClasses) {
        for (Class<?> analyzableClass : analyzableClasses) {
            ClassModel classModel = parentClassModel.getClassModel(analyzableClass, null, parentClassModel.depth + 1);
            if (classModel != null) {
                fieldTypeClassesModels.add(classModel);
            }
        }
    }

    private Class<?>[] findAnalyzableClasses(Type type) {
        Set<Class<?>> ignoredClasses = new LinkedHashSet<>(2);
        Class<?>[] result = resolveAnalyzableClasses(type, ignoredClasses);
        if (!ignoredClasses.isEmpty()) {
            ignoredAnalyzingClasses.addAll(ignoredClasses);
        }
        return result;
    }

    /**
     * 判定一个类型能否分析，并把判定过程中被忽略的类收进 {@code ignoredSink}。结果按分析任务缓存在
     * {@link ClassModelsManager} 上，命中时一并重放被忽略的类，否则未解析类型的提示会随缓存命中而丢失。
     *
     * <p>每层用独立的 {@code localIgnored}：共用父层集合会让先处理的兄弟节点把忽略项写进后处理兄弟的缓存。
     */
    private Class<?>[] resolveAnalyzableClasses(Type type, Set<Class<?>> ignoredSink) {
        ClassModelsManager classModelsManager = parentClassModel.classModelsManager;
        ClassModelsManager.TypeAnalysis cached = classModelsManager.getTypeAnalysis(type);
        if (cached != null) {
            ignoredSink.addAll(cached.ignoredClasses);
            return cached.analyzableClasses;
        }

        Set<Class<?>> localIgnored = new LinkedHashSet<>(2);
        Class<?>[] result = EMPTY_CLASS_ARRAY;
        if (type instanceof Class) {
            Class<?> typeClass = (Class<?>) type;
            if (typeClass.isArray()) {
                Class<?> componentType = typeClass.getComponentType();
                if (parentClassModel.shouldAnalyze(componentType, false)) {
                    result = resolveAnalyzableClasses(componentType, localIgnored);
                } else {
                    localIgnored.add(componentType);
                }
            } else {
                if (parentClassModel.shouldAnalyze(typeClass, false)) {
                    result = new Class<?>[]{typeClass};
                } else {
                    localIgnored.add(typeClass);
                }
            }
        } else if (type instanceof ParameterizedType) {
            Set<Class<?>> classes = new LinkedHashSet<>(2);

            classes.addAll(Arrays.asList(resolveAnalyzableClasses(((ParameterizedType) type).getRawType(), localIgnored)));

            Type[] typeArguments = ((ParameterizedType) type).getActualTypeArguments();
            for (Type typeArgument : typeArguments) {
                classes.addAll(Arrays.asList(resolveAnalyzableClasses(typeArgument, localIgnored)));
            }

            result = classes.toArray(EMPTY_CLASS_ARRAY);
        } else if (type instanceof WildcardType) {
            Set<Class<?>> classes = new LinkedHashSet<>(2);

            WildcardType wildcardType = (WildcardType) type;
            for (Type typeArgument : wildcardType.getLowerBounds()) {
                classes.addAll(Arrays.asList(resolveAnalyzableClasses(typeArgument, localIgnored)));
            }
            for (Type typeArgument : wildcardType.getUpperBounds()) {
                classes.addAll(Arrays.asList(resolveAnalyzableClasses(typeArgument, localIgnored)));
            }

            result = classes.toArray(EMPTY_CLASS_ARRAY);
        } else if (type instanceof GenericArrayType) {
            result = resolveAnalyzableClasses(((GenericArrayType) type).getGenericComponentType(), localIgnored);
        }

        classModelsManager.putTypeAnalysis(type, new ClassModelsManager.TypeAnalysis(result, localIgnored));
        ignoredSink.addAll(localIgnored);
        return result;
    }

    /**
     * 补链：收尾字段侧两类「当时无解」的情形——类型判定被跳过的类后来被别的根分析了则补建子节点；
     * 字段有值但当时该实例还没有节点则补一个链接节点。
     *
     * @return 本次是否真的改动了什么
     */
    boolean completeLinkClassModels() {
        boolean changed = false;
        if (!ignoredAnalyzingClasses.isEmpty()) {
            List<Class<?>> analyzedClasses = parentClassModel.selectAnalyzedClasses(ignoredAnalyzingClasses);
            if (!analyzedClasses.isEmpty()) {
                ignoredAnalyzingClasses.removeAll(analyzedClasses);
                analyzeFieldClasses(analyzedClasses.toArray(EMPTY_CLASS_ARRAY));
                changed = true;
            }
        }
        if (fieldTypeClassesModels.isEmpty()) {
            if (parentClassModel.sourceClass.isEnum()) {
                return changed;
            }
            if (sourceFieldValue != null) {
                ClassModel existedClassModel = parentClassModel.classModelsManager.findClassModelByInstance(sourceFieldValue);
                if (existedClassModel != null) {
                    fieldTypeClassesModels.add(new ClassModel.LinkClassModel(existedClassModel, parentClassModel.depth + 1));
                    changed = true;
                }
            }
        } else {
            for (ClassModel fieldClassModel : fieldTypeClassesModels) {
                changed |= fieldClassModel.completeLinkClassModels();
            }
        }
        return changed;
    }

    Type getGenericType() {
        try {
            Type genericType = sourceField.getGenericType();
            if ((genericType instanceof TypeVariable || genericType instanceof WildcardType) && sourceFieldValue != null) {
                return sourceFieldValue.getClass();
            }
            return genericType;
        } catch (TypeNotPresentException e) {
            return sourceField.getType();
        }
    }

    static Class<?> resolveActualAnalysisClass(Object clazzInstance, ClassModel classModel) {
        if (clazzInstance == null) {
            return null;
        }
        Class<?> clazz = clazzInstance.getClass();
        if (clazz == ArrayItem.class) {
            return resolveActualAnalysisClass(((ArrayItem<?>) clazzInstance).element, classModel);
        }
        if (clazz == ListItem.class) {
            return resolveActualAnalysisClass(((ListItem<?>) clazzInstance).element, classModel);
        }
        if (clazz == SetItem.class) {
            return resolveActualAnalysisClass(((SetItem<?>) clazzInstance).element, classModel);
        }
        if (clazz == MapEntry.class) {
            Class<?> actualAnalysisClass = resolveActualAnalysisClass(((MapEntry<?, ?>) clazzInstance).key, classModel);
            if (actualAnalysisClass != null) {
                return actualAnalysisClass;
            }
            return resolveActualAnalysisClass(((MapEntry<?, ?>) clazzInstance).value, classModel);
        }
        if (clazz == QueueItem.class) {
            return resolveActualAnalysisClass(((QueueItem<?>) clazzInstance).element, classModel);
        }
        if (clazz == EnumerationItem.class) {
            return resolveActualAnalysisClass(((EnumerationItem<?>) clazzInstance).element, classModel);
        }
        if (clazz == IteratorItem.class) {
            return resolveActualAnalysisClass(((IteratorItem<?>) clazzInstance).element, classModel);
        }
        if (classModel.shouldAnalyze(clazz, false)) {
            return clazz;
        }
        return null;
    }

    String getFieldName() {
        return sourceField.getName();
    }

    /**
     * 字段类型串（不带包名），HTML 与 PUML 的字段类型列共用。
     *
     * <p>不能对 {@link Type#toString()} 做正则去包名：数组会被输出成 {@code class [Lcom.foo.Bar;}，
     * 剥掉包名后只剩 {@code [Bar;}。这里按 Type 的种类递归拼接，数组还原成 {@code Bar[]}。
     */
    @SuppressWarnings("JavadocReference")
    String getFieldType() {
        return shortTypeName(getGenericType());
    }

    private static String shortTypeName(Type type) {
        if (type instanceof Class) {
            Class<?> clazz = (Class<?>) type;
            if (clazz.isArray()) {
                return shortTypeName(clazz.getComponentType()) + "[]";
            }
            String simpleName = clazz.getSimpleName();
            if (simpleName.isEmpty()) {
                // 匿名类 / 局部类的简单名为空，退回全限定名，避免渲染出空类型
                return clazz.getName();
            }
            Class<?> declaringClass = clazz.getDeclaringClass();
            // 内部类保留 Outer$Inner 写法，与只剥包名的原有口径一致
            return declaringClass == null ? simpleName : shortTypeName(declaringClass) + "$" + simpleName;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            StringBuilder result = new StringBuilder(shortTypeName(parameterizedType.getRawType())).append('<');
            Type[] typeArguments = parameterizedType.getActualTypeArguments();
            for (int i = 0; i < typeArguments.length; i++) {
                if (i > 0) {
                    result.append(", ");
                }
                result.append(shortTypeName(typeArguments[i]));
            }
            return result.append('>').toString();
        }
        if (type instanceof GenericArrayType) {
            return shortTypeName(((GenericArrayType) type).getGenericComponentType()) + "[]";
        }
        if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            Type[] lowerBounds = wildcardType.getLowerBounds();
            if (lowerBounds.length > 0) {
                return "? super " + shortTypeName(lowerBounds[0]);
            }
            Type[] upperBounds = wildcardType.getUpperBounds();
            if (upperBounds.length > 0 && upperBounds[0] != Object.class) {
                return "? extends " + shortTypeName(upperBounds[0]);
            }
            return "?";
        }
        // TypeVariable 等：getTypeName() 就是变量名本身（T / K / V）
        return type.getTypeName();
    }

    static class ArrayItem<E> {

        final int index;
        final E element;

        ArrayItem(int index, E element) {
            this.index = index;
            this.element = element;
        }
    }

    static class ListItem<E> {

        final int index;
        final E element;

        ListItem(int index, E element) {
            this.index = index;
            this.element = element;
        }
    }

    static class SetItem<E> {

        final E element;

        SetItem(E element) {
            this.element = element;
        }
    }

    static class MapEntry<K, V> {

        final K key;
        final V value;

        MapEntry(Map.Entry<K, V> entry) {
            this.key = entry.getKey();
            this.value = entry.getValue();
        }
    }

    static class QueueItem<E> {

        final E element;

        QueueItem(E element) {
            this.element = element;
        }
    }

    static class EnumerationItem<E> {

        final E element;

        EnumerationItem(E element) {
            this.element = element;
        }
    }

    static class IteratorItem<E> {

        final E element;

        IteratorItem(E element) {
            this.element = element;
        }
    }

    static class SelfFieldModel<T> extends FieldModel {

        public T __SELF__;

        static final Field FIELD__SELF__;

        static {
            try {
                FIELD__SELF__ = SelfFieldModel.class.getField("__SELF__");
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        }

        SelfFieldModel(RunningConfig runningConfig, Object sourceFieldValue, ClassModel parentClassModel) {
            super(runningConfig, FIELD__SELF__, sourceFieldValue, parentClassModel);
        }

        @Override
        boolean shouldAnalyzeAsCollection(Object instance) {
            return true;
        }

        @Override
        boolean completeLinkClassModels() {
            return false;
        }

        @Override
        Type getGenericType() {
            return parentClassModel.sourceClass;
        }

        @Override
        String getFieldName() {
            return "[this]";
        }
    }

    static class ToStringFieldModel extends FieldModel {

        public String __TO_STRING__;

        static final Field FIELD__TO_STRING__;

        static {
            try {
                FIELD__TO_STRING__ = ToStringFieldModel.class.getField("__TO_STRING__");
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        }

        ToStringFieldModel(RunningConfig runningConfig, String sourceFieldValue, ClassModel parentClassModel) {
            super(runningConfig, FIELD__TO_STRING__, sourceFieldValue, parentClassModel);
        }

        @Override
        protected void analyzing() {
        }

        @Override
        boolean completeLinkClassModels() {
            return false;
        }

        @Override
        String getFieldName() {
            return "[toString()]";
        }
    }

    static class InfoFieldModel extends FieldModel {

        final String info;

        InfoFieldModel(RunningConfig runningConfig, ClassModel parentClassModel, String info) {
            super(runningConfig, null, null, parentClassModel);
            this.info = info;
        }

        @Override
        boolean completeLinkClassModels() {
            return false;
        }
    }
}
