package com.sample.loomodemo.route;

/**
 * YOLO&Unet 模式
 * 每帧图像都同时调用 YOLO 和 UNet 进行检测，但是优先使用 YOLO 检测结果
 * 如果 YOLO 没有结果，则按 UNet 给出的路面结果进行引导
 */
public class YoloAndUnetRoute extends YoloRoute {
    // 模型文件名（固定存放在 /sdcard/model/unet 文件夹中）
    public String unetModelFilename;
//    // 检测置信度
//    public float confidence;
    // 模型尺寸
    public int unetModelWidth = 800, unetModelHeight = 480;
//    // 检测间隔（毫秒）
//    public int detectInterval;
    // 路面目标类别（从 0 开始编号）
    public int unetRoadClassIdx = 4;
    // 路面像素点数阈值（在输出图中的数量占比）
    // 当路面像素点数量低于此值时（可以认为没有检测到路面），将不进行引导
    public float unetRoadPixelMinCnt = 0.001f;
//    // 头部的俯仰角度
//    public float headPitch;
//    // 运动控制：将尽量确保检测到的目标宽度范围在 objectMinW 到 objectMaxW 之间
//    //         宽度 <= objectMinW 时使用最大速度 maxVelocity 前进
//    //         宽度 objectMaxW ~ objectMaxW * 1.1 时不再行进
//    //         宽度 >objectMaxW * 1.1 时后退，后退速度固定为 backVelocity
//    // 目标最小宽度和最大宽度（0~1）
//    public float objectMinW, objectMaxW;
//    // 运动的最大速度
//    public float maxLinearVelocity;
//    // 左右转向速度最大值
//    public float maxAngularVelocity;
}
