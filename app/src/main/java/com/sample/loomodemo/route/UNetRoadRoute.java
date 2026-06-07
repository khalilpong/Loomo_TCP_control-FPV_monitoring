package com.sample.loomodemo.route;

public class UNetRoadRoute extends YoloAndUnetRoute {
    public static final int TURN_LEFT = 1;
    public static final int TURN_RIGHT = -1;

    public UNetRoadRoute() {
        headPitch = 0.0f;
        confidence = 0.35f;
        detectInterval = 180;
        useTurnDetection = false;
    }

    public float arc1Distance = 2.0f;
    public float straightDistance = 4.0f;
    public float arc2Distance = 2.0f;
    public int arc1TurnDirection = TURN_LEFT;
    public int arc2TurnDirection = TURN_LEFT;

    public float arcLinearVelocity = 0.35f;
    public float straightLinearVelocity = 0.5f;
    public float maxLinearVelocity = 0.7f;
    public float maxAngularVelocity = 0.9f;

    public float centerGain = 1.2f;
    public float headingGain = 1.0f;
    public float centerDeadband = 0.03f;
    public float headingDeadband = 0.03f;
    public float arcTurnBias = 0.18f;
    public int lostFrameThreshold = 5;

    public int[] obstacleClassIds = new int[] {0, 1, 2, 3, 5, 7};
    public float obstacleConfidence = 0.35f;
    public int obstacleDetectInterval = 350;
    public float obstacleCenterMargin = 0.20f;
    public float obstacleMinWidth = 0.10f;
    public float obstacleMinHeight = 0.12f;
    public int obstacleResumeFrames = 3;

    public int reuseLastRoadFrames = 4;
    public float lostRoadLinearVelocity = 0.10f;
    public float lostRoadAngularScale = 0.60f;
    public float roadSmoothingAlpha = 0.65f;
    public float wideRoadThreshold = 0.58f;
    public float wideRoadAngularScale = 0.50f;
    public float wideRoadLinearScale = 0.75f;
    public boolean bootstrapSearchEnabled = true;
    public float bootstrapLinearVelocity = 0.12f;
    public float bootstrapAngularVelocity = 0.16f;
    public float bootstrapMaxDistance = 1.20f;

    // -------------------------------------------------------
    // 视觉转弯检测参数 (Vision-based turn detection)
    // 当 useTurnDetection=true 时启用，距离参数退化为安全兜底
    // -------------------------------------------------------

    /** 是否启用视觉转弯检测（false 则退回纯里程计模式） */
    public boolean useTurnDetection = false;

    /**
     * ARC_1 完成判定：远近道路中心偏差绝对值低于此阈值
     * 表示前方路面已变直，可离开入弧阶段
     */
    public float arc1CompleteThreshold = 0.07f;

    /** 连续满足 arc1CompleteThreshold 的帧数才确认 ARC_1 完成 */
    public int arc1CompleteFrames = 4;

    /**
     * 转弯检测灵敏度：STRAIGHT 阶段远处偏差超过此值时判定前方有弯
     * deviation = farCenter.x - nearCenter.x
     * 右转时 deviation > 0，左转时 deviation < 0
     */
    public float turnDetectThreshold = 0.10f;

    /** 连续满足 turnDetectThreshold 的帧数才触发 ARC_2 */
    public int turnDetectFrames = 5;

    /**
     * ARC_2 完成判定：偏差绝对值低于此阈值，表示已驶出弯道
     */
    public float arc2CompleteThreshold = 0.07f;

    /** 连续满足 arc2CompleteThreshold 的帧数才确认 ARC_2 完成 */
    public int arc2CompleteFrames = 4;

    /**
     * ARC_1 阶段：视觉生效前的最小行驶距离（米）
     * 防止刚启动时视觉抖动导致提前切换
     */
    public float arc1MinDistance = 1.5f;

    /**
     * STRAIGHT 阶段：视觉生效前的最小行驶距离（米，从 ARC_1 结束起算）
     * 防止刚进入直道时立刻误检出弯道
     */
    public float straightMinDistance = 5.0f;

    @Override
    public boolean checkDestByCkpt() { return false; }
}
