package com.sample.loomodemo.helper;

import com.sample.loomodemo.Route;
import com.sample.loomodemo.route.ConeTrackRoute;
import com.sample.loomodemo.route.DTSRoute;
import com.sample.loomodemo.route.TcpProRoute;
import com.sample.loomodemo.route.TcpRemoteRoute;
import com.sample.loomodemo.route.UNetRoadRoute;
import com.sample.loomodemo.route.VlsYoloRoute;
import com.sample.loomodemo.route.YoloAndUnetRoute;
import com.sample.loomodemo.route.YoloRoute;

public class RouteDetailHelper {
    public static String getRouteDetail(Route route) {
        if (route.mode.equalsIgnoreCase("VLS")) {
            return "VLS";
        } else if (route.mode.equalsIgnoreCase("VLS&YOLO")) {
            VlsYoloRoute temp = (VlsYoloRoute) route;
            return "VLS&YOLO\nobs=" + temp.obstacleConfidence
                    + ", resume=" + temp.obstacleResumeFrames;
        } else if (route.mode.equalsIgnoreCase("ConeTrack")) {
            ConeTrackRoute temp = (ConeTrackRoute) route;
            return "ConeTrack\ncruise=" + temp.cruiseLinearVelocity
                    + ", obs=" + temp.obstacleConfidence;
        } else if (route.mode.equalsIgnoreCase("TcpRemote")) {
            TcpRemoteRoute temp = (TcpRemoteRoute) route;
            return "TcpRemote\nv=" + temp.forwardLinearVelocity
                    + ", w=" + temp.turnAngularVelocity
                    + ", timeout=" + temp.commandTimeoutMs;
        } else if (route.mode.equalsIgnoreCase("TcpPro")) {
            TcpProRoute temp = (TcpProRoute) route;
            return "TcpPro\nv=" + temp.maxLinearVelocity
                    + ", w=" + temp.maxAngularVelocity
                    + ", deadband=" + temp.joystickDeadband;
        } else if (route.mode.equalsIgnoreCase("DTS")) {
            return "DTS\navoid obstacle: " + (((DTSRoute) route).avoidObstacle ? "ON" : "OFF");
        } else if (route.mode.equalsIgnoreCase("YOLO")) {
            return "YOLO";
        } else if (route.mode.equalsIgnoreCase("YOLO&UNet")) {
            return "YOLO&UNet";
        } else if (route.mode.equalsIgnoreCase("UNetRoad")) {
            UNetRoadRoute temp = (UNetRoadRoute) route;
            return "UNetRoad\narc1=" + temp.arc1Distance
                    + " dir1=" + temp.arc1TurnDirection
                    + " straight=" + temp.straightDistance
                    + " arc2=" + temp.arc2Distance
                    + " dir2=" + temp.arc2TurnDirection;
        }
        return "unknown";
    }
}
