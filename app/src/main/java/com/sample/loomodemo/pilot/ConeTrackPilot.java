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
import com.sample.loomodemo.helper.YoloV3Tiny;
import com.sample.loomodemo.route.ConeTrackRoute;
import com.segway.robot.algo.Pose2D;
import com.segway.robot.sdk.locomotion.head.Head;
import com.segway.robot.sdk.locomotion.sbv.Base;
import com.segway.robot.sdk.vision.Vision;
import com.segway.robot.sdk.vision.frame.Frame;
import com.segway.robot.sdk.vision.stream.StreamInfo;
import com.segway.robot.sdk.vision.stream.StreamType;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Rect2d;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ConeTrackPilot extends Pilot {
    private static final String TAG = "ConeTrackPilot";

    private static class TrackResult {
        PointF nearCenter;
        PointF farCenter;
        int coneCount;
        float trackQuality;
    }

    private static class RegionCenterResult {
        PointF center;
        int leftCount;
        int rightCount;
    }

    private static class ConeDetection {
        float x;
        float y;
        Rect rect;
    }

    private final ConeTrackRoute mRoute;
    private final PresenterChangeInterface mPresenterChangeInterface;
    private final Surface mSurface;
    private final int mStreamType = StreamType.COLOR;
    private final int mFrameWidth;
    private final int mFrameHeight;

    private PilotListener mListener;
    private YoloV3Tiny mObstacleDetector;
    private volatile boolean mRun = true;
    private volatile boolean mPause = false;
    private volatile boolean mIsDetecting = false;
    private volatile long mLastDetectTime = 0;
    private volatile long mLastObstacleDetectTime = 0;
    private volatile int mObstacleClearFrames = 0;
    private volatile boolean mObstacleBlocking = false;
    private volatile YoloV3Tiny.Result mLastObstacle;
    private volatile TrackResult mLastStableTrack;
    private volatile int mTrackReuseFrames = 0;
    private volatile int mLostTrackFrames = 0;
    private Pose2D mLastPose;
    private volatile float mDistanceTravelled = 0.f;
    private volatile boolean mHasArrived = false;
    private volatile TrackResult mFilteredTrack;
    private volatile float mCurrentLinearVelocity = 0.f;
    private volatile float mCurrentAngularVelocity = 0.f;

    public ConeTrackPilot(ConeTrackRoute route, TextureView view, PresenterChangeInterface presenterChangeInterface) {
        super(route);
        mRoute = route;
        mPresenterChangeInterface = presenterChangeInterface;
        mSurface = new Surface(view.getSurfaceTexture());
        StreamInfo info = Vision.getInstance().getStreamInfo(mStreamType);
        mFrameWidth = info.getWidth();
        mFrameHeight = info.getHeight();
        Log.i(TAG, "ConeTrack stream: " + mFrameWidth + " x " + mFrameHeight);
    }

    @Override
    public void setPilotListener(PilotListener listener) {
        mListener = listener;
    }

    @Override
    public void start() {
        mRun = true;
        mPause = false;
        mIsDetecting = false;
        mObstacleClearFrames = 0;
        mObstacleBlocking = false;
        mLastObstacle = null;
        mLastStableTrack = null;
        mTrackReuseFrames = 0;
        mLostTrackFrames = 0;
        mHasArrived = false;
        mFilteredTrack = null;
        mCurrentLinearVelocity = 0.f;
        mCurrentAngularVelocity = 0.f;
        mDistanceTravelled = 0.f;
        mLastPose = SegwayService.base().getOdometryPose(-1);

        Base.getInstance().setControlMode(Base.CONTROL_MODE_RAW);
        Head.getInstance().setMode(Head.MODE_EMOJI);
        Head.getInstance().setHeadJointYaw(0.f);
        Head.getInstance().setWorldPitch(mRoute.headPitch);

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
                Thread.currentThread().interrupt();
            }
        }
        Base.getInstance().setLinearVelocity(0.f);
        Base.getInstance().setAngularVelocity(0.f);
        mCurrentLinearVelocity = 0.f;
        mCurrentAngularVelocity = 0.f;
        mObstacleDetector = null;
        mSurface.release();
    }

    @Override
    public void pause() {
        mPause = true;
        Base.getInstance().setLinearVelocity(0.f);
        Base.getInstance().setAngularVelocity(0.f);
        mCurrentLinearVelocity = 0.f;
        mCurrentAngularVelocity = 0.f;
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
        if (mRoute.maxRunDistance > 0.f && mDistanceTravelled >= mRoute.maxRunDistance) {
            notifyArrival();
            return;
        }

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
            mCurrentLinearVelocity = 0.f;
            mCurrentAngularVelocity = 0.f;
            TcpSrv.getInstance().sendMsg("[cone-track] obstacle blocking, wait");
            return;
        }

        TrackResult track = detectTrack(image);
        if (track != null) {
            TrackResult filtered = smoothTrack(track);
            mLastStableTrack = filtered;
            mTrackReuseFrames = 0;
            mLostTrackFrames = 0;
            driveByTrack(filtered, false);
            return;
        }

        ++mLostTrackFrames;
        if (mLastStableTrack != null && mTrackReuseFrames < mRoute.reuseLastTrackFrames) {
            ++mTrackReuseFrames;
            driveByTrack(mLastStableTrack, true);
            return;
        }

        Base.getInstance().setLinearVelocity(0.f);
        Base.getInstance().setAngularVelocity(0.f);
        mCurrentLinearVelocity = 0.f;
        mCurrentAngularVelocity = 0.f;
        TcpSrv.getInstance().sendMsg("[cone-track] track lost, lostFrames=" + mLostTrackFrames);
        if (mLostTrackFrames >= mRoute.lostTrackThreshold) {
            TcpSrv.getInstance().sendMsg("[cone-track] lost track threshold reached, safety stop");
        }
    }

    private TrackResult detectTrack(Mat image) {
        Mat rgb = new Mat();
        Imgproc.cvtColor(image, rgb, Imgproc.COLOR_RGBA2RGB);
        Mat hsv = new Mat();
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);

        Mat mask = new Mat();
        Core.inRange(
                hsv,
                new Scalar(mRoute.coneHueMin, mRoute.coneSatMin, mRoute.coneValMin),
                new Scalar(mRoute.coneHueMax, mRoute.coneSatMax, mRoute.coneValMax),
                mask
        );

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel);
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Imgproc.findContours(mask, contours, new Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        List<ConeDetection> cones = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            double area = Imgproc.contourArea(contour);
            if (area < mRoute.minConeArea) {
                continue;
            }
            Rect rect = Imgproc.boundingRect(contour);
            ConeDetection cone = new ConeDetection();
            cone.x = (float) (rect.x + rect.width / 2.0) / image.cols();
            cone.y = (float) (rect.y + rect.height / 2.0) / image.rows();
            cone.rect = rect;
            cones.add(cone);
            mPresenterChangeInterface.drawRect(new Rect2d(rect.x, rect.y, rect.width, rect.height));
        }

        if (cones.isEmpty()) {
            return null;
        }

        TrackResult result = new TrackResult();
        PointF splitAnchor = mLastStableTrack != null ? mLastStableTrack.farCenter : null;
        RegionCenterResult nearRegion = computeRegionCenter(
                cones,
                mRoute.nearRegionStart,
                1.01f,
                mRoute.defaultTrackHalfWidthNear,
                mLastStableTrack != null ? mLastStableTrack.nearCenter : null,
                splitAnchor
        );
        RegionCenterResult farRegion = computeRegionCenter(
                cones,
                mRoute.farRegionStart,
                mRoute.nearRegionStart,
                mRoute.defaultTrackHalfWidthFar,
                mLastStableTrack != null ? mLastStableTrack.farCenter : null,
                splitAnchor
        );
        result.nearCenter = nearRegion.center;
        result.farCenter = farRegion.center;
        result.coneCount = cones.size();
        result.trackQuality = computeTrackQuality(nearRegion, farRegion);

        if (result.nearCenter == null || result.farCenter == null) {
            return null;
        }
        return result;
    }

    private RegionCenterResult computeRegionCenter(List<ConeDetection> cones, float minY, float maxY, float defaultHalfWidth,
                                                   PointF fallback, PointF splitAnchor) {
        RegionCenterResult result = new RegionCenterResult();
        List<ConeDetection> region = new ArrayList<>();
        for (ConeDetection cone : cones) {
            if (cone.y >= minY && cone.y < maxY) {
                region.add(cone);
            }
        }
        if (region.isEmpty()) {
            result.center = fallback;
            return result;
        }

        List<ConeDetection> left = new ArrayList<>();
        List<ConeDetection> right = new ArrayList<>();
        float splitX = splitAnchor != null ? clamp(splitAnchor.x, 0.30f, 0.70f) : 0.5f;
        for (ConeDetection cone : region) {
            if (cone.x < splitX) {
                left.add(cone);
            } else {
                right.add(cone);
            }
        }
        result.leftCount = left.size();
        result.rightCount = right.size();

        left.sort(Comparator.comparingDouble(c -> -c.y));
        right.sort(Comparator.comparingDouble(c -> -c.y));

        Float leftX = averageTopX(left, 2);
        Float rightX = averageTopX(right, 2);
        float centerX;
        if (leftX != null && rightX != null) {
            centerX = (leftX + rightX) / 2.f;
        } else if (leftX != null) {
            centerX = leftX + defaultHalfWidth;
        } else if (rightX != null) {
            centerX = rightX - defaultHalfWidth;
        } else {
            result.center = fallback;
            return result;
        }
        centerX = clamp(centerX, 0.1f, 0.9f);
        result.center = new PointF(centerX, (minY + maxY) / 2.f);
        return result;
    }

    private Float averageTopX(List<ConeDetection> cones, int topCount) {
        if (cones.isEmpty()) return null;
        int count = Math.min(topCount, cones.size());
        float sum = 0.f;
        for (int i = 0; i < count; ++i) {
            sum += cones.get(i).x;
        }
        return sum / count;
    }

    private void driveByTrack(TrackResult result, boolean reuseLastTrack) {
        float headingError = applyDeadband(0.5f - result.farCenter.x, mRoute.headingDeadband);
        float centerError = applyDeadband(0.5f - result.nearCenter.x, mRoute.centerDeadband);
        float angularVelocity = mRoute.headingGain * headingError + mRoute.centerGain * centerError;
        angularVelocity = clamp(angularVelocity, -mRoute.maxAngularVelocity, mRoute.maxAngularVelocity);

        float curvature = Math.abs(result.farCenter.x - result.nearCenter.x);
        float visibility = result.coneCount >= mRoute.visibilityMinCones
                ? 1.f
                : (float) result.coneCount / Math.max(1, mRoute.visibilityMinCones);
        float confidence = Math.max(mRoute.trackConfidenceFloor, Math.min(visibility, result.trackQuality));
        float targetLinear = mRoute.cruiseLinearVelocity * (1.f - curvature * mRoute.curveSlowdownGain);
        targetLinear = Math.max(mRoute.minLinearVelocity, targetLinear);
        targetLinear = Math.min(targetLinear, mRoute.maxLinearVelocity);
        float linearVelocity = targetLinear * confidence;
        if (reuseLastTrack) {
            linearVelocity = Math.min(linearVelocity, mRoute.minLinearVelocity);
            angularVelocity *= 0.6f;
        }

        mCurrentLinearVelocity = approach(mCurrentLinearVelocity, linearVelocity, mRoute.linearRampStep);
        mCurrentAngularVelocity = approach(mCurrentAngularVelocity, angularVelocity, mRoute.angularRampStep);
        Base.getInstance().setLinearVelocity(mCurrentLinearVelocity);
        Base.getInstance().setAngularVelocity(mCurrentAngularVelocity);
        TcpSrv.getInstance().sendMsg("[cone-track] cones=" + result.coneCount
                + ", reuse=" + reuseLastTrack
                + ", quality=" + String.format(Locale.US, "%.3f", result.trackQuality)
                + ", near=" + formatPoint(result.nearCenter)
                + ", far=" + formatPoint(result.farCenter)
                + ", v=" + String.format(Locale.US, "%.3f", mCurrentLinearVelocity)
                + ", w=" + String.format(Locale.US, "%.3f", mCurrentAngularVelocity));
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
            boolean inPathDepth = normCenterY >= 0.30;
            if (nearCenter && closeEnough && inPathDepth) {
                if (blocking == null || result.box.width > blocking.box.width) {
                    blocking = result;
                }
            }
        }

        mLastObstacle = blocking;
        if (blocking != null) {
            TcpSrv.getInstance().sendMsg("[cone-track] obstacle cls=" + blocking.clsIdx + ", box=" + blocking.box);
            return true;
        }
        return false;
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

    private void notifyArrival() {
        if (mHasArrived) return;
        mHasArrived = true;
        Base.getInstance().setLinearVelocity(0.f);
        Base.getInstance().setAngularVelocity(0.f);
        mCurrentLinearVelocity = 0.f;
        mCurrentAngularVelocity = 0.f;
        TcpSrv.getInstance().sendMsg("[cone-track] route destination arrived");
        if (mListener != null) {
            boolean isLast = mRoute.toCkptIdx == mRoute.finalCkptIdx;
            new Thread(() -> mListener.onCkptArrived(mRoute.toCkptIdx, isLast)).start();
        }
    }

    private float applyDeadband(float value, float deadband) {
        if (Math.abs(value) <= deadband) return 0.f;
        return value;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private String formatPoint(PointF point) {
        return point == null ? "null" : String.format(Locale.US, "(%.3f,%.3f)", point.x, point.y);
    }

    private float computeTrackQuality(RegionCenterResult nearRegion, RegionCenterResult farRegion) {
        float nearScore = computeRegionSupportScore(nearRegion);
        float farScore = computeRegionSupportScore(farRegion);
        return clamp((nearScore + farScore) / 2.f, 0.f, 1.f);
    }

    private float computeRegionSupportScore(RegionCenterResult region) {
        if (region == null || region.center == null) return 0.f;
        boolean hasLeft = region.leftCount > 0;
        boolean hasRight = region.rightCount > 0;
        if (hasLeft && hasRight) return 1.f;
        if (hasLeft || hasRight) return 0.55f;
        return 0.f;
    }

    private TrackResult smoothTrack(TrackResult raw) {
        if (mFilteredTrack == null) {
            mFilteredTrack = copyTrack(raw);
            return mFilteredTrack;
        }

        float alpha = clamp(mRoute.trackSmoothingAlpha, 0.f, 0.95f);
        TrackResult filtered = copyTrack(raw);
        filtered.nearCenter = blendPoint(mFilteredTrack.nearCenter, raw.nearCenter, alpha);
        filtered.farCenter = blendPoint(mFilteredTrack.farCenter, raw.farCenter, alpha);
        filtered.trackQuality = lerp(raw.trackQuality, mFilteredTrack.trackQuality, alpha);
        mFilteredTrack = filtered;
        return filtered;
    }

    private TrackResult copyTrack(TrackResult src) {
        TrackResult copy = new TrackResult();
        copy.nearCenter = clonePoint(src.nearCenter);
        copy.farCenter = clonePoint(src.farCenter);
        copy.coneCount = src.coneCount;
        copy.trackQuality = src.trackQuality;
        return copy;
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

    private float approach(float current, float target, float step) {
        if (current < target) return Math.min(current + step, target);
        return Math.max(current - step, target);
    }

    private float lerp(float current, float previous, float alpha) {
        return current * (1.f - alpha) + previous * alpha;
    }
}
