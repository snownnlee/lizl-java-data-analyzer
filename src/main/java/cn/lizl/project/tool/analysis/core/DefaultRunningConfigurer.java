package cn.lizl.project.tool.analysis.core;

import cn.lizl.project.tool.analysis.api.OutputContentReceiver;
import cn.lizl.project.tool.analysis.api.OutputFileReceiver;
import cn.lizl.project.tool.analysis.api.RunningConfigurer;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

public final class DefaultRunningConfigurer extends BaseRunningConfigurer {

    private final Logger log = Logger.getLogger(getClass().getName());

    private final List<Analyzable> analyzableCollection = new LinkedList<>();

    @Override
    public RunningConfigurer addAnalyzingClass(Class<?> analyzingClass) {
        return addAnalyzable(analyzingClass, null);
    }

    @Override
    public RunningConfigurer addAnalyzingClass(String analyzingClassName) {
        try {
            return addAnalyzable(Class.forName(analyzingClassName, false, GlobalConfig.classLoader), null);
        } catch (ClassNotFoundException e) {
            if (GlobalConfig.throwExceptionWhenStringClassNotFound) {
                throw new RuntimeException("加载分析类型失败！", e);
            } else {
                log.warning("指定的分析类型 " + analyzingClassName + " 加载失败，已跳过！");
                return this;
            }
        }
    }

    @Override
    public RunningConfigurer addAnalyzingObject(Object analyzingObject) {
        if (analyzingObject instanceof Class<?>) {
            return addAnalyzable((Class<?>) analyzingObject, null);
        }
        return addAnalyzable(analyzingObject.getClass(), analyzingObject);
    }

    private RunningConfigurer addAnalyzable(Class<?> analyzingClass, Object analyzingObject) {
        analyzableCollection.add(new Analyzable(analyzingClass, analyzingObject));
        return this;
    }

    @Override
    public RunningConfigurer run(OutputFileReceiver outputFileReceiver) {
        if (analyzableCollection.isEmpty()) {
            log.info("无可分析类型！");
        } else {
            ClassModel[] rootClassModels = analyzing(analyzableCollection);

            File outputFile = getRender().output(rootClassModels);

            outputFileReceiver.receive(outputFile);
        }
        return this;
    }

    @Override
    public RunningConfigurer run(OutputContentReceiver outputContentReceiver) {
        if (analyzableCollection.isEmpty()) {
            log.info("无可分析类型！");
        } else {
            ClassModel[] rootClassModels = analyzing(analyzableCollection);

            String renderContent = getRender().render(rootClassModels);

            outputContentReceiver.receive(renderContent);
        }
        return this;
    }

    private ClassModel[] analyzing(List<Analyzable> analyzableCollection) {
        ClassModelsManager classModelsManager = new ClassModelsManager(runningConfig, analyzableCollection);

        ClassModel[] rootClassModels = classModelsManager.getRootClassModels();
        // toArray 一次后按下标走：LinkedList 的 get(i) 是 O(n)，目标多时这里会平方级
        Analyzable[] analyzables = analyzableCollection.toArray(new Analyzable[0]);
        for (int i = 0; i < analyzables.length; i++) {
            if (rootClassModels[i] == null) {
                rootClassModels[i] = new ClassModel(runningConfig, classModelsManager, analyzables[i].clazz, analyzables[i].instance, 1);
            }
        }
        // 补链循环到不再有变化为止：跨根链接要求后面的根建好后，回头补前面根里当时无解的类型/实例。
        // 绝大多数场景一遍即收敛，不必每建一个根就重走此前所有树。
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ClassModel rootClassModel : rootClassModels) {
                if (rootClassModel != null) {
                    changed |= rootClassModel.completeLinkClassModels();
                }
            }
        }
        int count = 0;
        for (ClassModel rootClassModel : rootClassModels) {
            if (rootClassModel == null) {
                continue;
            }
            count++;
            rootClassModel.determineIsComplex();
        }
        if (rootClassModels.length != count) {
            ClassModel[] result = new ClassModel[count];
            int i = 0;
            for (ClassModel rootClassModel : rootClassModels) {
                if (rootClassModel != null) {
                    result[i++] = rootClassModel;
                }
            }
            return result;
        }
        return rootClassModels;
    }

    @Override
    public RunningConfigurer clear() {
        analyzableCollection.clear();
        return this;
    }

    @Override
    public RunningConfigurer reset(boolean clear) {
        runningConfig.reset();
        return clear ? clear() : this;
    }
}
