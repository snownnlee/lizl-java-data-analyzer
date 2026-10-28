package cn.lizl.project.tool.analysis.core;

import java.io.File;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cn.lizl.project.tool.analysis.util.FrontendAssets;

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
        // 引用了前端引擎的产物（PUML_HTML / MERMAID_HTML）在同目录补一份 js：
        // 双击 file:// 打开、断网都能渲染，且同目录多个产物共用一份，不重复落盘。
        FrontendAssets.ensureReferenced(content, outputFile.getParentFile());
        return outputFile;
    }
}
