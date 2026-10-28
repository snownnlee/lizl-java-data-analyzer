package cn.lizl.project.tool.analysis.util;

import cn.lizl.project.tool.analysis.JavaDataAnalyzer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CommonUtil {

    private static final Logger log = Logger.getLogger(CommonUtil.class.getName());

    private static final char[] LN_CHARS = System.lineSeparator().toCharArray();

    private static final String PROJECT_ROOT_PACKAGE = JavaDataAnalyzer.class.getPackage().getName();

    public static void writeUtf8String(String content, File file) {
        writeUtf8String(content, file.toPath());
    }

    public static void writeUtf8String(String content, Path filePath) {
        try {
            if (Files.notExists(filePath.getParent())) {
                Files.createDirectories(filePath.getParent());
            }
            Files.write(filePath, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String read(InputStream inputStream, String charset, boolean close) {
        return read(inputStream, Charset.forName(charset), close);
    }

    public static String read(InputStream inputStream, Charset charset, boolean close) {
        try {
            // 不用 available() 定长读：它只是「当前不阻塞可读的字节数」，对进程错误流/网络流常只返回部分，
            // 一次 read 少读会把剩余内容静默丢掉；统一走缓冲循环读完整流
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int readLength;
            while ((readLength = inputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, readLength);
            }
            return new String(byteArrayOutputStream.toByteArray(), charset);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (close) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    log.log(Level.WARNING, "关闭输入流发生异常！", e);
                }
            }
        }
    }

    /** 成环时的占位：继续展开只会无限递归 */
    private static final String CYCLE_MARK = "(循环引用)";

    /**
     * 产物文件/目录名非法字符表：控制符、Windows 文件名非法字符，
     * 以及 {@code & ^ % !} 等 cmd 元字符（防御纵深：避免文件名进入任何 shell 命令行时被二次解释）。
     */
    private static final Pattern UNSAFE_FILE_NAME_CHARS = Pattern.compile("[\\p{Cntrl}\\\\/:*?\"<>|&^%!]");

    /** 文件名中非法字符的替换占位 */
    public static final String UNSAFE_FILE_NAME_MARK = "？";

    /**
     * 敏感字段名子串表（全小写匹配 {@code name.toLowerCase().contains(...)}）：
     * 命中的字段值在渲染层替换为 {@link #SENSITIVE_VALUE_MARK}，不落报告。
     * 分析不受影响——结构照常展开，只挡「值」不挡「结构」。
     */
    private static final String[] SENSITIVE_NAME_PARTS =
            {"password", "passwd", "pwd", "secret", "token", "credential", "apikey", "api_key", "authorization"};

    /** 敏感字段值的渲染占位 */
    public static final String SENSITIVE_VALUE_MARK = "***";

    /**
     * 字段/参数名是否命中敏感名单（大小写不敏感的子串匹配，如 {@code userPassword}、{@code authToken}）。
     * 用于报告渲染前的值脱敏；名单是防御默认值而非完备清单，调用方仍应避免把分析服务暴露到不可信网络。
     */
    public static boolean isSensitiveName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String part : SENSITIVE_NAME_PARTS) {
            if (lower.contains(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清洗单段文件/目录名：命中非法字符表的部分替换为 {@link #UNSAFE_FILE_NAME_MARK}。
     *
     * <p>只做字符级替换，不处理「整段为 {@code ..}」的目录穿越——调用方在把段当目录拼接时须自行防护。
     */
    public static String sanitizeFileName(String name) {
        return UNSAFE_FILE_NAME_CHARS.matcher(name).replaceAll(UNSAFE_FILE_NAME_MARK);
    }

    public static boolean isUnsafeFileNameSegment(String segment) {
        return "..".equals(segment) || ".".equals(segment);
    }

    public static String getStringFrom(Object object, int lengthLimit) {
        return getStringFrom(object, lengthLimit, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * 带递归路径的值格式化。
     *
     * <p>{@code path} 只记「当前递归路径上」的容器，命中即成环——自引用集合会让
     * {@code HashMap.toString()} 之类一路展开。用路径而非「已访问」：同一容器被并列引用两次时要各自展开。
     */
    private static String getStringFrom(Object object, int lengthLimit, Set<Object> path) {
        if (object == null) {
            return "null";
        }
        if (lengthLimit == 0) {
            return "";
        }
        if (lengthLimit < 0) {
            lengthLimit = Integer.MAX_VALUE;
        }
        boolean container = isRecursiveContainer(object);
        if (container && !path.add(object)) {
            return CYCLE_MARK;
        }
        try {
            return formatValue(object, lengthLimit, path);
        } finally {
            if (container) {
                path.remove(object);
            }
        }
    }

    private static boolean isRecursiveContainer(Object object) {
        return object.getClass().isArray()
                || object instanceof Collection
                || object instanceof Enumeration
                || object instanceof Iterator
                || object instanceof Map;
    }

    private static String formatValue(Object object, int lengthLimit, Set<Object> path) {
        if (object.getClass().isArray()) {
            StringJoiner resultJoiner = new StringJoiner(", ", "[", "]");
            int length = Array.getLength(object);
            for (int i = 0; i < length; i++) {
                if (resultJoiner.length() >= lengthLimit) {
                    resultJoiner.add("...");
                    break;
                }
                resultJoiner.add(getStringFrom(Array.get(object, i), lengthLimit, path));
            }
            return resultJoiner.toString();
        }
        if (object instanceof Collection) {
            StringJoiner resultJoiner;
            if (object instanceof List) {
                resultJoiner = new StringJoiner(", ", "[", "]");
            } else if (object instanceof Queue) {
                resultJoiner = new StringJoiner(" -- ", "[", "]");
            } else {
                resultJoiner = new StringJoiner(", ", "{", "}");
            }
            for (Object item : (Collection<?>) object) {
                if (resultJoiner.length() >= lengthLimit) {
                    resultJoiner.add("...");
                    break;
                }
                resultJoiner.add(getStringFrom(item, lengthLimit, path));
            }
            return resultJoiner.toString();
        }
        if (object instanceof Enumeration) {
            StringJoiner resultJoiner = new StringJoiner(" --> ", "[", "]");
            Enumeration<?> enumeration = (Enumeration<?>) object;
            while (enumeration.hasMoreElements()) {
                if (resultJoiner.length() >= lengthLimit) {
                    resultJoiner.add("...");
                    break;
                }
                resultJoiner.add(getStringFrom(enumeration.nextElement(), lengthLimit, path));
            }
            return resultJoiner.toString();
        }
        if (object instanceof Iterator) {
            StringJoiner resultJoiner = new StringJoiner(" -> ", "[", "]");
            Iterator<?> iterator = (Iterator<?>) object;
            while (iterator.hasNext()) {
                if (resultJoiner.length() >= lengthLimit) {
                    resultJoiner.add("...");
                    break;
                }
                resultJoiner.add(getStringFrom(iterator.next(), lengthLimit, path));
            }
            return resultJoiner.toString();
        }
        if (object instanceof Map) {
            StringJoiner resultJoiner = new StringJoiner(", ", "{", "}");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
                if (resultJoiner.length() >= lengthLimit) {
                    resultJoiner.add("...");
                    break;
                }
                resultJoiner.add(getStringFrom(entry.getKey(), lengthLimit, path) + "=" + getStringFrom(entry.getValue(), lengthLimit, path));
            }
            return resultJoiner.toString();
        }
        try {
            String result = object.toString();
            return result.length() > lengthLimit ? result.substring(0, lengthLimit) + "..." : result;
        } catch (Throwable throwable) {
            return "(T_T)!Call toString() cause ---> " + throwable.getMessage();
        }
    }

    /**
     * 合并两段包名配置，去重并保持首次出现顺序。
     *
     * <p>{@code add*} 系列没有清除入口，不去重会让同一个包被反复追加，而每个被分析的类都要遍历这个数组。
     */
    public static String[] combine(String[] packages1, String[] packages2) {
        Set<String> merged = new LinkedHashSet<>(packages1.length + packages2.length);
        Collections.addAll(merged, packages1);
        Collections.addAll(merged, packages2);
        return merged.toArray(new String[0]);
    }

    public static boolean isNotProjectInnerClass(Class<?> clazz) {
        Class<?> tempClass = clazz;
        while (tempClass != null) {
            if (tempClass.getName().startsWith(PROJECT_ROOT_PACKAGE)) {
                return false;
            }
            tempClass = tempClass.getSuperclass();
        }
        return true;
    }

    public static void appendLine(StringBuilder builder, CharSequence... parts) {
        if (parts != null) {
            for (CharSequence part : parts) {
                builder.append(part);
            }
        }
        builder.append(LN_CHARS);
    }
}
