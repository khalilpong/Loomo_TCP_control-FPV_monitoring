package com.sample.loomodemo.pilot;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import com.sample.loomodemo.Pilot;
import com.sample.loomodemo.PilotListener;
import com.sample.loomodemo.helper.PresenterChangeInterface;
import com.sample.loomodemo.helper.SegwayService;
import com.sample.loomodemo.helper.TcpSrv;
import com.sample.loomodemo.helper.UNet;
import com.sample.loomodemo.helper.YoloV3Tiny;
import com.sample.loomodemo.route.UNetRoadRoute;
import com.segway.robot.algo.Pose2D;
import com.segway.robot.sdk.locomotion.head.Head;
import com.segway.robot.sdk.locomotion.sbv.Base;
import com.segway.robot.sdk.vision.Vision;
import com.segway.robot.sdk.vision.frame.Frame;
import com.segway.robot.sdk.vision.stream.StreamInfo;
import com.segway.robot.sdk.vision.stream.StreamType;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect2d;

import java.util.List;

public class UNetRoadPilot extends Pilot {
    private static final String TAG = "UNetRoadPilot";

    private enum Phase {
        ARC_1,
        STRAIGHT,
        ARC_2,
        ARRIVED
    }

    private final UNetRoadRoute mRoute;
    private final PresenterChangeInterface mPresenterChangeInterface;
    private final Surface mSurface;
    private final int mStreamType = StreamType.COLOR;
    private final int mFrameWidth;
    private final int mFrameHeight;

    private PilotListener mListener;
    private UNet mUNet;
    private YoloV3Tiny mObstacleDetector;
    private volatile boolean mRun = true;
    private volatile boolean mPause = false;
    private volatile boolean mIsDetecting = false;
    private volatile long mLastDetectTime = 0;
    private volatile long mLastObstacleDetectTime = 0;
    private volatile int mLostFrames = 0;
    private volatile int mObstacleClearFrames = 0;
    private volatile boolean mObstacleBlocking = false;
    private volatile Phase mPhase = Phase.ARC_1;
    private volatile float mDistanceTravelled = 0.f;
    private volatile float mStraightEntryDistance = 0.f;
    private Pose2D mLastPose;
    private volatile boolean mHasArrived = false;
    private volatile UNet.Result mLastResult;
    private volatile UNet.Result mLastStableRoadResult;
    private volatile UNet.Result mFilteredRoadResult;
    private volatile YoloV3Tiny.Result mLastObstacle;
    private volatile int mRoadReuseFrames = 0;

    private volatile int mArc1CompleteCount = 0;
    private volatile int mTurnDetectCount = 0;
    private volatile int mArc2CompleteCount = 0;

    public UNetRoadPilot(UNetRoadRoute route, TextureView view, PresenterChangeInterface presenterChangeInterface) {
        super(route);
        mRoute = route;
        mPresenterChangeInterface = presenterChangeInterface;
        mSurface = new Surface(view.getSurfaceTexture());
        StreamInfo info = Vision.getInstance().getStreamInfo(mStreamType);
        mFrameWidth = info.getWidth();
        mFrameHeight = info.getHeight();
        Log.i(TAG, "UNetRoadPilot stream: " + mFrameWidth + " x " + mFrameHeight);
    }

    @Override
    public void setPilotListener(PilotListener listener) {
        mListener = listener;
    }

