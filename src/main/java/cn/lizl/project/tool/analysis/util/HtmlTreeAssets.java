package cn.lizl.project.tool.analysis.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 交互式 HTML 树（{@code HTML} / {@code HTML2}）的样式与脚本：<b>不内联</b>，以「带内容指纹的文件名 +
 * 同目录相对引用」分发，口径与 {@link FrontendAssets} 里的前端引擎一致。
 *
 * <p>为什么外置：这两份资源每份产物都要带一遍（压缩后合计约 22 KB），同目录多个产物、反复分析时
 * 纯属重复；外置后浏览器还能跨产物复用同一份缓存。
 *
 * <p>为什么文件名带内容指纹：同行多个产物共用一个目录，若用固定名，「目标文件已存在就跳过」会把
 * 升级前的旧 CSS/JS 一直留着，页面静默变成无样式或按钮失灵；指纹让「同名即同内容」成立，
 * 于是跳过是安全的，也给了一年强缓存。
 */
public final class HtmlTreeAssets {

    /** 样式表：两个渲染器共用 */
    public static final String STYLE = "tree-style.css";

    /** 交互脚本：展开/收起、导航、粒子特效，两个渲染器共用 */
    public static final String INTERACTION = "tree-interaction.js";

    /** 建树脚本：只有 HTML2 用，把 JSON 数据骨架还原成 DOM */
    public static final String BUILDER = "tree-builder.js";

    private static final String[] ALL = {STYLE, INTERACTION, BUILDER};

    /** 文件名里的指纹长度（hex 字符数），8 位足够区分同一台机器上的几个版本 */
    private static final int FINGERPRINT_LENGTH = 8;

    private static final Logger LOG = Logger.getLogger(HtmlTreeAssets.class.getName());

    /** 资源只读、进程内不变，压缩一次缓存住 */
    private static final Map<String, Asset> CACHE = new ConcurrentHashMap<>();

    private HtmlTreeAssets() {
    }

    /** 资源在产物里被引用的文件名，例如 {@code tree-style-1a2b3c4d.css} */
    public static String servedName(String fileName) {
        return asset(fileName).servedName;
    }

    /** 压缩后的资源文本（测试与排查用；产物里引用的是文件，不是这段文本） */
    public static String text(String fileName) {
        return new String(asset(fileName).content, StandardCharsets.UTF_8);
    }

    /** HTTP 通道按产物里的引用名回资源时用：是否是本库提供的样式/脚本（入参是带指纹的文件名） */
    public static boolean isAsset(String servedName) {
        for (String fileName : ALL) {
            if (asset(fileName).servedName.equals(servedName)) {
                return true;
            }
        }
        return false;
    }

    /** 读已发布资源的内容（入参是带指纹的文件名） */
    public static byte[] read(String servedName) {
        for (String fileName : ALL) {
            Asset asset = asset(fileName);
            if (asset.servedName.equals(servedName)) {
                return asset.content;
            }
        }
        throw new IllegalStateException("不是交互式 HTML 树的资源：" + servedName);
    }

    /** 资源对应的 Content-Type（入参是带指纹的文件名） */
    public static String contentTypeOf(String servedName) {
        return servedName.endsWith(".css") ? "text/css; charset=UTF-8" : "application/javascript; charset=UTF-8";
    }

    /**
     * 把产物内容里引用到的资源补写到 {@code directory}。
     *
     * <p>与 {@link FrontendAssets#ensureReferenced} 同口径：用「内容里出现文件名」判断，因为产物落盘
     * 有三条路径（直调、Bean 采集、拦截器），它们只知道自己的字符串内容，不一定知道渲染类型。
     *
     * <p>失败只记警告：主产物已经写成功，不该因为附带资源写不进去让整次分析失败，但必须留下可排查的
     * 日志，否则用户只会看到一个没样式的页面。
     */
    public static void ensureReferenced(String html, File directory) {
        if (html == null || directory == null) {
            return;
        }
        for (String fileName : ALL) {
            Asset asset = asset(fileName);
            if (html.contains(asset.servedName)) {
                ensureOne(directory, asset);
            }
        }
    }

    private static void ensureOne(File directory, Asset asset) {
        File target = new File(directory, asset.servedName);
        // 文件名带内容指纹：已存在且非空即同一份内容，多个产物写同一目录时直接复用
        if (target.isFile() && target.length() > 0) {
            return;
        }
        try {
            CommonUtil.writeFileAtomically(directory, target, asset.content);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "样式/脚本 " + asset.servedName + " 未能写入 " + directory
                    + "，该目录下的 HTML 产物会缺样式或交互（可手工从 jar 的 "
                    + ResourcePaths.html(asset.fileName) + " 取内容，另存为 "
                    + asset.servedName + " 后放入该目录）", e);
        }
    }

    private static Asset asset(String fileName) {
        Asset cached = CACHE.get(fileName);
        if (cached != null) {
            return cached;
        }
        return CACHE.computeIfAbsent(fileName, HtmlTreeAssets::build);
    }

    private static Asset build(String fileName) {
        byte[] content = compact(fileName, readRaw(fileName)).getBytes(StandardCharsets.UTF_8);
        return new Asset(fileName, servedName(fileName, content), content);
    }

    /** CSS 与 JS 的压缩规则不同，按扩展名分派；压缩在加载时做一次，产出的是最终会进产物的那份文本 */
    private static String compact(String fileName, String text) {
        return fileName.endsWith(".css")
                ? TemplateCompressor.compactCss(text)
                : TemplateCompressor.compactJs(text);
    }

    private static String servedName(String fileName, byte[] content) {
        int dot = fileName.lastIndexOf('.');
        return fileName.substring(0, dot) + "-" + fingerprint(content) + fileName.substring(dot);
    }

    private static String fingerprint(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(FINGERPRINT_LENGTH);
            for (int i = 0; i < FINGERPRINT_LENGTH / 2; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String readRaw(String fileName) {
        String path = ResourcePaths.html(fileName);
        try (InputStream stream = HtmlTreeAssets.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("错误：样式/脚本资源读取为空！！！" + path);
            }
            return CommonUtil.read(stream, StandardCharsets.UTF_8, true);
        } catch (IOException e) {
            throw new IllegalStateException("读取样式/脚本资源失败：" + path, e);
        }
    }

    private static final class Asset {

        /** jar 内的原名（如 {@code tree-style.css}），排障提示里让用户知道该取哪个文件 */
        private final String fileName;

        /** 产物里引用的名字（带内容指纹，如 {@code tree-style-1a2b3c4d.css}） */
        private final String servedName;

        private final byte[] content;

        private Asset(String fileName, String servedName, byte[] content) {
            this.fileName = fileName;
            this.servedName = servedName;
            this.content = content;
        }
    }
}
