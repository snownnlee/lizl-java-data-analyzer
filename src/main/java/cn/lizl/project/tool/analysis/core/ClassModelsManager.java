package cn.lizl.project.tool.analysis.core;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ClassModelsManager extends RunningConfigCapable {

    /**
     * 实例 → 该实例建过的节点。
     *
     * <p>必须是 {@link IdentityHashMap}：语义是「同一个对象」而非「相等的对象」。用普通 {@code HashMap}
     * 会把值相等的不同实例误判为已分析，且会对动态代理等对象调用 {@code hashCode()} 而抛异常。
     */
    private final Map<Object, List<ClassModel>> instanceClassModels = new IdentityHashMap<>(128);

    /**
     * 类 → 按类型分析建出的节点。
     *
     * <p>与按实例表分开，避免按实例查找时扫描同类节点列表，退化为 O(n²)。
     */
    private final Map<Class<?>, List<ClassModel>> typedClassModels = new HashMap<>(128);

    /** 类 → 节点数，只用于存在性判断。 */
    private final Map<Class<?>, Integer> classModelCounts = new HashMap<>(128);

    /**
     * 分析根索引：类 → (实例 → 下标)，使根查找也是 O(1)。
     *
     * <p>必须同时用类和实例定位：只按实例查会把同一实例的父类节点误认成它的根。
     */
    private final Map<Class<?>, Map<Object, Integer>> rootIndexes = new HashMap<>();

    /**
     * 类型判定结果缓存，生命周期与本次分析任务一致。
     *
     * <p>不能做成静态字段：判定结果依赖当前包名配置，静态缓存不失效会让下一次分析沿用上一次的结论，
     * 导致字段被静默跳过。
     */
    private final Map<Type, TypeAnalysis> typeAnalysisCache = new HashMap<>(64);

    private final ClassModel[] classModelArray;

    private int classIdNumber = 0;

    /** 本次分析已新建的节点数，用于 {@code maxNodeCount} 限额。 */
    private int nodeCount = 0;

    /** 本次分析的截止时刻（{@link System#nanoTime()} 口径），{@code 0} 表示不限时。 */
    private final long analysisDeadline;

    ClassModelsManager(RunningConfig runningConfig, List<Analyzable> analyzableCollection) {
        super(runningConfig);
        Analyzable[] analyzableArray = analyzableCollection.toArray(new Analyzable[0]);
        classModelArray = new ClassModel[analyzableArray.length];
        for (int i = 0; i < analyzableArray.length; i++) {
            Analyzable analyzable = analyzableArray[i];
            Map<Object, Integer> byInstance = rootIndexes.computeIfAbsent(analyzable.clazz, k -> new IdentityHashMap<>());
            // 同一个根被重复登记时，下标小的才是原先按下标查找命中的那个
            byInstance.putIfAbsent(analyzable.instance, i);
        }
        analysisDeadline = runningConfig.analysisTimeoutMillis > 0
                ? System.nanoTime() + runningConfig.analysisTimeoutMillis * 1000000L
                : 0L;
    }

    /**
     * 为即将新建的节点申请名额。深度与集合取项数都封不住总量，这里是唯一的兜底。
     *
     * @return {@code null} 表示还有余量；否则返回拦截原因，调用方据此降级为提示节点
     */
    String requestNodeBudget() {
        if (runningConfig.maxNodeCount > 0 && nodeCount >= runningConfig.maxNodeCount) {
            return "已达到最大分析节点数" + runningConfig.maxNodeCount;
        }
        if (analysisDeadline != 0L && System.nanoTime() - analysisDeadline >= 0) {
            return "已超过最大分析耗时" + runningConfig.analysisTimeoutMillis + "ms";
        }
        nodeCount++;
        return null;
    }

    String generateClassId() {
        return "C" + ++classIdNumber;
    }

    void add(ClassModel classModel) {
        if (classModel == null) {
            return;
        }
        Class<?> clazz = classModel.sourceClass;
        Object instance = classModel.sourceClassInstance;
        if (clazz != null) {
            classModelCounts.compute(clazz, (k, count) -> count == null ? 1 : count + 1);
        }
        if (instance != null) {
            instanceClassModels.computeIfAbsent(instance, k -> new ArrayList<>(2)).add(classModel);
        } else if (clazz != null) {
            typedClassModels.computeIfAbsent(clazz, k -> new ArrayList<>(2)).add(classModel);
        }
    }

    /**
     * 查「这个类的这个实例」是否已有节点，两步均为 O(1)：先查索引，再查它是否为分析根。
     */
    ClassModel findClassModelByClassAndInstance(Class<?> clazz, Object objectInstance) {
        if (clazz == null) {
            return null;
        }
        ClassModel existedClassModel;
        if (objectInstance == null) {
            existedClassModel = findTypedClassModel(clazz);
        } else {
            existedClassModel = findInstanceClassModel(clazz, objectInstance);
        }
        if (existedClassModel != null) {
            return existedClassModel;
        }
        Map<Object, Integer> byInstance = rootIndexes.get(clazz);
        Integer rootIndex = byInstance == null ? null : byInstance.get(objectInstance);
        if (rootIndex == null) {
            return null;
        }
        ClassModel createdRoot = classModelArray[rootIndex];
        if (createdRoot != null) {
            // 该类不可分析（如 java.*）时根会在 analyzing 里注销登记，索引查不到但节点已建，必须复用：
            // 新建节点会把深度写死为 1，使深度判定失效。
            return createdRoot;
        }
        return classModelArray[rootIndex] = new ClassModel(runningConfig, this, clazz, objectInstance, 1);
    }

    private ClassModel findTypedClassModel(Class<?> clazz) {
        List<ClassModel> classModels = typedClassModels.get(clazz);
        return classModels == null || classModels.isEmpty() ? null : classModels.get(0);
    }

    private ClassModel findInstanceClassModel(Class<?> clazz, Object objectInstance) {
        List<ClassModel> classModels = instanceClassModels.get(objectInstance);
        if (classModels != null) {
            for (ClassModel classModel : classModels) {
                if (classModel.sourceClass == clazz) {
                    return classModel;
                }
            }
        }
        return null;
    }

    /**
     * 该类是否已有节点，按实例建的与按类型建的都算。走计数表，不遍历同类节点列表。
     */
    boolean hasClassModel(Class<?> clazz) {
        return clazz != null && classModelCounts.containsKey(clazz);
    }

    ClassModel findClassModelByInstance(Object objectInstance) {
        if (objectInstance == null) {
            return null;
        }
        List<ClassModel> classModels = instanceClassModels.get(objectInstance);
        return classModels == null || classModels.isEmpty() ? null : classModels.get(0);
    }

    void remove(ClassModel classModel) {
        Class<?> clazz = classModel.sourceClass;
        Object instance = classModel.sourceClassInstance;
        if (clazz != null) {
            Integer count = classModelCounts.get(clazz);
            if (count != null) {
                if (count <= 1) {
                    classModelCounts.remove(clazz);
                } else {
                    classModelCounts.put(clazz, count - 1);
                }
            }
        }
        if (instance != null) {
            instanceClassModels.computeIfPresent(instance, (k, list) -> {
                list.remove(classModel);
                return list.isEmpty() ? null : list;
            });
        } else if (clazz != null) {
            typedClassModels.computeIfPresent(clazz, (k, list) -> {
                list.remove(classModel);
                return list.isEmpty() ? null : list;
            });
        }
    }

    ClassModel[] getRootClassModels() {
        return classModelArray;
    }

    TypeAnalysis getTypeAnalysis(Type type) {
        return typeAnalysisCache.get(type);
    }

    /**
     * 缓存一次类型判定结果，随本次任务结束丢弃。
     */
    void putTypeAnalysis(Type type, TypeAnalysis typeAnalysis) {
        typeAnalysisCache.put(type, typeAnalysis);
    }

    /**
     * 一次类型判定的完整结果。
     *
     * <p>连同「判定中忽略了哪些类」一起缓存：这些忽略项原本是判定过程写入的副作用，缓存命中时判定不再
     * 执行，不存下来就会丢掉未解析类型的提示。
     */
    static final class TypeAnalysis {

        final Class<?>[] analyzableClasses;

        final Set<Class<?>> ignoredClasses;

        TypeAnalysis(Class<?>[] analyzableClasses, Collection<Class<?>> ignoredClasses) {
            this.analyzableClasses = analyzableClasses;
            // 复制一份：原集合在后续兄弟节点判定时还会继续增长
            this.ignoredClasses = Collections.unmodifiableSet(new LinkedHashSet<>(ignoredClasses));
        }
    }
}