    @Override
    public void start() {
        mRun = true;
        mPause = false;
        mLostFrames = 0;
        mObstacleClearFrames = 0;
        mObstacleBlocking = false;
        mLastObstacle = null;
        mPhase = Phase.ARC_1;
        mDistanceTravelled = 0.f;
        mStraightEntryDistance = 0.f;
        mArc1CompleteCount = 0;
        mTurnDetectCount = 0;
        mArc2CompleteCount = 0;
        mHasArrived = false;
        mLastResult = null;
        mLastStableRoadResult = null;
        mFilteredRoadResult = null;
        mRoadReuseFrames = 0;
        mLastPose = SegwayService.base().getOdometryPose(-1);

        Base.getInstance().setControlMode(Base.CONTROL_MODE_RAW);
        Head.getInstance().setMode(Head.MODE_EMOJI);
        Head.getInstance().setHeadJointYaw(0.f);
        Head.getInstance().setWorldPitch(mRoute.headPitch);

        mUNet = UNet.create(mRoute);
        if (mUNet == null) {
            Log.e(TAG, "start: UNet model load failed");
            return;
        }
        mObstacleDetector = YoloV3Tiny.create(mRoute);
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
                e.printStackTrace();
            }
        }
        Base.getInstance().setLinearVelocity(0.f);
        Base.getInstance().setAngularVelocity(0.f);
        mUNet = null;
        mObstacleDetector = null;
        mSurface.release();
    }

    @Override
    public void pause() {
        mPause = true;
        Base.getInstance().setLinearVelocity(0.f);
        Base.getInstance().setAngularVelocity(0.f);
    }

    @Override
    public void resume() {
        mPause = false;
    }

    private void onNewFrame(int streamType, Frame frame) {
        if (streamType != mStreamType || !mRun || mPause || mHasArrived) return;

        Mat image = new Mat(mFrameHeight, mFrameWidth, CvType.CV_8UC4, frame.getByteBuffer());
        Paint paint = new Paint();
        Bitmap bitmap = Bitmap.createBitmap(mFrameWidth, mFrameHeight, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(image, bitmap, true);
        final Canvas canvas = mSurface.lockCanvas(null);
        canvas.drawBitmap(bitmap, 0.f, 0.f, paint);
        mSurface.unlockCanvasAndPost(canvas);

        if (mLastResult != null) {
            drawPoint(mLastResult.nearCenter);
            drawPoint(mLastResult.farCenter);
        }
        if (mLastObstacle != null) {
            mPresenterChangeInterface.drawRect(mLastObstacle.box);
        }

        long curTick = System.currentTimeMillis();
        if (!mIsDetecting && curTick - mLastDetectTime >= mRoute.detectInterval) {
            mIsDetecting = true;
            mLastDetectTime = curTick;
            new Thread(() -> {
                process(image, curTick);
                mIsDetecting = false;
            }).start();
        }
    }

    private void process(Mat image, long curTick) {
        updateDistanceTravelled();

        boolean obstacleDetected = false;
        boolean obstacleCheckRan = false;
        if (mObstacleDetector != null && curTick - mLastObstacleDetectTime >= mRoute.obstacleDetectInterval) {
            mLastObstacleDetectTime = curTick;
            obstacleCheckRan = true;
            obstacleDetected = detectObstacle(image);
        }

        if (obstacleDetected) {
            mObstacleBlocking = true;
            mObstacleClearFrames = 0;
        } else if (mObstacleBlocking && obstacleCheckRan) {
            ++mObstacleClearFrames;
            if (mObstacleClearFrames >= mRoute.obstacleResumeFrames) {
                mObstacleBlocking = false;
                mLastObstacle = null;
            }
        }

        if (mObstacleBlocking) {
            Base.getInstance().setLinearVelocity(0.f);
            Base.getInstance().setAngularVelocity(0.f);
            TcpSrv.getInstance().sendMsg("[unet-road] obstacle blocking, wait");
            return;
        }

        UNet.Result result = mUNet.Detect(image);
        if (result != null) {
            UNet.Result filtered = smoothRoadResult(result);
            mLastResult = filtered;
            mLastStableRoadResult = filtered;
            mRoadReuseFrames = 0;
        }

        if (result == null) {
            ++mLostFrames;
            updatePhaseByDistance();
            if (mLastStableRoadResult != null && mRoadReuseFrames < mRoute.reuseLastRoadFrames) {
                ++mRoadReuseFrames;
                driveByRoadResult(mLastStableRoadResult, true);
            } else if (shouldBootstrapSearch()) {
                driveBootstrapSearch();
            } else {
                handleLostRoad();
            }
            return;
        }

        mLostFrames = 0;

        if (mRoute.useTurnDetection) {
            updatePhaseByVision(result);
        } else {
            updatePhaseByDistance();
        }

        if (mPhase == Phase.ARRIVED) {
            notifyArrival();
            return;
        }

        driveByRoadResult(result, false);
    }

    private void driveByRoadResult(UNet.Result result, boolean reuseLastResult) {
        float openness = computeWideRoadOpenness(result);
        float visionScale = lerp(1.0f, mRoute.wideRoadAngularScale, openness);
        float linearScale = lerp(1.0f, mRoute.wideRoadLinearScale, openness);

        float headingError = applyDeadband(0.5f - result.farCenter.x, mRoute.headingDeadband) * visionScale;
        float centerError = applyDeadband(0.5f - result.nearCenter.x, mRoute.centerDeadband) * visionScale;
        float angularVelocity = mRoute.headingGain * headingError + mRoute.centerGain * centerError;

        float targetLinearVelocity = mPhase == Phase.STRAIGHT
                ? mRoute.straightLinearVelocity
                : mRoute.arcLinearVelocity;
        if (mPhase == Phase.ARC_1) {
            angularVelocity += getTurnBias(mRoute.arc1TurnDirection);
        } else if (mPhase == Phase.ARC_2) {
            angularVelocity += getTurnBias(mRoute.arc2TurnDirection);
        }

        float widthFactor = result.nearWidth > 0.f ? Math.min(1.f, Math.max(0.35f, result.nearWidth / 0.35f)) : 0.45f;
        float linearVelocity = Math.min(mRoute.maxLinearVelocity, targetLinearVelocity * widthFactor * linearScale);
        if (reuseLastResult) {
            linearVelocity = Math.min(linearVelocity, mRoute.lostRoadLinearVelocity);
            angularVelocity *= mRoute.lostRoadAngularScale;
        }

        angularVelocity = clamp(angularVelocity, -mRoute.maxAngularVelocity, mRoute.maxAngularVelocity);
        Base.getInstance().setLinearVelocity(linearVelocity);
        Base.getInstance().setAngularVelocity(angularVelocity);

        float deviation = result.farCenter.x - result.nearCenter.x;
        TcpSrv.getInstance().sendMsg("[unet-road] phase=" + mPhase
                + ", dist=" + String.format("%.2f", mDistanceTravelled)
                + ", reuse=" + reuseLastResult
                + ", deviation=" + String.format("%.3f", deviation)
                + ", openness=" + String.format("%.3f", openness)
                + ", near=" + result.nearCenter
                + ", far=" + result.farCenter
                + ", width=" + String.format("%.3f", result.nearWidth)
                + ", v=" + String.format("%.3f", linearVelocity)
                + ", w=" + String.format("%.3f", angularVelocity));
    }

    private boolean detectObstacle(Mat image) {
        List<YoloV3Tiny.Result> results = mObstacleDetector.detectSelected(image, mRoute.obstacleConfidence, mRoute.obstacleClassIds);
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
            TcpSrv.getInstance().sendMsg("[unet-road] obstacle cls=" + blocking.clsIdx + ", box=" + blocking.box);
            return true;
        }
        return false;
    }

    private void handleLostRoad() {
        Base.getInstance().setLinearVelocity(0.f);
        Base.getInstance().setAngularVelocity(0.f);
        TcpSrv.getInstance().sendMsg("[unet-road] road lost, lostFrames=" + mLostFrames + ", phase=" + mPhase);
        if (mLostFrames >= mRoute.lostFrameThreshold) {
            Log.w(TAG, "Too many lost frames, stop for safety");
        }
    }

    private void updateDistanceTravelled() {
        Pose2D curPose = SegwayService.base().getOdometryPose(-1);
        if (mLastPose != null) {
            double dx = curPose.getX() - mLastPose.getX();
            double dy = curPose.getY() - mLastPose.getY();
            mDistanceTravelled += (float) Math.sqrt(dx * dx + dy * dy);
        }
        mLastPose = curPose;
    }

    private void updatePhaseByDistance() {
        float arc1End = mRoute.arc1Distance;
        float straightEnd = arc1End + mRoute.straightDistance;
        float arc2End = straightEnd + mRoute.arc2Distance;
        Phase prev = mPhase;
        if (mDistanceTravelled >= arc2End) {
            mPhase = Phase.ARRIVED;
        } else if (mDistanceTravelled >= straightEnd) {
            if (mPhase != Phase.ARC_2 && mPhase != Phase.ARRIVED) {
                mPhase = Phase.ARC_2;
            }
        } else if (mDistanceTravelled >= arc1End) {
            if (mPhase == Phase.ARC_1) {
                mPhase = Phase.STRAIGHT;
                mStraightEntryDistance = mDistanceTravelled;
            }
        }
        if (mPhase != prev) {
            TcpSrv.getInstance().sendMsg("[unet-road] phase(dist) " + prev + " -> " + mPhase
                    + " at dist=" + String.format("%.2f", mDistanceTravelled));
        }
    }

    private void updatePhaseByVision(UNet.Result result) {
        float deviation = result.farCenter.x - result.nearCenter.x;
        Phase prev = mPhase;

        switch (mPhase) {
            case ARC_1: {
                if (mDistanceTravelled >= mRoute.arc1MinDistance
                        && Math.abs(deviation) < mRoute.arc1CompleteThreshold) {
                    ++mArc1CompleteCount;
                    if (mArc1CompleteCount >= mRoute.arc1CompleteFrames) {
                        mPhase = Phase.STRAIGHT;
                        mStraightEntryDistance = mDistanceTravelled;
                        mArc1CompleteCount = 0;
                        mTurnDetectCount = 0;
                    }
                } else {
                    mArc1CompleteCount = 0;
                }
                if (mDistanceTravelled >= mRoute.arc1Distance * 2.0f) {
                    mPhase = Phase.STRAIGHT;
                    mStraightEntryDistance = mDistanceTravelled;
                    mArc1CompleteCount = 0;
                    mTurnDetectCount = 0;
                    TcpSrv.getInstance().sendMsg("[unet-road] ARC_1 fallback by distance");
                }
                break;
            }

            case STRAIGHT: {
                float signedDeviation = deviation * (-mRoute.arc2TurnDirection);
                float straightDistSinceEntry = mDistanceTravelled - mStraightEntryDistance;

                if (straightDistSinceEntry >= mRoute.straightMinDistance
                        && signedDeviation > mRoute.turnDetectThreshold) {
                    ++mTurnDetectCount;
                    if (mTurnDetectCount >= mRoute.turnDetectFrames) {
                        mPhase = Phase.ARC_2;
                        mTurnDetectCount = 0;
                        mArc2CompleteCount = 0;
                    }
                } else {
                    mTurnDetectCount = 0;
                }
                float straightEnd = mRoute.arc1Distance + mRoute.straightDistance;
                if (mDistanceTravelled >= straightEnd) {
                    mPhase = Phase.ARC_2;
                    mTurnDetectCount = 0;
                    mArc2CompleteCount = 0;
                    TcpSrv.getInstance().sendMsg("[unet-road] STRAIGHT fallback by distance");
                }
                break;
            }

            case ARC_2: {
                if (Math.abs(deviation) < mRoute.arc2CompleteThreshold) {
                    ++mArc2CompleteCount;
                    if (mArc2CompleteCount >= mRoute.arc2CompleteFrames) {
                        mPhase = Phase.ARRIVED;
                    }
                } else {
                    mArc2CompleteCount = 0;
                }
                float arc2End = mRoute.arc1Distance + mRoute.straightDistance + mRoute.arc2Distance;
                if (mDistanceTravelled >= arc2End) {
                    mPhase = Phase.ARRIVED;
                    TcpSrv.getInstance().sendMsg("[unet-road] ARC_2 fallback by distance");
                }
                break;
            }

            default:
                break;
        }

        if (mPhase != prev) {
            TcpSrv.getInstance().sendMsg("[unet-road] phase(vision) " + prev + " -> " + mPhase
                    + " at dist=" + String.format("%.2f", mDistanceTravelled)
                    + ", deviation=" + String.format("%.3f", deviation));
        }
    }

    private void notifyArrival() {
        if (mHasArrived) return;
        mHasArrived = true;
        Base.getInstance().setLinearVelocity(0.f);
        Base.getInstance().setAngularVelocity(0.f);
        TcpSrv.getInstance().sendMsg("[unet-road] route destination arrived");
        if (mListener != null) {
            boolean isLast = mRoute.toCkptIdx == mRoute.finalCkptIdx;
            new Thread(() -> mListener.onCkptArrived(mRoute.toCkptIdx, isLast)).start();
        }
    }

    private float getTurnBias(int turnDirection) {
        int normalized = turnDirection >= 0 ? UNetRoadRoute.TURN_LEFT : UNetRoadRoute.TURN_RIGHT;
        return normalized * mRoute.arcTurnBias;
    }

    private void drawPoint(PointF point) {
        if (point == null) return;
        Rect2d rect = new Rect2d(point.x * mFrameWidth, point.y * mFrameHeight, 4, 4);
        mPresenterChangeInterface.drawRect(rect);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float applyDeadband(float value, float deadband) {
        if (Math.abs(value) <= deadband) {
            return 0.f;
        }
        return value;
    }

    private boolean shouldBootstrapSearch() {
        if (!mRoute.bootstrapSearchEnabled) return false;
        if (mLastStableRoadResult != null) return false;
        return mDistanceTravelled < mRoute.bootstrapMaxDistance && mPhase != Phase.ARRIVED;
    }

    private void driveBootstrapSearch() {
        float linearVelocity = Math.min(mRoute.maxLinearVelocity, mRoute.bootstrapLinearVelocity);
        float angularVelocity = 0.f;
        if (mPhase == Phase.ARC_1) {
            angularVelocity = getSearchAngularVelocity(mRoute.arc1TurnDirection);
        } else if (mPhase == Phase.ARC_2) {
            angularVelocity = getSearchAngularVelocity(mRoute.arc2TurnDirection);
        }

        Base.getInstance().setLinearVelocity(linearVelocity);
        Base.getInstance().setAngularVelocity(angularVelocity);
        TcpSrv.getInstance().sendMsg("[unet-road] bootstrap search"
                + ", phase=" + mPhase
                + ", dist=" + String.format("%.2f", mDistanceTravelled)
                + ", lostFrames=" + mLostFrames
                + ", v=" + String.format("%.3f", linearVelocity)
                + ", w=" + String.format("%.3f", angularVelocity));
    }

    private UNet.Result smoothRoadResult(UNet.Result raw) {
        if (mFilteredRoadResult == null) {
            mFilteredRoadResult = copyRoadResult(raw);
            return mFilteredRoadResult;
        }

        float alpha = clamp(mRoute.roadSmoothingAlpha, 0.f, 0.95f);
        UNet.Result filtered = copyRoadResult(raw);
        filtered.nearCenter = blendPoint(mFilteredRoadResult.nearCenter, raw.nearCenter, alpha);
        filtered.farCenter = blendPoint(mFilteredRoadResult.farCenter, raw.farCenter, alpha);
        filtered.groundCenter = blendPoint(mFilteredRoadResult.groundCenter, raw.groundCenter, alpha);
        filtered.nearWidth = lerp(raw.nearWidth, mFilteredRoadResult.nearWidth, alpha);
        filtered.farWidth = lerp(raw.farWidth, mFilteredRoadResult.farWidth, alpha);
        mFilteredRoadResult = filtered;
        return filtered;
    }

    private UNet.Result copyRoadResult(UNet.Result src) {
        UNet.Result copy = new UNet.Result();
        copy.groundPixelCount = src.groundPixelCount;
        copy.groundCenter = clonePoint(src.groundCenter);
        copy.nearCenter = clonePoint(src.nearCenter);
        copy.farCenter = clonePoint(src.farCenter);
        copy.nearWidth = src.nearWidth;
        copy.farWidth = src.farWidth;
        return copy;
    }

    private float computeWideRoadOpenness(UNet.Result result) {
        float width = Math.max(result.nearWidth, result.farWidth);
        if (width <= mRoute.wideRoadThreshold) {
            return 0.f;
        }
        return clamp((width - mRoute.wideRoadThreshold) / Math.max(0.05f, 1.f - mRoute.wideRoadThreshold), 0.f, 1.f);
    }

    private PointF blendPoint(PointF previous, PointF current, float alpha) {
        if (previous == null) return clonePoint(current);
        if (current == null) return clonePoint(previous);
        return new PointF(
                lerp(current.x, previous.x, alpha),
                lerp(current.y, previous.y, alpha)
        );
    }

    private PointF clonePoint(PointF src) {
        return src == null ? null : new PointF(src.x, src.y);
    }

    private float lerp(float current, float previous, float alpha) {
        return current * (1.f - alpha) + previous * alpha;
    }

    private float getSearchAngularVelocity(int turnDirection) {
        float sign = turnDirection >= 0 ? 1.f : -1.f;
        return clamp(sign * mRoute.bootstrapAngularVelocity, -mRoute.maxAngularVelocity, mRoute.maxAngularVelocity);
    }
}
