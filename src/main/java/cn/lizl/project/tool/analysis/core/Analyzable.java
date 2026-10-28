package cn.lizl.project.tool.analysis.core;

final class Analyzable {

    final Class<?> clazz;
    final Object instance;

    Analyzable(Class<?> clazz, Object instance) {
        if (clazz == null) {
            throw new IllegalArgumentException("分析类型不能为空！！！");
        }
        this.clazz = clazz;
        this.instance = instance;
    }
}
