package com.sample.loomodemo;

import android.graphics.PointF;

// 一段行程
public class Route {
    public int fromCkptIdx, toCkptIdx; // 起始和结束的检查点序号
    public int finalCkptIdx; // 全程终点检查点序号
    public PointF[] checkpoints; // 途径检查点列表（仅包含从 from 到 to 部分）
    // 检查点有效范围（在检查点附近此范围内则认为到达此检查点）
    public float mCkptRange;
    public String mode; // 引导模式（VLS...）

    // 通过检查点坐标来判断行程终点到达
    public boolean checkDestByCkpt() { return true; }
}

