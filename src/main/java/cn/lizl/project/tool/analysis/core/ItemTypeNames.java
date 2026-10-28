package cn.lizl.project.tool.analysis.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 集合元素包装类的展示别名，HTML 与 PUML 共用。 */
final class ItemTypeNames {

    private static final Map<Class<?>, String> NAMES;

    static {
        Map<Class<?>, String> names = new HashMap<>(8);
        names.put(FieldModel.ArrayItem.class, "[Array-Item]");
        names.put(FieldModel.ListItem.class, "[List-Item]");
        names.put(FieldModel.SetItem.class, "[Set-Item]");
        names.put(FieldModel.MapEntry.class, "[Map-Entry]");
        names.put(FieldModel.QueueItem.class, "[Queue-Item]");
        names.put(FieldModel.EnumerationItem.class, "[Enumeration-Item]");
        names.put(FieldModel.IteratorItem.class, "[Iterator-Item]");
        NAMES = Collections.unmodifiableMap(names);
    }

    private ItemTypeNames() {
    }

    static String of(Class<?> clazz) {
        return clazz == null ? null : NAMES.get(clazz);
    }
}
