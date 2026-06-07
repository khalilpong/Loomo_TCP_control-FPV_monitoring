package com.sample.loomodemo;

import android.graphics.PointF;
import android.util.Log;

import com.sample.loomodemo.helper.SegwayService;
import com.sample.loomodemo.helper.TcpSrv;
import com.segway.robot.algo.Pose2D;

import java.util.Timer;
import java.util.TimerTask;

// 检查 checkpoint 是否到达
// NOTE: 如发生了打滑等情况，里程计的数值将变得极度不可信
// NOTE: Base.CONTROL_MODE_NAVIGATION 模式下，可以通过 setOnCheckPointArrivedListener() 检查
//       但是其他模式下就无法检查了，所以手动实现一个方法来检查
public class CkptChecker {
    private final String TAG = "CkptChecker";
    private boolean mPause = false;
    public interface IArriveListener {
        void onCkptArrived(int idx, PointF ckpt, boolean isLast);
    }

    // 将从第 1 个检查点开始检查
    CkptChecker(AppConfig cfg, IArriveListener listener) {
        mListener = listener;
        mDistThreshSq = cfg.getCkptRange() * cfg.getCkptRange();
        mAllCkpt = cfg.getCheckpoints();
        mAllRoute = cfg.getRoutes();
        mLastCkptOfCurRoute = mAllRoute[mCurRouteIdx].toCkptIdx;
        mCheckTimer = new Timer();
        mCheckTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                doCheck();
            }
        }, 2000, 2000);
    }

    void resetCkpt(int ckptIdx) {
        mCurCkptIdx = ckptIdx;
    }

    public void pause() {
        mPause = true;
    }
    public void resume() {
        mPause = false;
    }

    public void stop() {
        mCheckTimer.cancel();
        mCheckTimer.purge();
    }

    private boolean arriveCkpt(PointF ckpt) {
        Pose2D curPose = SegwayService.base().getOdometryPose(-1);
        double dist = Math.pow(curPose.getX() - ckpt.x, 2) + Math.pow(curPose.getY() - ckpt.y, 2);
        Log.d(TAG, "ckpt: " + ckpt + ", curPose: " + curPose + ", dist: " + dist + ", thresh: " + mDistThreshSq);
        TcpSrv.getInstance().sendMsg("[odometer] current pose: (" + curPose.getX() + ", " + curPose.getY() + ")");
        return dist <= mDistThreshSq;
    }

    private void doCheck() {
        if (mPause) return; // 暂停
        int arrivedCkpt = -1; // 到达的检查点（我们将检查当前检查点和本路段最后一个检查点）
        if (arriveCkpt(mAllCkpt[mCurCkptIdx])) arrivedCkpt = mCurCkptIdx;
        else if (mLastCkptOfCurRoute != mCurCkptIdx && arriveCkpt(mAllCkpt[mLastCkptOfCurRoute])) arrivedCkpt = mLastCkptOfCurRoute;

        if (arrivedCkpt == -1) return; // 没有到达检查点

        boolean isLast = arrivedCkpt == mAllCkpt.length - 1;
        mListener.onCkptArrived(arrivedCkpt, mAllCkpt[arrivedCkpt], isLast);
        if (isLast) { // 最后一个检查点，停止定时器
            mCheckTimer.cancel();
            mCheckTimer.purge();
        } else {
            mCurCkptIdx = arrivedCkpt + 1; // 检查下一个检查点
            if (mCurCkptIdx > mAllRoute[mCurRouteIdx].toCkptIdx) { // 本路段已经结束
                ++mCurRouteIdx;
                mLastCkptOfCurRoute = mAllRoute[mCurRouteIdx].toCkptIdx;
            }
        }
    }

    private float mDistThreshSq; // 判定到达检查点的距离阈值（平方）
    private IArriveListener mListener;
    private Timer mCheckTimer;
    private PointF mAllCkpt[]; // 所有的检查点列表
    private Route mAllRoute[]; // 所有路径列表
    private int mCurCkptIdx = 1; // 当前正在检查的检查点序号（从第 1 个检查点开始，第 0 个是原点）
    private int mCurRouteIdx = 0; // 当前路段
    private int mLastCkptOfCurRoute = 1; // 当前路段的最后一个检查点
}
