package cn.lizl.project.tool.analysis.core;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cn.lizl.project.tool.analysis.util.FrontendAssets;
import cn.lizl.project.tool.analysis.util.HtmlTreeAssets;

import static cn.lizl.project.tool.analysis.util.CommonUtil.isUnsafeFileNameSegment;
import static cn.lizl.project.tool.analysis.util.CommonUtil.sanitizeFileName;
import static cn.lizl.project.tool.analysis.util.CommonUtil.writeUtf8String;
import static cn.lizl.project.tool.analysis.util.CommonUtil.UNSAFE_FILE_NAME_MARK;

abstract class BaseRender extends RunningConfigCapable implements IRender {

    private final String fileExtension;

    protected BaseRender(RunningConfig runningConfig, String fileExtension) {
        super(runningConfig);
        this.fileExtension = fileExtension;
    }

    /**
     * 资源引用的标签只在这里拼：模板用 {@code ${styleTag}} / {@code ${scriptTag}} 之类的整标签占位符，
     * 不再手写 {@code <link>}/{@code <script>}，模板改空格或属性顺序不会失配。
     */
    protected final String treeStyleTag(String fileName) {
        return runningConfig.inlineAssets
                ? "<style>" + HtmlTreeAssets.text(fileName) + "</style>"
                : "<link rel=\"stylesheet\" href=\"./" + HtmlTreeAssets.servedName(fileName) + "\">";
    }

    /** 交互式 HTML 的脚本标签，口径见 {@link #treeStyleTag} */
    protected final String treeScriptTag(String fileName) {
        return runningConfig.inlineAssets
                ? "<script>" + HtmlTreeAssets.text(fileName) + "</script>"
                : "<script src=\"./" + HtmlTreeAssets.servedName(fileName) + "\"></script>";
    }

    /** 浏览器端渲染引擎的脚本标签，口径见 {@link #treeStyleTag} */
    protected final String engineScriptTag(String fileName) {
        return runningConfig.inlineAssets
                ? "<script>" + FrontendAssets.readAsText(fileName) + "</script>"
                : "<script src=\"./" + fileName + "\"></script>";
    }

    @Override
    public File output(ClassModel[] classModels) {
        return writeOutputFile(render(classModels), runningConfig.outputRootDir, buildOutputFileName(classModels));
    }

    /**
     * 计算本次落盘用的文件名数组。
     *
     * <p>返回值一定是新数组：{@code runningConfig.outputFileName} 存的是调用方传入的数组本体，直接在上面
     * 补扩展名等于改掉调用方的数组，换一种渲染类型时还会拼出 {@code a.html.puml}。
     */
    private String[] buildOutputFileName(ClassModel[] classModels) {
        String[] configured = runningConfig.outputFileName;
        String[] outputFileName;
        if (configured == null || configured.length == 0) {
            if (classModels.length == 1) {
                outputFileName = new String[]{classModels[0].sourceClass.getName() + ".单类型数据结构图" + fileExtension};
            } else {
                outputFileName = new String[]{Stream.of(classModels)
                        .map(ClassModel::getClassSimpleName)
                        .collect(Collectors.joining("+", "[", "].多类型数据结构图" + fileExtension))};
            }
        } else {
            outputFileName = configured.clone();
            int last = outputFileName.length - 1;
            if (!outputFileName[last].endsWith(fileExtension)) {
                outputFileName[last] += fileExtension;
            }
        }
        int last = outputFileName.length - 1;
        for (int i = 0; i <= last; i++) {
            String segment = sanitizeFileName(outputFileName[i]);
            // 任何段整段为 . / .. 都替换（末段理论上已被追加扩展名，这里一并拦截做纵深防御）
            if (isUnsafeFileNameSegment(segment)) {
                segment = UNSAFE_FILE_NAME_MARK;
            }
            outputFileName[i] = segment;
        }
        return outputFileName;
    }

    private File writeOutputFile(String content, String outputRootDir, String[] outputFileName) {
        File outputFile = Paths.get(outputRootDir, outputFileName).toFile();
        writeUtf8String(content, outputFile);
        // 外链模式下把产物引用到的资源补到同目录：双击 file:// 与断网可用，多个产物共用一份
        FrontendAssets.ensureReferenced(content, outputFile.getParentFile());
        HtmlTreeAssets.ensureReferenced(content, outputFile.getParentFile());
        return outputFile;
    }

    /**
     * 简单视图内联值：拼接后按 {@code valueDisplayLengthLimit} 截断（{@code <=0} 不截）。
     *
     * <p>三个渲染器共用：HTML / HTML2 写进文本与骨架，PUML 系写进字段行。调用方保证 {@code values} 非空。
     */
    protected String simpleViewInlineText(List<String> values) {
        String joined = String.join(", ", values);
        int limit = runningConfig.valueDisplayLengthLimit;
        if (limit > 0 && joined.length() > limit) {
            return joined.substring(0, limit) + "...";
        }
        return joined;
    }
}
