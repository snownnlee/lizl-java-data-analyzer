package cn.lizl.project.tool.analysis.http;

import cn.lizl.project.tool.analysis.util.HtmlTemplates;
import cn.lizl.project.tool.analysis.util.ResourcePaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * classpath 资源读取：页面模板都是只读、进程内不变，处理器各自缓存字节即可。
 *
 * <p>公开给 {@code spring.*} 下的处理器复用——它们不属于通用 http 包，但仍需要读模板。
 */
public final class ClasspathResource {

    private ClasspathResource() {
    }

    /**
     * 读取 {@code analysis/html/} 下的页面模板。压缩与缓存统一在 {@link HtmlTemplates#load} 完成
     * （与产物外壳模板共用同一份缓存），这里只补一层 UTF-8 字节出口。
     */
    public static byte[] readHtml(String fileName) throws IOException {
        checkExists(fileName);
        return HtmlTemplates.load(fileName).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] read(String name) throws IOException {
        ClassLoader classLoader = ClasspathResource.class.getClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(name)) {
            if (inputStream == null) {
                throw new IOException("classpath 中找不到 " + name);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(32 * 1024);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private static void checkExists(String fileName) throws IOException {
        if (ClasspathResource.class.getClassLoader().getResource(ResourcePaths.html(fileName)) == null) {
            throw new IOException("classpath 中找不到 " + ResourcePaths.html(fileName));
        }
    }
}
