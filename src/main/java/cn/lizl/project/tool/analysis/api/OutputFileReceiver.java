package cn.lizl.project.tool.analysis.api;

import java.awt.Desktop;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 产物已写入文件后的回调。
 *
 * <p>回调在分析线程上同步执行，耗时操作会拖长 {@code run} 的返回时间；实现抛出的异常会原样传回调用方。
 * 回调时产物文件已写完并关闭。
 */
public interface OutputFileReceiver {

    Logger log = Logger.getLogger(OutputFileReceiver.class.getName());

    /**
     * 只打印产物绝对路径，不在 {@code run} 里抛异常。{@code run()} 无参版本的默认值。
     */
    OutputFileReceiver DEFAULT = outputFile -> {
        try {
            log.info("解析结果已输出到文件 --->  " + outputFile.getCanonicalPath());
        } catch (Exception e) {
            log.log(Level.WARNING, "输出文件接收器 OutputFileReceiver.DEFAULT 处理发生异常！", e);
        }
    };

    /**
     * 用系统默认关联程序打开 {@code .html} 产物；其他系统或非 html 产物只打印路径。
     *
     * <p>走 {@link Desktop#open}，不经 shell 命令行，文件名中的特殊字符不会被二次解释；
     * 桌面环境不可用或无关联程序时只留一条警告，不中断分析。
     */
    OutputFileReceiver OPEN_IN_WINDOWS_BROWSER = outputFile -> {
        try {
            String filePath = outputFile.getCanonicalPath();
            if (!filePath.toLowerCase().endsWith("html")) {
                log.warning("非网页文件，请自行查找软件打开 ---> " + filePath);
                return;
            }
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                log.info("即将打开输出文件 ---> " + filePath);
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    log.warning("当前桌面环境不支持打开文件，请自行打开 ---> " + filePath);
                    return;
                }
                Desktop.getDesktop().open(outputFile);
                log.info("已打开文件！");
            } else {
                log.info("非Windows系统，请自行打开文件，文件路径 ---> " + filePath);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "文件打开错误提示 ---> " + e.getMessage(), e);
        }
    };

    /**
     * @param outputFile 已写好的产物文件，调用期间保证存在且内容完整
     */
    void receive(File outputFile);
}
