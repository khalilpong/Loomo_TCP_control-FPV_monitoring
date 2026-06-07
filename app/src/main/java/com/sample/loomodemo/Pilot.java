package com.sample.loomodemo;

// 行程引导接口
public abstract class Pilot {
    protected Pilot(Route route) {}

    public abstract void start();
    public abstract void stop();

    public abstract void pause();
    public abstract void resume();

    // 设置引导侦听器
    // 并不要求所有 pilot 实现此功能
    // 仅部分方式能够自行检测到达检查点
    public void setPilotListener(PilotListener listener) {}
}
