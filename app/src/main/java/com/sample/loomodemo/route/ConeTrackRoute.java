package com.sample.loomodemo.route;

public class ConeTrackRoute extends YoloRoute {
    public ConeTrackRoute() {
        modelCfgFilename = "yolov3-tiny.cfg";
        modelWeightsFilename = "yolov3-tiny.weights";
        confidence = 0.35f;
        detectInterval = 120;
        headPitch = 0.0f;
        maxLinearVelocity = 0.70f;
        maxAngularVelocity = 0.70f;
    }

    public float cruiseLinearVelocity = 0.55f;
    public float minLinearVelocity = 0.15f;
    public float centerGain = 1.10f;
    public float headingGain = 1.30f;
    public float centerDeadband = 0.03f;
    public float headingDeadband = 0.03f;
    public float curveSlowdownGain = 1.60f;

    public int coneHueMin = 5;
    public int coneHueMax = 30;
    public int coneSatMin = 80;
    public int coneSatMax = 255;
    public int coneValMin = 80;
    public int coneValMax = 255;
    public double minConeArea = 180.0;

    public float nearRegionStart = 0.68f;
    public float farRegionStart = 0.40f;
    public float defaultTrackHalfWidthNear = 0.20f;
    public float defaultTrackHalfWidthFar = 0.15f;
    public int reuseLastTrackFrames = 4;
    public int lostTrackThreshold = 6;
    public int visibilityMinCones = 2;
    public float trackSmoothingAlpha = 0.60f;
    public float trackConfidenceFloor = 0.45f;
    public float linearRampStep = 0.08f;
    public float angularRampStep = 0.14f;

    public int[] obstacleClassIds = new int[] {0, 1, 2, 3, 5, 7};
    public float obstacleConfidence = 0.35f;
    public int obstacleDetectInterval = 250;
    public float obstacleCenterMargin = 0.22f;
    public float obstacleMinWidth = 0.08f;
    public float obstacleMinHeight = 0.10f;
    public int obstacleResumeFrames = 3;

    public float maxRunDistance = 0.f;

    @Override
    public boolean checkDestByCkpt() { return false; }
}
