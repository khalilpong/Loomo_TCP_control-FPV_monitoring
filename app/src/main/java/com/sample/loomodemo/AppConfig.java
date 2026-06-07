package com.sample.loomodemo;

import android.graphics.PointF;
import android.os.Environment;
import android.util.Log;

import com.sample.loomodemo.helper.AppConfigHelper;
import com.sample.loomodemo.helper.VoiceControlParams;
import com.sample.loomodemo.route.ConeTrackRoute;
import com.sample.loomodemo.route.DTSRoute;
import com.sample.loomodemo.route.TcpProRoute;
import com.sample.loomodemo.route.TcpRemoteRoute;
import com.sample.loomodemo.route.UNetRoadRoute;
import com.sample.loomodemo.route.VlsYoloRoute;
import com.sample.loomodemo.route.YoloAndUnetRoute;
import com.sample.loomodemo.route.YoloRoute;

import org.ini4j.Ini;
import org.ini4j.Profile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

public class AppConfig {
    AppConfig() {
        Ini ini;
        try {
            File dir = Environment.getExternalStorageDirectory();
            Log.i(TAG, "will load loomoDemo.ini from " + dir);
            ini = new Ini(new File(dir, "loomoDemo.ini"));
            Log.i(TAG, "load done: " + ini);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        parseIni(ini);
    }

    boolean isValid() {
        return mValid;
    }

    final PointF[] getCheckpoints() { return mCheckpoints; }
    float getCkptRange() { return mCkptRange; }
    final Route[] getRoutes() { return mRoutes; }
    final VoiceControlParams getVoiceControlParams() { return mVoiceControlParams; }

    private void parseIni(Ini ini) {
        try {
            parseIniGlobal(ini);
            parseIniCheckpoints(ini);
            parseIniRoutes(ini);
            parseVoice(ini);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        mValid = true;
    }

    private void parseIniGlobal(Ini ini) {
        mCkptRange = Float.parseFloat(ini.get("global", "ckptRange"));
    }

    private void parseIniCheckpoints(Ini ini) {
        Profile.Section sec = ini.get("checkpoints");
        String[] lines = sec.getAll("checkpoint", String[].class);
        if (lines.length < 1) {
            throw new Error("no checkpoints");
        }
        ArrayList<PointF> arr = new ArrayList<>();
        arr.add(new PointF(0.f, 0.f));
        for (String line : lines) {
            arr.add(AppConfigHelper.parsePoint2f(line));
        }
        mCheckpoints = new PointF[arr.size()];
        arr.toArray(mCheckpoints);
    }

    private void parseIniRoutes(Ini ini) {
        final int maxRouteCnt = 100;
        ArrayList<Route> arr = new ArrayList<>();
        for (int i = 1; i <= maxRouteCnt; ++i) {
            Profile.Section sec = ini.get("route" + i);
            if (sec == null) break;

            int endCkpt = sec.get("endCkpt", int.class);
            if ((i > 1 && endCkpt <= arr.get(arr.size() - 1).toCkptIdx)
                    || (i == 1 && endCkpt <= 0)
                    || (endCkpt >= mCheckpoints.length)) {
                throw new Error("route " + i + " invalid from or to checkpoint");
            }

            String mode = sec.get("mode", String.class);
            Route route;
            switch (mode) {
                case "DTS": {
                    DTSRoute temp = new DTSRoute();
                    temp.avoidObstacle = sec.get("avoidObstacle", Boolean.class);
                    route = temp;
                } break;
                case "VLS":
                    route = new Route();
                    break;
                case "VLS&YOLO": {
                    VlsYoloRoute temp = new VlsYoloRoute();
                    parseVlsYoloRoute(sec, temp);
                    route = temp;
                } break;
                case "ConeTrack": {
                    ConeTrackRoute temp = new ConeTrackRoute();
                    parseConeTrackRoute(sec, temp);
                    route = temp;
                } break;
                case "TcpRemote": {
                    TcpRemoteRoute temp = new TcpRemoteRoute();
                    parseTcpRemoteRoute(sec, temp);
                    route = temp;
                } break;
                case "TcpPro": {
                    TcpProRoute temp = new TcpProRoute();
                    parseTcpProRoute(sec, temp);
                    route = temp;
                } break;
                case "YOLO": {
                    YoloRoute temp = new YoloRoute();
                    parseYoloRoute(sec, temp);
                    route = temp;
                } break;
                case "YOLO&UNet": {
                    YoloAndUnetRoute temp = new YoloAndUnetRoute();
                    parseYoloAndUnetRoute(sec, temp);
                    route = temp;
                } break;
                case "UNetRoad": {
                    UNetRoadRoute temp = new UNetRoadRoute();
                    parseUNetRoadRoute(sec, temp);
                    route = temp;
                } break;
                default:
                    throw new Error("unknown route mode " + mode);
            }

            route.mode = mode;
            route.fromCkptIdx = arr.isEmpty() ? 0 : arr.get(arr.size() - 1).toCkptIdx;
            route.toCkptIdx = endCkpt;
            route.finalCkptIdx = mCheckpoints.length - 1;
            route.checkpoints = Arrays.copyOfRange(mCheckpoints, route.fromCkptIdx, route.toCkptIdx + 1);
            arr.add(route);
        }

        if (arr.isEmpty() || arr.get(arr.size() - 1).toCkptIdx + 1 != mCheckpoints.length) {
            throw new Error("destination checkpoint unreachable");
        }
        mRoutes = new Route[arr.size()];
        arr.toArray(mRoutes);
    }

    private void parseYoloRoute(Profile.Section sec, YoloRoute route) {
        route.modelCfgFilename = sec.get("cfgFilename", String.class);
        route.modelWeightsFilename = sec.get("weightsFilename", String.class);
        route.confidence = sec.get("confidence", Float.class);
        route.detectInterval = sec.get("detectInterval", Integer.class, 0);
        route.objectClassIdx = sec.get("objectClassIdx", Integer.class);
        route.targetClassIdx = sec.get("targetClassIdx", Integer.class);
        route.headPitch = sec.get("headPitch", Float.class);
        route.objectMinW = sec.get("objectMinW", Float.class);
        route.objectMaxW = sec.get("objectMaxW", Float.class);
        route.maxLinearVelocity = sec.get("maxLinearVelocity", Float.class);
        route.backLinearVelocity = sec.get("backLinearVelocity", Float.class);
        route.maxAngularVelocity = sec.get("maxAngularVelocity", Float.class);
    }

    private void parseYoloAndUnetRoute(Profile.Section sec, YoloAndUnetRoute route) {
        parseYoloRoute(sec, route);
        route.unetModelFilename = sec.get("unetModelFilename", String.class);
        route.unetModelWidth = sec.get("unetModelWidth", Integer.class, 800);
        route.unetModelHeight = sec.get("unetModelHeight", Integer.class, 480);
        route.unetRoadClassIdx = sec.get("unetRoadClassIdx", Integer.class, 4);
        route.unetRoadPixelMinCnt = sec.get("unetRoadPixelMinCnt", Float.class, 0.001f);
    }

    private void parseVlsYoloRoute(Profile.Section sec, VlsYoloRoute route) {
        route.modelCfgFilename = sec.get("cfgFilename", String.class, route.modelCfgFilename);
        route.modelWeightsFilename = sec.get("weightsFilename", String.class, route.modelWeightsFilename);
        route.confidence = sec.get("confidence", Float.class, route.confidence);
        route.headPitch = sec.get("headPitch", Float.class, route.headPitch);
        String obstacleClassIds = sec.get("obstacleClassIds", String.class, "0,1,2,3,5,7");
        route.obstacleClassIds = AppConfigHelper.parseIntArray(obstacleClassIds);
        route.obstacleConfidence = sec.get("obstacleConfidence", Float.class, route.obstacleConfidence);
        route.obstacleDetectInterval = sec.get("obstacleDetectInterval", Integer.class, route.obstacleDetectInterval);
        route.obstacleCenterMargin = sec.get("obstacleCenterMargin", Float.class, route.obstacleCenterMargin);
        route.obstacleMinWidth = sec.get("obstacleMinWidth", Float.class, route.obstacleMinWidth);
        route.obstacleMinHeight = sec.get("obstacleMinHeight", Float.class, route.obstacleMinHeight);
        route.obstacleResumeFrames = sec.get("obstacleResumeFrames", Integer.class, route.obstacleResumeFrames);
    }

    private void parseConeTrackRoute(Profile.Section sec, ConeTrackRoute route) {
        route.modelCfgFilename = sec.get("cfgFilename", String.class, route.modelCfgFilename);
        route.modelWeightsFilename = sec.get("weightsFilename", String.class, route.modelWeightsFilename);
        route.confidence = sec.get("confidence", Float.class, route.confidence);
        route.detectInterval = sec.get("detectInterval", Integer.class, route.detectInterval);
        route.headPitch = sec.get("headPitch", Float.class, route.headPitch);
        route.cruiseLinearVelocity = sec.get("cruiseLinearVelocity", Float.class, route.cruiseLinearVelocity);
        route.minLinearVelocity = sec.get("minLinearVelocity", Float.class, route.minLinearVelocity);
        route.maxLinearVelocity = sec.get("maxLinearVelocity", Float.class, route.maxLinearVelocity);
        route.maxAngularVelocity = sec.get("maxAngularVelocity", Float.class, route.maxAngularVelocity);
        route.centerGain = sec.get("centerGain", Float.class, route.centerGain);
        route.headingGain = sec.get("headingGain", Float.class, route.headingGain);
        route.centerDeadband = sec.get("centerDeadband", Float.class, route.centerDeadband);
        route.headingDeadband = sec.get("headingDeadband", Float.class, route.headingDeadband);
        route.curveSlowdownGain = sec.get("curveSlowdownGain", Float.class, route.curveSlowdownGain);

        route.coneHueMin = sec.get("coneHueMin", Integer.class, route.coneHueMin);
        route.coneHueMax = sec.get("coneHueMax", Integer.class, route.coneHueMax);
        route.coneSatMin = sec.get("coneSatMin", Integer.class, route.coneSatMin);
        route.coneSatMax = sec.get("coneSatMax", Integer.class, route.coneSatMax);
        route.coneValMin = sec.get("coneValMin", Integer.class, route.coneValMin);
        route.coneValMax = sec.get("coneValMax", Integer.class, route.coneValMax);
        route.minConeArea = sec.get("minConeArea", Double.class, route.minConeArea);
        route.nearRegionStart = sec.get("nearRegionStart", Float.class, route.nearRegionStart);
        route.farRegionStart = sec.get("farRegionStart", Float.class, route.farRegionStart);
        route.defaultTrackHalfWidthNear = sec.get("defaultTrackHalfWidthNear", Float.class, route.defaultTrackHalfWidthNear);
        route.defaultTrackHalfWidthFar = sec.get("defaultTrackHalfWidthFar", Float.class, route.defaultTrackHalfWidthFar);
        route.reuseLastTrackFrames = sec.get("reuseLastTrackFrames", Integer.class, route.reuseLastTrackFrames);
        route.lostTrackThreshold = sec.get("lostTrackThreshold", Integer.class, route.lostTrackThreshold);
        route.visibilityMinCones = sec.get("visibilityMinCones", Integer.class, route.visibilityMinCones);
        route.trackSmoothingAlpha = sec.get("trackSmoothingAlpha", Float.class, route.trackSmoothingAlpha);
        route.trackConfidenceFloor = sec.get("trackConfidenceFloor", Float.class, route.trackConfidenceFloor);
        route.linearRampStep = sec.get("linearRampStep", Float.class, route.linearRampStep);
        route.angularRampStep = sec.get("angularRampStep", Float.class, route.angularRampStep);
        route.maxRunDistance = sec.get("maxRunDistance", Float.class, route.maxRunDistance);

        String obstacleClassIds = sec.get("obstacleClassIds", String.class, "0,1,2,3,5,7");
        route.obstacleClassIds = AppConfigHelper.parseIntArray(obstacleClassIds);
        route.obstacleConfidence = sec.get("obstacleConfidence", Float.class, route.obstacleConfidence);
        route.obstacleDetectInterval = sec.get("obstacleDetectInterval", Integer.class, route.obstacleDetectInterval);
        route.obstacleCenterMargin = sec.get("obstacleCenterMargin", Float.class, route.obstacleCenterMargin);
        route.obstacleMinWidth = sec.get("obstacleMinWidth", Float.class, route.obstacleMinWidth);
        route.obstacleMinHeight = sec.get("obstacleMinHeight", Float.class, route.obstacleMinHeight);
        route.obstacleResumeFrames = sec.get("obstacleResumeFrames", Integer.class, route.obstacleResumeFrames);
    }

    private void parseTcpRemoteRoute(Profile.Section sec, TcpRemoteRoute route) {
        route.headPitch = sec.get("headPitch", Float.class, route.headPitch);
        route.forwardLinearVelocity = sec.get("forwardLinearVelocity", Float.class, route.forwardLinearVelocity);
        route.backwardLinearVelocity = sec.get("backwardLinearVelocity", Float.class, route.backwardLinearVelocity);
        route.turnAngularVelocity = sec.get("turnAngularVelocity", Float.class, route.turnAngularVelocity);
        route.maxLinearVelocity = sec.get("maxLinearVelocity", Float.class, route.maxLinearVelocity);
        route.maxAngularVelocity = sec.get("maxAngularVelocity", Float.class, route.maxAngularVelocity);
        route.commandTimeoutMs = sec.get("commandTimeoutMs", Integer.class, route.commandTimeoutMs);
        route.linearRampStep = sec.get("linearRampStep", Float.class, route.linearRampStep);
        route.angularRampStep = sec.get("angularRampStep", Float.class, route.angularRampStep);
    }

    private void parseTcpProRoute(Profile.Section sec, TcpProRoute route) {
        // TcpPro is the FPV joystick mode. Every field below mirrors one runtime
        // knob in TcpProRoute so behavior can be tuned from loomoDemo.ini without
        // recompiling the app.
        route.headPitch = sec.get("headPitch", Float.class, route.headPitch);
        route.maxLinearVelocity = sec.get("maxLinearVelocity", Float.class, route.maxLinearVelocity);
        route.maxReverseLinearVelocity = sec.get("maxReverseLinearVelocity", Float.class, route.maxReverseLinearVelocity);
        route.maxAngularVelocity = sec.get("maxAngularVelocity", Float.class, route.maxAngularVelocity);
        route.linearRampStep = sec.get("linearRampStep", Float.class, route.linearRampStep);
        route.linearBrakeStep = sec.get("linearBrakeStep", Float.class, route.linearBrakeStep);
        route.angularRampStep = sec.get("angularRampStep", Float.class, route.angularRampStep);
        route.angularBrakeStep = sec.get("angularBrakeStep", Float.class, route.angularBrakeStep);
        route.speedTurnCoupling = sec.get("speedTurnCoupling", Float.class, route.speedTurnCoupling);
        route.joystickDeadband = sec.get("joystickDeadband", Float.class, route.joystickDeadband);
        route.linearExpo = sec.get("linearExpo", Float.class, route.linearExpo);
        route.angularExpo = sec.get("angularExpo", Float.class, route.angularExpo);
        route.turnSlowdownGain = sec.get("turnSlowdownGain", Float.class, route.turnSlowdownGain);
        route.lowSpeedTurnBoost = sec.get("lowSpeedTurnBoost", Float.class, route.lowSpeedTurnBoost);
        route.sharpTurnThreshold = sec.get("sharpTurnThreshold", Float.class, route.sharpTurnThreshold);
        route.sharpTurnLinearScale = sec.get("sharpTurnLinearScale", Float.class, route.sharpTurnLinearScale);
        route.pivotTurnAssistMinTurn = sec.get("pivotTurnAssistMinTurn", Float.class, route.pivotTurnAssistMinTurn);
        route.pivotTurnAssistMaxForward = sec.get("pivotTurnAssistMaxForward", Float.class, route.pivotTurnAssistMaxForward);
        route.commandTimeoutMs = sec.get("commandTimeoutMs", Integer.class, route.commandTimeoutMs);
        route.statusIntervalMs = sec.get("statusIntervalMs", Integer.class, route.statusIntervalMs);
        route.videoPort = sec.get("videoPort", Integer.class, route.videoPort);
        route.videoIntervalMs = sec.get("videoIntervalMs", Integer.class, route.videoIntervalMs);
        route.videoJpegQuality = sec.get("videoJpegQuality", Integer.class, route.videoJpegQuality);
        route.videoWidth = sec.get("videoWidth", Integer.class, route.videoWidth);
        route.videoHeight = sec.get("videoHeight", Integer.class, route.videoHeight);
    }

    private void parseUNetRoadRoute(Profile.Section sec, UNetRoadRoute route) {
        route.unetModelFilename = sec.get("unetModelFilename", String.class);
        route.unetModelWidth = sec.get("unetModelWidth", Integer.class, route.unetModelWidth);
        route.unetModelHeight = sec.get("unetModelHeight", Integer.class, route.unetModelHeight);
        route.unetRoadClassIdx = sec.get("unetRoadClassIdx", Integer.class, route.unetRoadClassIdx);
        route.unetRoadPixelMinCnt = sec.get("unetRoadPixelMinCnt", Float.class, route.unetRoadPixelMinCnt);
        route.modelCfgFilename = sec.get("cfgFilename", String.class, "yolov3-tiny.cfg");
        route.modelWeightsFilename = sec.get("weightsFilename", String.class, "yolov3-tiny.weights");
        route.confidence = sec.get("confidence", Float.class, route.confidence > 0 ? route.confidence : 0.35f);
        route.headPitch = sec.get("headPitch", Float.class, route.headPitch);
        route.detectInterval = sec.get("detectInterval", Integer.class, route.detectInterval);
        route.arc1Distance = sec.get("arc1Distance", Float.class, route.arc1Distance);
        route.straightDistance = sec.get("straightDistance", Float.class, route.straightDistance);
        route.arc2Distance = sec.get("arc2Distance", Float.class, route.arc2Distance);
        route.arc1TurnDirection = sec.get("arc1TurnDirection", Integer.class, route.arc1TurnDirection);
        route.arc2TurnDirection = sec.get("arc2TurnDirection", Integer.class, route.arc2TurnDirection);
        route.arcLinearVelocity = sec.get("arcLinearVelocity", Float.class, route.arcLinearVelocity);
        route.straightLinearVelocity = sec.get("straightLinearVelocity", Float.class, route.straightLinearVelocity);
        route.maxLinearVelocity = sec.get("maxLinearVelocity", Float.class, route.maxLinearVelocity);
        route.maxAngularVelocity = sec.get("maxAngularVelocity", Float.class, route.maxAngularVelocity);
        route.centerGain = sec.get("centerGain", Float.class, route.centerGain);
        route.headingGain = sec.get("headingGain", Float.class, route.headingGain);
        route.centerDeadband = sec.get("centerDeadband", Float.class, route.centerDeadband);
        route.headingDeadband = sec.get("headingDeadband", Float.class, route.headingDeadband);
        route.arcTurnBias = sec.get("arcTurnBias", Float.class, route.arcTurnBias);
        route.lostFrameThreshold = sec.get("lostFrameThreshold", Integer.class, route.lostFrameThreshold);
        String obstacleClassIds = sec.get("obstacleClassIds", String.class, "0,1,2,3,5,7");
        route.obstacleClassIds = AppConfigHelper.parseIntArray(obstacleClassIds);
        route.obstacleConfidence = sec.get("obstacleConfidence", Float.class, route.obstacleConfidence);
        route.obstacleDetectInterval = sec.get("obstacleDetectInterval", Integer.class, route.obstacleDetectInterval);
        route.obstacleCenterMargin = sec.get("obstacleCenterMargin", Float.class, route.obstacleCenterMargin);
        route.obstacleMinWidth = sec.get("obstacleMinWidth", Float.class, route.obstacleMinWidth);
        route.obstacleMinHeight = sec.get("obstacleMinHeight", Float.class, route.obstacleMinHeight);
        route.obstacleResumeFrames = sec.get("obstacleResumeFrames", Integer.class, route.obstacleResumeFrames);
        route.reuseLastRoadFrames = sec.get("reuseLastRoadFrames", Integer.class, route.reuseLastRoadFrames);
        route.lostRoadLinearVelocity = sec.get("lostRoadLinearVelocity", Float.class, route.lostRoadLinearVelocity);
        route.lostRoadAngularScale = sec.get("lostRoadAngularScale", Float.class, route.lostRoadAngularScale);
        route.roadSmoothingAlpha = sec.get("roadSmoothingAlpha", Float.class, route.roadSmoothingAlpha);
        route.wideRoadThreshold = sec.get("wideRoadThreshold", Float.class, route.wideRoadThreshold);
        route.wideRoadAngularScale = sec.get("wideRoadAngularScale", Float.class, route.wideRoadAngularScale);
        route.wideRoadLinearScale = sec.get("wideRoadLinearScale", Float.class, route.wideRoadLinearScale);
        route.bootstrapSearchEnabled = sec.get("bootstrapSearchEnabled", Boolean.class, route.bootstrapSearchEnabled);
        route.bootstrapLinearVelocity = sec.get("bootstrapLinearVelocity", Float.class, route.bootstrapLinearVelocity);
        route.bootstrapAngularVelocity = sec.get("bootstrapAngularVelocity", Float.class, route.bootstrapAngularVelocity);
        route.bootstrapMaxDistance = sec.get("bootstrapMaxDistance", Float.class, route.bootstrapMaxDistance);

        route.useTurnDetection = sec.get("useTurnDetection", Boolean.class, route.useTurnDetection);
        route.arc1CompleteThreshold = sec.get("arc1CompleteThreshold", Float.class, route.arc1CompleteThreshold);
        route.arc1CompleteFrames = sec.get("arc1CompleteFrames", Integer.class, route.arc1CompleteFrames);
        route.turnDetectThreshold = sec.get("turnDetectThreshold", Float.class, route.turnDetectThreshold);
        route.turnDetectFrames = sec.get("turnDetectFrames", Integer.class, route.turnDetectFrames);
        route.arc2CompleteThreshold = sec.get("arc2CompleteThreshold", Float.class, route.arc2CompleteThreshold);
        route.arc2CompleteFrames = sec.get("arc2CompleteFrames", Integer.class, route.arc2CompleteFrames);
        route.arc1MinDistance = sec.get("arc1MinDistance", Float.class, route.arc1MinDistance);
        route.straightMinDistance = sec.get("straightMinDistance", Float.class, route.straightMinDistance);
    }

    private void parseVoice(Ini ini) {
        mVoiceControlParams = new VoiceControlParams();
        mVoiceControlParams.startLinearVelocity = Float.parseFloat(ini.get("voice", "startLinearVelocity"));
        mVoiceControlParams.linearVelocityStep = Float.parseFloat(ini.get("voice", "linearVelocityStep"));
        mVoiceControlParams.linearVelocityMax = Float.parseFloat(ini.get("voice", "linearVelocityMax"));
        mVoiceControlParams.moveBackVelocity = Float.parseFloat(ini.get("voice", "moveBackVelocity"));
        mVoiceControlParams.angularVelocity = Float.parseFloat(ini.get("voice", "angularVelocity"));
    }

    private static final String TAG = "AppConfig";
    private boolean mValid = false;
    private float mCkptRange;
    private PointF[] mCheckpoints;
    private Route[] mRoutes;
    private VoiceControlParams mVoiceControlParams;
}



