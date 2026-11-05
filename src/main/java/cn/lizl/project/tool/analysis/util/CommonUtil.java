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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 通用工具：UTF-8 文件读写、文件名清洗、字段值转字符串（按上限截断）、敏感字段名判定。
 *
 * <p>放在叶子包 {@code util} 供 {@code core} 与 {@code http} 共用，本身不含分析语义。
 */
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

    /** 读尽输入流的全部字节（classpath 资源读取的共用实现；调用方负责关闭流） */
    public static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(32 * 1024);
        byte[] chunk = new byte[8192];
        int readLength;
        while ((readLength = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, readLength);
        }
        return buffer.toByteArray();
    }

    /**
     * 把内容写到 {@code target}：必要时创建所在目录；先写临时文件再原子改名，
     * 并发写同一目录不会留下半截文件（浏览器会把半截 js 当成语法错误）。
     */
    public static void writeFileAtomically(File directory, File target, byte[] content) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建输出目录：" + directory);
        }
        Path temp = Files.createTempFile(directory.toPath(), target.getName() + ".", ".tmp");
        try {
            Files.write(temp, content);
            Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 成环时的占位：继续展开只会无限递归 */
    private static final String CYCLE_MARK = "(循环引用)";

    /** 一次性容器未消费时的占位前缀，与类型名拼接使用 */
    private static final String ONE_SHOT_UNCONSUMED_MARK = "（一次性容器，未消费）";

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
        // Locale.ROOT：土耳其语区域下 "APIKEY".toLowerCase() 会得到无点 ı，命中判定失效、凭证明文落盘
        String lower = name.toLowerCase(Locale.ROOT);
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
        return getStringFrom(object, lengthLimit, false);
    }

    /**
     * 报告渲染取值专用：{@code maskSensitiveValues=true} 时，Map 中 key 命中敏感名单的 entry
     * 只把「值」替换为 {@link #SENSITIVE_VALUE_MARK}，key 与其它 entry 照常展示。
     *
     * <p>字段名脱敏在渲染层做，但容器内部的 key 渲染层看不到，只能在这里逐项判定；两参入口
     * 不带此行为，供文件名、日志等非报告场景沿用。脱敏只按 key 判定，不对任意值做敏感子串
     * 扫描，以免普通文本被误打码。
     */
    public static String getStringFrom(Object object, int lengthLimit, boolean maskSensitiveValues) {
        return getStringFrom(object, lengthLimit, maskSensitiveValues, true);
    }

    /**
     * 在三参入口之上增加一次性容器（{@code Iterator} / {@code Enumeration}）的消费开关：
     * {@code consumeOneShotCollections=false} 时不调用 {@code next()} / {@code nextElement()}，
     * 返回 {@code [类型名（一次性容器，未消费）]} 占位，保证对活对象的格式化是只读操作。
     * 报告渲染应使用本入口并传入分析配置的同名开关。
     */
    public static String getStringFrom(Object object, int lengthLimit, boolean maskSensitiveValues,
                                       boolean consumeOneShotCollections) {
        return getStringFrom(object, lengthLimit, maskSensitiveValues, consumeOneShotCollections,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * 带递归路径的值格式化。
     *
     * <p>{@code path} 只记「当前递归路径上」的容器，命中即成环——自引用集合会让
     * {@code HashMap.toString()} 之类一路展开。用路径而非「已访问」：同一容器被并列引用两次时要各自展开。
     */
    private static String getStringFrom(Object object, int lengthLimit, boolean maskSensitive,
                                        boolean consumeOneShot, Set<Object> path) {
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
            return formatValue(object, lengthLimit, maskSensitive, consumeOneShot, path);
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

    private static String formatValue(Object object, int lengthLimit, boolean maskSensitive,
                                      boolean consumeOneShot, Set<Object> path) {
        if (object.getClass().isArray()) {
            StringJoiner resultJoiner = new StringJoiner(", ", "[", "]");
            int length = Array.getLength(object);
            for (int i = 0; i < length; i++) {
                if (resultJoiner.length() >= lengthLimit) {
                    resultJoiner.add("...");
                    break;
                }
                resultJoiner.add(getStringFrom(Array.get(object, i), lengthLimit, maskSensitive, consumeOneShot, path));
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
                resultJoiner.add(getStringFrom(item, lengthLimit, maskSensitive, consumeOneShot, path));
            }
            return resultJoiner.toString();
        }
        if (object instanceof Enumeration) {
            if (!consumeOneShot) {
                return object.getClass().getName() + ONE_SHOT_UNCONSUMED_MARK;
            }
            StringJoiner resultJoiner = new StringJoiner(" --> ", "[", "]");
            Enumeration<?> enumeration = (Enumeration<?>) object;
            while (enumeration.hasMoreElements()) {
                if (resultJoiner.length() >= lengthLimit) {
                    resultJoiner.add("...");
                    break;
                }
                resultJoiner.add(getStringFrom(enumeration.nextElement(), lengthLimit, maskSensitive, consumeOneShot, path));
            }
            return resultJoiner.toString();
        }
        if (object instanceof Iterator) {
            if (!consumeOneShot) {
                return object.getClass().getName() + ONE_SHOT_UNCONSUMED_MARK;
            }
            StringJoiner resultJoiner = new StringJoiner(" -> ", "[", "]");
            Iterator<?> iterator = (Iterator<?>) object;
            while (iterator.hasNext()) {
                if (resultJoiner.length() >= lengthLimit) {
                    resultJoiner.add("...");
                    break;
                }
                resultJoiner.add(getStringFrom(iterator.next(), lengthLimit, maskSensitive, consumeOneShot, path));
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
                String keyText = getStringFrom(entry.getKey(), lengthLimit, maskSensitive, consumeOneShot, path);
                // 判定用原始 key：keyText 已被 lengthLimit 截断，敏感词落在截断点之后会漏脱敏
                String rawKey = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                // 容器内部的 key 渲染层看不到：命中敏感名单时只替换该项的「值」，key 与其它 entry 照常展示
                String valueText = maskSensitive && isSensitiveName(rawKey)
                        ? SENSITIVE_VALUE_MARK
                        : getStringFrom(entry.getValue(), lengthLimit, maskSensitive, consumeOneShot, path);
                resultJoiner.add(keyText + "=" + valueText);
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
