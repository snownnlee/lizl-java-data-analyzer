package cn.lizl.project.tool.analysis.core;

abstract class RunningConfigCapable {

    protected final RunningConfig runningConfig;

    RunningConfigCapable(RunningConfig runningConfig) {
        this.runningConfig = runningConfig;
    }
}
