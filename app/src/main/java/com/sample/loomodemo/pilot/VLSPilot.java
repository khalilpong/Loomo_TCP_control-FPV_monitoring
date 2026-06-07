package com.sample.loomodemo.pilot;

import android.graphics.PointF;
import android.util.Log;

import com.sample.loomodemo.Pilot;
import com.sample.loomodemo.Route;
import com.sample.loomodemo.helper.SegwayService;
import com.segway.robot.algo.Pose2D;
import com.segway.robot.algo.minicontroller.CheckPoint;
import com.segway.robot.algo.minicontroller.CheckPointStateListener;
import com.segway.robot.sdk.locomotion.sbv.Base;
import com.segway.robot.sdk.locomotion.sbv.StartVLSListener;

// VLS 引导行进程序
public class VLSPilot extends Pilot {
    // 从 route 的起点通过 VLS 引导至 route 的终点
    // 注意，需要当前已经到达或经过 route 的起点（不会被设置为检查点）
    public VLSPilot(Route route) {
        super(route);
        mRoute = route;
    }

    private static final String TAG = "VLSPilot";
    private final Route mRoute;

    @Override
    public void start() {
        // 将底座配置为导航模式
        SegwayService.base().setControlMode(Base.CONTROL_MODE_NAVIGATION);
        // 启动 VLS
        SegwayService.base().startVLS(true, true, new StartVLSListener() {
            @Override
            public void onOpened() {
                SegwayService.base().clearCheckPointsAndStop(); // 先清空已有检查点
                SegwayService.base().setNavigationDataSource(Base.NAVIGATION_SOURCE_TYPE_VLS);
//                // 重置原点，则设置的 checkpoint 为相对于当前位置的增量
                // 我们的所有代码均不打算重置原点，所以原点将始终为设备开机时候的位置
//                SegwayService.base().cleanOriginalPoint();
//                Pose2D curPose = SegwayService.base().getOdometryPose(-1);
//                SegwayService.base().setOriginalPoint(curPose);
                // 逐个添加检查点（该行程首个点不设置，认为已经在此检查点上了）
                for (int ckpt = mRoute.fromCkptIdx + 1; ckpt <= mRoute.toCkptIdx; ++ckpt) {
                    int tempIdx = ckpt - mRoute.fromCkptIdx;
                    PointF pt = mRoute.checkpoints[tempIdx];
                    // 因为我们其实是靠 CkptChecker 来判断检查到到达状态的
                    // 可能在 VLS 判定到达之前结束，所以 theta 无法生效，故不配置 theta
                    SegwayService.base().addCheckPoint(pt.x, pt.y);
                    Log.i(TAG, "已添加检查点 " + ckpt + ": " + pt + " 到 VLS");
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "onError() called with: errorMessage = [" + errorMessage + "]");
            }
        });
        SegwayService.base().setOnCheckPointArrivedListener(new CheckPointStateListener() {
            @Override
            public void onCheckPointArrived(CheckPoint checkPoint, Pose2D realPose, boolean isLast) {
                Log.i(TAG, "VLS 报告已到达检查点：" + checkPoint + ", realPose: " + realPose + ", isLast:" + isLast);
            }

            @Override
            public void onCheckPointMiss(CheckPoint checkPoint, Pose2D realPose, boolean isLast, int reason) {
                Log.w(TAG, "VLS 报告已错过检查点：" + checkPoint + ", realPose: " + realPose + ", isLast:" + isLast + ", reason: " + reason);
            }
        });
    }

    @Override
    public void stop() {
        SegwayService.base().clearCheckPointsAndStop();
        SegwayService.base().stopVLS();
    }

    @Override
    public void pause() {
        SegwayService.base().stopVLS();
    }

    @Override
    public void resume() {
        SegwayService.base().startVLS(true, true, new StartVLSListener() {

            @Override
            public void onOpened() {}

            @Override
            public void onError(String errorMessage) {}
        });
    }
}
