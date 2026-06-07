package com.sample.loomodemo.route;

import com.sample.loomodemo.Route;

public class YoloRoute extends Route {
    // 模型文件名（固定存放在 /sdcard/model/yolov3tiny 文件夹中）
    public String modelCfgFilename;
    public String modelWeightsFilename;
    // 检测置信度
    public float confidence;
//    // 模型尺寸
//    public int modelWidth, modelHeight;
    // 检测间隔（毫秒）
    public int detectInterval;
    // 引导前进的目标类别和判定终点到达的目标类别（从 0 开始编号，-1 表示未启用）
    public int objectClassIdx, targetClassIdx;
    // 头部的俯仰角度
    public float headPitch;
    // 运动控制：将尽量确保检测到的目标宽度范围在 objectMinW 到 objectMaxW 之间
    //         宽度 <= objectMinW 时使用最大速度 maxVelocity 前进
    //         宽度 objectMaxW ~ objectMaxW * 1.1 时不再行进
    //         宽度 >objectMaxW * 1.1 时后退，后退速度固定为 backVelocity
    // 目标最小宽度和最大宽度（0~1）
    public float objectMinW, objectMaxW;
    // 运动的最大速度，后退速度（取正值）
    public float maxLinearVelocity, backLinearVelocity;
    // 左右转向速度最大值
    public float maxAngularVelocity;

    // 如果没有配置 targetClassIdx 则通过检查点坐标来判断终点是否到达，否则由算法判断
    @Override
    public boolean checkDestByCkpt() { return targetClassIdx == -1; }
}
