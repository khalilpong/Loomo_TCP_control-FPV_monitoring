package com.sample.loomodemo.pilot;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import com.sample.loomodemo.Pilot;
import com.sample.loomodemo.Route;
import com.sample.loomodemo.helper.SegwayService;
import com.sample.loomodemo.helper.TcpSrv;
import com.sample.loomodemo.helper.YoloV3Tiny;
import com.sample.loomodemo.route.VlsYoloRoute;
import com.segway.robot.algo.Pose2D;
import com.segway.robot.algo.minicontroller.CheckPoint;
import com.segway.robot.algo.minicontroller.CheckPointStateListener;
import com.segway.robot.sdk.locomotion.head.Head;
import com.segway.robot.sdk.locomotion.sbv.Base;
import com.segway.robot.sdk.locomotion.sbv.StartVLSListener;
import com.segway.robot.sdk.vision.Vision;
import com.segway.robot.sdk.vision.frame.Frame;
import com.segway.robot.sdk.vision.stream.StreamInfo;
import com.segway.robot.sdk.vision.stream.StreamType;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.util.List;

public class VlsYoloPilot extends Pilot {
    private static final String TAG = "VlsYoloPilot";

    private final Route mBaseRoute;
    private final VlsYoloRoute mRoute;
    private final Surface mSurface;
    private final int mStreamType = StreamType.COLOR;
    private final int mFrameWidth;
    private final int mFrameHeight;

    private boolean mRun = true;
    private boolean mPause = false;
    private boolean mVlsPausedByObstacle = false;
    private boolean mIsDetecting = false;
    private long mLastObstacleDetectTime = 0;
    private int mObstacleClearFrames = 0;
    private YoloV3Tiny mDetector;
    private YoloV3Tiny.Result mLastObstacle;

    public VlsYoloPilot(VlsYoloRoute route, TextureView view) {
        super(route);
        mBaseRoute = route;
        mRoute = route;
        mSurface = new Surface(view.getSurfaceTexture());
        StreamInfo info = Vision.getInstance().getStreamInfo(mStreamType);
        mFrameWidth = info.getWidth();
        mFrameHeight = info.getHeight();
        Log.i(TAG, "VLS&YOLO stream: " + mFrameWidth + " x " + mFrameHeight);
    }

    @Override
    public void start() {
        mRun = true;
        mPause = false;
        mVlsPausedByObstacle = false;
        mIsDetecting = false;
        mLastObstacleDetectTime = 0;
        mObstacleClearFrames = 0;
        mLastObstacle = null;

        Head.getInstance().setMode(Head.MODE_EMOJI);
        Head.getInstance().setHeadJointYaw(0.f);
        Head.getInstance().setWorldPitch(mRoute.headPitch);

        mDetector = YoloV3Tiny.create(mRoute);
        if (mDetector == null) {
            Log.e(TAG, "start: YOLO model load failed");
            return;
        }

        startVls(true);
        SegwayService.base().setOnCheckPointArrivedListener(new CheckPointStateListener() {
            @Override
            public void onCheckPointArrived(CheckPoint checkPoint, Pose2D realPose, boolean isLast) {
                Log.i(TAG, "VLS arrived checkpoint " + checkPoint + ", pose=" + realPose + ", isLast=" + isLast);
            }

            @Override
            public void onCheckPointMiss(CheckPoint checkPoint, Pose2D realPose, boolean isLast, int reason) {
                Log.w(TAG, "VLS missed checkpoint " + checkPoint + ", pose=" + realPose
                        + ", isLast=" + isLast + ", reason=" + reason);
            }
        });
        Vision.getInstance().startListenFrame(mStreamType, this::onNewFrame);
    }

    @Override
    public void stop() {
        mRun = false;
        Vision.getInstance().stopListenFrame(mStreamType);
        while (mIsDetecting) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        SegwayService.base().clearCheckPointsAndStop();
        SegwayService.base().stopVLS();
        mDetector = null;
        mSurface.release();
    }

    @Override
    public void pause() {
        mPause = true;
        SegwayService.base().stopVLS();
    }

    @Override
    public void resume() {
        mPause = false;
        if (!mVlsPausedByObstacle) {
            startVls(false);
        }
    }

