package com.sample.loomodemo.helper;

public class VoiceControlParams {
    // 前进基准速度（GO AHEAD 的速度）
    public float startLinearVelocity = 0.5f;
    // GO FASTER 每次递增的速度
    public float linearVelocityStep = 0.5f;
    // 前进允许的最大速度
    public float linearVelocityMax = 2.5f;
    // 后退速度（固定）
    public float moveBackVelocity = 0.5f;
    // 转向角速度
    public float angularVelocity = 0.5f;
}
