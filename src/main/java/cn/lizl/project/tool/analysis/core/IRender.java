package cn.lizl.project.tool.analysis.core;

import java.io.File;

interface IRender {

    String render(ClassModel[] classModels);

    File output(ClassModel[] classModels);
}
