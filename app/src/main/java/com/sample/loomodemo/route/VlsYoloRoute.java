package com.sample.loomodemo.route;

public class VlsYoloRoute extends YoloRoute {
    public VlsYoloRoute() {
        modelCfgFilename = "yolov3-tiny.cfg";
        modelWeightsFilename = "yolov3-tiny.weights";
        confidence = 0.35f;
        headPitch = 0.70f;
        objectClassIdx = -1;
        targetClassIdx = -1;
    }

    public int[] obstacleClassIds = new int[] {0, 1, 2, 3, 5, 7};
    public float obstacleConfidence = 0.35f;
    public int obstacleDetectInterval = 300;
    public float obstacleCenterMargin = 0.22f;
    public float obstacleMinWidth = 0.09f;
    public float obstacleMinHeight = 0.12f;
    public int obstacleResumeFrames = 4;
}
