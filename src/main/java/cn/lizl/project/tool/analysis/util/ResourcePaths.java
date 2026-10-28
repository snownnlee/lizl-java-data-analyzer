package cn.lizl.project.tool.analysis.util;

/**
 * classpath 资源路径约定。
 *
 * <p>页面模板统一放在 {@value #HTML_DIR} 下，<b>不</b>放 classpath 根目录：
 * 根目录是全 classpath 共享的命名空间，很容易与宿主应用或其它框架的同名文件（beans.html、
 * interceptor.html 这类名字尤其常见）撞车，撞上后取到的是对方的模板。
 */
public final class ResourcePaths {

    /** 页面模板所在目录（相对 classpath 根，以 / 结尾） */
    public static final String HTML_DIR = "analysis/html/";

    private ResourcePaths() {
    }

    /** 拼出模板的完整 classpath 路径，例如 {@code analysis/html/beans.html} */
    public static String html(String fileName) {
        return HTML_DIR + fileName;
    }
}