    private void startVls(boolean resetCheckpoints) {
        SegwayService.base().setControlMode(Base.CONTROL_MODE_NAVIGATION);
        SegwayService.base().startVLS(true, true, new StartVLSListener() {
            @Override
            public void onOpened() {
                if (resetCheckpoints) {
                    SegwayService.base().clearCheckPointsAndStop();
                    SegwayService.base().setNavigationDataSource(Base.NAVIGATION_SOURCE_TYPE_VLS);
                    for (int ckpt = mBaseRoute.fromCkptIdx + 1; ckpt <= mBaseRoute.toCkptIdx; ++ckpt) {
                        PointF pt = mBaseRoute.checkpoints[ckpt - mBaseRoute.fromCkptIdx];
                        SegwayService.base().addCheckPoint(pt.x, pt.y);
                        Log.i(TAG, "added checkpoint " + ckpt + " to VLS: " + pt);
                    }
                }
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "startVls error: " + errorMessage);
            }
        });
    }

    private void onNewFrame(int streamType, Frame frame) {
        if (streamType != mStreamType || !mRun || mPause || mDetector == null) return;

        Mat image = new Mat(mFrameHeight, mFrameWidth, CvType.CV_8UC4, frame.getByteBuffer());
        Paint paint = new Paint();
        Bitmap bitmap = Bitmap.createBitmap(mFrameWidth, mFrameHeight, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(image, bitmap, true);
        final Canvas canvas = mSurface.lockCanvas(null);
        canvas.drawBitmap(bitmap, 0.f, 0.f, paint);
        mSurface.unlockCanvasAndPost(canvas);

        long curTick = System.currentTimeMillis();
        if (!mIsDetecting && curTick - mLastObstacleDetectTime >= mRoute.obstacleDetectInterval) {
            mIsDetecting = true;
            mLastObstacleDetectTime = curTick;
            new Thread(() -> {
                processObstacle(image);
                mIsDetecting = false;
            }).start();
        }
    }

    private void processObstacle(Mat image) {
        boolean blocking = detectBlockingObstacle(image);
        if (blocking) {
            mObstacleClearFrames = 0;
            if (!mVlsPausedByObstacle) {
                mVlsPausedByObstacle = true;
                SegwayService.base().stopVLS();
                TcpSrv.getInstance().sendMsg("[vls-yolo] obstacle blocking, pause VLS");
            }
            return;
        }

        if (mVlsPausedByObstacle) {
            ++mObstacleClearFrames;
            if (mObstacleClearFrames >= mRoute.obstacleResumeFrames) {
                mVlsPausedByObstacle = false;
                mLastObstacle = null;
                if (!mPause) {
                    startVls(false);
                }
                TcpSrv.getInstance().sendMsg("[vls-yolo] obstacle clear, resume VLS");
            }
        }
    }

    private boolean detectBlockingObstacle(Mat image) {
        List<YoloV3Tiny.Result> results = mDetector.detectSelected(image, mRoute.obstacleConfidence, mRoute.obstacleClassIds);
        YoloV3Tiny.Result blocking = null;
        for (YoloV3Tiny.Result result : results) {
            double centerX = result.box.x + result.box.width / 2.0;
            double centerY = result.box.y + result.box.height / 2.0;
            double normCenterX = centerX / image.cols();
            double normCenterY = centerY / image.rows();
            double normWidth = result.box.width / image.cols();
            double normHeight = result.box.height / image.rows();

            boolean nearCenter = Math.abs(normCenterX - 0.5) <= mRoute.obstacleCenterMargin;
            boolean closeEnough = normWidth >= mRoute.obstacleMinWidth || normHeight >= mRoute.obstacleMinHeight;
            boolean inPathDepth = normCenterY >= 0.35;
            if (nearCenter && closeEnough && inPathDepth) {
                if (blocking == null || result.box.width > blocking.box.width) {
                    blocking = result;
                }
            }
        }

        mLastObstacle = blocking;
        if (blocking != null) {
            TcpSrv.getInstance().sendMsg("[vls-yolo] obstacle cls=" + blocking.clsIdx + ", box=" + blocking.box);
            return true;
        }
        return false;
    }
}
