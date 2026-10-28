package cn.lizl.project.tool.analysis.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 产物 HTML 用到的前端渲染引擎（classpath 里的 {@code analysis/js/}）。
 *
 * <p>产物不内联引擎、也不引 CDN，而是在同目录放一份同名 js 并相对引用：
 * 双击打开（{@code file://}）与断网都能看图，多个产物之间还能共用同一份文件。
 *
 * <p>引擎来源与许可见同目录的 {@code SOURCES.txt}，由 {@code scripts/fetch-frontend-assets.py} 生成。
 */
public final class FrontendAssets {

    /** mermaid 11：普通脚本，暴露 {@code globalThis.mermaid} */
    public static final String MERMAID = "mermaid-11.17.2.min.js";

    /** PlantUML 官方 JS 引擎（TeaVM 编译）：发布物是 ES 模块，已改写为暴露 {@code globalThis.PlantUML} */
    public static final String PLANTUML = "plantuml-1.2026.8.js";

    /** PlantUML 依赖的 Graphviz 布局引擎，必须先于 {@link #PLANTUML} 加载 */
    public static final String VIZ_GLOBAL = "viz-global-1.2026.8.js";

    private static final String[] ALL = {MERMAID, PLANTUML, VIZ_GLOBAL};

    private static final String CLASSPATH_DIR = "analysis/js/";

    private static final Logger LOG = Logger.getLogger(FrontendAssets.class.getName());

    /** 引擎内容进程内不变，读一次缓存住：HTTP 通道每次请求都读 3 MB 会白白压 GC */
    private static final java.util.Map<String, byte[]> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private FrontendAssets() {
    }

    /** 是否是本库提供的前端引擎文件名（HTTP 通道据此判断该走资源分支） */
    public static boolean isAsset(String fileName) {
        for (String asset : ALL) {
            if (asset.equals(fileName)) {
                return true;
            }
        }
        return false;
    }

    /** 读 classpath 里的引擎内容（进程内缓存，只真正读一次） */
    public static byte[] read(String fileName) {
        byte[] cached = CACHE.get(fileName);
        if (cached != null) {
            return cached;
        }
        String path = CLASSPATH_DIR + fileName;
        try (InputStream in = FrontendAssets.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("classpath 上找不到前端引擎：" + path);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            byte[] content = buffer.toByteArray();
            CACHE.put(fileName, content);
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("读取前端引擎失败：" + path, e);
        }
    }

    /**
     * 把 {@code html} 里引用到的引擎补写到 {@code directory}（已存在且非空则跳过）。
     *
     * <p>用「内容里出现文件名」判断，而不是让每个调用方传渲染类型：产物落盘有三条路径
     * （直调、Bean 采集、拦截器），它们都知道自己的字符串内容，但不一定知道渲染类型。
     *
     * <p>失败只记警告：主产物已经写成功了，不该因为附带资源写不进去就让整次分析失败；
     * 但必须留下可排查的日志，否则用户只会看到一张空白图。
     */
    public static void ensureReferenced(String html, File directory) {
        if (html == null || directory == null) {
            return;
        }
        for (String asset : ALL) {
            if (html.contains(asset)) {
                ensureOne(directory, asset);
            }
        }
    }

    private static void ensureOne(File directory, String fileName) {
        File target = new File(directory, fileName);
        // 已存在且非空即认为就绪：多个产物写同一目录时，后写的直接复用，不再重复落 3 MB
        if (target.isFile() && target.length() > 0) {
            return;
        }
        try {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("无法创建输出目录：" + directory);
            }
            byte[] content = read(fileName);
            // 先写临时文件再原子改名：并发写同一目录时不会留下半截 js（浏览器会把它当语法错误）
            Path temp = Files.createTempFile(directory.toPath(), fileName + ".", ".tmp");
            try {
                Files.write(temp, content);
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "前端引擎 " + fileName + " 未能写入 " + directory
                    + "，该目录下的 HTML 产物将无法渲染图形（可手工从 jar 的 " + CLASSPATH_DIR
                    + fileName + " 复制过去）", e);
        }
    }

    /** 引擎文件大小，供日志/测试参考 */
    public static long sizeOf(String fileName) {
        return read(fileName).length;
    }

    /** 以 UTF-8 文本形式读（测试用：断言改写后的产物形态） */
    public static String readAsText(String fileName) {
        return new String(read(fileName), StandardCharsets.UTF_8);
    }
}
