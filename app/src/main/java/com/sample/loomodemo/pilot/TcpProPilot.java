package com.sample.loomodemo.pilot;

import android.graphics.Bitmap;

import com.sample.loomodemo.Pilot;
import com.sample.loomodemo.helper.SegwayService;
import com.sample.loomodemo.helper.TcpProSoundFx;
import com.sample.loomodemo.helper.TcpSrv;
import com.sample.loomodemo.helper.TcpVideoSrv;
import com.sample.loomodemo.route.TcpProRoute;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TcpProPilot is the Loom-side runtime for the FPV joystick build.
 *
 * Responsibilities:
 * 1. Receive TCP control commands from the phone on port 6666 via TcpSrv
 * 2. Convert normalized joystick input into safe velocity targets
 * 3. Smooth target changes before pushing them to the Segway Base SDK
 * 4. Stream camera frames to the phone on a dedicated video port
 * 5. Publish status/odometry back to the phone for logs and telemetry
 */
public class TcpProPilot extends Pilot {
    // The base is updated at a fixed control rate. Commands may arrive faster or
    // slower than this, but hardware motion is always filtered through this loop.
    private static final long CONTROL_LOOP_MS = 40L;
    private static final float MAX_HEAD_YAW = 30.f;
    private static final float MIN_HEAD_PITCH = -0.8f;
    private static final float MAX_HEAD_PITCH = 0.8f;

    private static final String SAY_MOVE_ASIDE_CN = "\u8bf7\u8ba9\u4e00\u8ba9";
    private static final String SAY_DONT_COME_CN = "\u4e0d\u8981\u8fc7\u6765\u554a";
    private static final String REVERSE_WARNING_CN = "\u8bf7\u6ce8\u610f\u5012\u8f66";
    private static final String SAY_MOVE_ASIDE_EN = "Please make way.";
    private static final String SAY_DONT_COME_EN = "Stay back.";
    private static final String REVERSE_WARNING_EN = "Reversing.";
    private static final String ENGINE_FALLBACK_TEXT = "Vroom vroom.";
    private static final String BRAKE_FALLBACK_TEXT = "Brake.";

    private final TcpProRoute mRoute;
    private volatile boolean mRun = true;
    private volatile boolean mPause = false;
    private volatile boolean mEmergencyStopLatched = false;
    private volatile long mLastCommandTime = 0L;
    private volatile long mLastStatusTime = 0L;
    private volatile float mTargetLinearVelocity = 0.f;
    private volatile float mTargetAngularVelocity = 0.f;
    private volatile float mAppliedLinearVelocity = 0.f;
    private volatile float mAppliedAngularVelocity = 0.f;
    private volatile float mDistanceTravelled = 0.f;
    private volatile float mHeadingDegrees = 0.f;
    private Pose2D mLastPose;
    private Method mPoseHeadingMethod;
    private final int mStreamType = StreamType.COLOR;
    private final int mFrameWidth;
    private final int mFrameHeight;
    private volatile long mLastVideoFrameTime = 0L;
    private volatile boolean mVideoEncoding = false;
    private volatile boolean mVideoClientConnected = false;
    private TcpVideoSrv mVideoSrv;
    private ExecutorService mVideoExecutor;
    private TcpProSoundFx mSoundFx;
    private boolean mFunSoundAvailable = false;
    private boolean mWasMoving = false;
    private boolean mWasReversing = false;
    private long mLastReverseSpeakTime = 0L;
    private long mLastFallbackEngineSpeakTime = 0L;

    private final TcpSrv.TcpMessageListener mMessageListener = this::handleCommand;

    public TcpProPilot(TcpProRoute route) {
        super(route);
        mRoute = route;
        // Query the camera stream once up front. Incoming Vision frames will use
        // this raw size before we optionally rescale to the configured video size.
        StreamInfo info = Vision.getInstance().getStreamInfo(mStreamType);
        mFrameWidth = info.getWidth();
        mFrameHeight = info.getHeight();
    }

    @Override
    public void start() {
        mRun = true;
        mPause = false;
        mEmergencyStopLatched = false;
        mLastCommandTime = System.currentTimeMillis();
        mLastStatusTime = 0L;
        mTargetLinearVelocity = 0.f;
        mTargetAngularVelocity = 0.f;
        mAppliedLinearVelocity = 0.f;
        mAppliedAngularVelocity = 0.f;
        mDistanceTravelled = 0.f;
        mHeadingDegrees = 0.f;
        mLastPose = SegwayService.base().getOdometryPose(-1);
        mLastVideoFrameTime = 0L;
        mVideoEncoding = false;
        mVideoClientConnected = false;
        mWasMoving = false;
        mWasReversing = false;
        mLastReverseSpeakTime = 0L;
        mLastFallbackEngineSpeakTime = 0L;
        mVideoExecutor = Executors.newSingleThreadExecutor();
        try {
            mSoundFx = new TcpProSoundFx();
            mFunSoundAvailable = true;
        } catch (Throwable t) {
            mSoundFx = null;
            mFunSoundAvailable = false;
            TcpSrv.getInstance().sendMsg("[tcp-pro] sound fx disabled: " + t.getClass().getSimpleName());
        }
        if (mLastPose != null) {
            mHeadingDegrees = extractHeadingDegrees(mLastPose);
        }

        Base.getInstance().setControlMode(Base.CONTROL_MODE_RAW);
        Head.getInstance().setMode(Head.MODE_EMOJI);
        Head.getInstance().setHeadJointYaw(0.f);
        Head.getInstance().setWorldPitch(mRoute.headPitch);
        // The control TCP service is shared globally. TcpPro registers a listener
        // so only this pilot reacts to commands while this route is active.
        TcpSrv.getInstance().addMessageListener(mMessageListener);
        TcpSrv.getInstance().sendMsg("[tcp-pro] ready: JOY <forward> <turn> STOP STATUS PING E_STOP RESET HEAD <yaw> <pitch> SAY_MOVE_ASIDE SAY_DONT_COME");
        TcpSrv.getInstance().sendMsg("[tcp-pro] tts: " + SegwayService.getChineseTtsDebugStatus());
        try {
            mVideoSrv = new TcpVideoSrv(mRoute.videoPort, new TcpVideoSrv.Listener() {
                @Override
                public void onClientConnected() {
                    mVideoClientConnected = true;
                    TcpSrv.getInstance().sendMsg("[tcp-pro] video client connected on " + mRoute.videoPort);
                }

                @Override
                public void onClientDisconnected() {
                    mVideoClientConnected = false;
                    TcpSrv.getInstance().sendMsg("[tcp-pro] video client disconnected");
                }
            });
            mVideoSrv.start();
            TcpSrv.getInstance().sendMsg("[tcp-pro] video ready on port " + mRoute.videoPort);
        } catch (IOException e) {
            TcpSrv.getInstance().sendMsg("[tcp-pro] video server start failed: " + e.getMessage());
        }
        Vision.getInstance().startListenFrame(mStreamType, this::onNewFrame);
        new Thread(this::controlLoop).start();
    }

    @Override
    public void stop() {
        mRun = false;
        TcpSrv.getInstance().removeMessageListener(mMessageListener);
        Vision.getInstance().stopListenFrame(mStreamType);
        if (mVideoSrv != null) {
            mVideoSrv.stop();
            mVideoSrv = null;
        }
        if (mVideoExecutor != null) {
            mVideoExecutor.shutdownNow();
            mVideoExecutor = null;
        }
        if (mSoundFx != null) {
            mSoundFx.close();
            mSoundFx = null;
        }
        mFunSoundAvailable = false;
        stopBaseImmediate();
    }

    @Override
    public void pause() {
        mPause = true;
        stopBaseImmediate();
    }

    @Override
    public void resume() {
        mPause = false;
        mLastCommandTime = System.currentTimeMillis();
    }

    private void controlLoop() {
        while (mRun) {
            if (!mPause) {
                long now = System.currentTimeMillis();
                updateOdometry();
                // If the phone stops sending commands, the watchdog zeroes the
                // robot instead of letting the last motion command live forever.
                if (!mEmergencyStopLatched && now - mLastCommandTime > mRoute.commandTimeoutMs) {
                    if (hasAnyVelocity()) {
                        stopBaseImmediate();
                        TcpSrv.getInstance().sendMsg("[tcp-pro] timeout stop");
                    }
                } else if (!mEmergencyStopLatched) {
                    // Normal case: move the applied velocity a small step toward
                    // the latest target so motion feels smooth instead of jumpy.
                    rampToTargets();
                }
                updateFunSounds(now);

                // Telemetry is throttled separately from control so the app can
                // stay informed without flooding the socket with status text.
                if (now - mLastStatusTime >= mRoute.statusIntervalMs) {
                    mLastStatusTime = now;
                    TcpSrv.getInstance().sendMsg(buildStatusLine());
                }
            }

            try {
                Thread.sleep(CONTROL_LOOP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void onNewFrame(int streamType, Frame frame) {
        if (!mRun || streamType != mStreamType || mVideoSrv == null || !mVideoSrv.hasClient()) return;
        long now = System.currentTimeMillis();
        // Only encode the next frame when the configured interval has elapsed and
        // the previous frame is no longer being compressed. This avoids runaway
        // backlog and keeps latency better than "encode every frame at all costs".
        if (now - mLastVideoFrameTime < mRoute.videoIntervalMs || mVideoEncoding) return;
        mLastVideoFrameTime = now;
        mVideoEncoding = true;

        Mat rgba = new Mat(mFrameHeight, mFrameWidth, CvType.CV_8UC4, frame.getByteBuffer()).clone();
        ExecutorService videoExecutor = mVideoExecutor;
        if (videoExecutor == null) {
            rgba.release();
            mVideoEncoding = false;
            return;
        }
        videoExecutor.execute(() -> {
            try {
                encodeAndSendFrame(rgba, now);
            } finally {
                rgba.release();
                mVideoEncoding = false;
            }
        });
    }

    private void handleCommand(String command) {
        if (!mRun) return;
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) return;

        String upper = trimmed.toUpperCase(Locale.US);
        mLastCommandTime = System.currentTimeMillis();

        // Lightweight "always safe" commands are handled before any motion guard.
        if ("PING".equals(upper)) {
            TcpSrv.getInstance().sendMsg("[tcp-pro] PONG");
            return;
        }
        if ("STATUS".equals(upper)) {
            TcpSrv.getInstance().sendMsg(buildStatusLine());
            return;
        }
        if ("STOP".equals(upper)) {
            stopBaseImmediate();
            TcpSrv.getInstance().sendMsg("[tcp-pro] STOP");
            return;
        }
        if ("E_STOP".equals(upper) || "ESTOP".equals(upper)) {
            mEmergencyStopLatched = true;
            stopBaseImmediate();
            TcpSrv.getInstance().sendMsg("[tcp-pro] E-STOP latched");
            TcpSrv.getInstance().sendMsg(buildStatusLine());
            return;
        }
        if ("RESET".equals(upper)) {
            mEmergencyStopLatched = false;
            stopBaseImmediate();
            TcpSrv.getInstance().sendMsg("[tcp-pro] RESET");
            TcpSrv.getInstance().sendMsg(buildStatusLine());
            return;
        }
        if ("SAY_MOVE_ASIDE".equals(upper)) {
            TcpSrv.getInstance().sendMsg("[tcp-pro] speaking: move aside");
            SegwayService.speakChineseOrFallback(SAY_MOVE_ASIDE_CN, SAY_MOVE_ASIDE_EN);
            TcpSrv.getInstance().sendMsg("[tcp-pro] SAY_MOVE_ASIDE");
            return;
        }
        if ("SAY_DONT_COME".equals(upper)) {
            TcpSrv.getInstance().sendMsg("[tcp-pro] speaking: stay back");
            SegwayService.speakChineseOrFallback(SAY_DONT_COME_CN, SAY_DONT_COME_EN);
            TcpSrv.getInstance().sendMsg("[tcp-pro] SAY_DONT_COME");
            return;
        }
        if (upper.startsWith("HEAD ")) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 3) {
                try {
                    // Yaw comes from the app in degrees because that is easier for
                    // humans to reason about; the SDK itself expects radians.
                    float yawDeg = clamp(Float.parseFloat(parts[1]), -MAX_HEAD_YAW, MAX_HEAD_YAW);
                    float pitch = clamp(Float.parseFloat(parts[2]), MIN_HEAD_PITCH, MAX_HEAD_PITCH);
                    float yawRad = (float) Math.toRadians(yawDeg);
                    Head.getInstance().setHeadJointYaw(yawRad);
                    Head.getInstance().setWorldPitch(pitch);
                    TcpSrv.getInstance().sendMsg("[tcp-pro] HEAD -> yawDeg=" + formatFloat(yawDeg)
                            + ", yawRad=" + formatFloat(yawRad)
                            + ", pitch=" + formatFloat(pitch));
                } catch (NumberFormatException ex) {
                    TcpSrv.getInstance().sendMsg("[tcp-pro] invalid HEAD command");
                }
            } else {
                TcpSrv.getInstance().sendMsg("[tcp-pro] usage: HEAD <yaw> <pitch>");
            }
            return;
        }

        if (mEmergencyStopLatched) {
            TcpSrv.getInstance().sendMsg("[tcp-pro] E-STOP active, send RESET first");
            return;
        }

        if (upper.startsWith("JOY ")) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 3) {
                try {
                    // JOY is normalized in the phone app to [-1, 1]. Loom clamps
                    // again here so malformed or spoofed packets stay bounded.
                    float forwardNorm = clamp(Float.parseFloat(parts[1]), -1.f, 1.f);
                    float turnNorm = clamp(Float.parseFloat(parts[2]), -1.f, 1.f);
                    applyJoystick(forwardNorm, turnNorm);
                } catch (NumberFormatException ex) {
                    TcpSrv.getInstance().sendMsg("[tcp-pro] invalid JOY command");
                }
            } else {
                TcpSrv.getInstance().sendMsg("[tcp-pro] usage: JOY <forward> <turn>");
            }
            return;
        }

        if (upper.startsWith("VEL ")) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 3) {
                try {
                    float linear = Float.parseFloat(parts[1]);
                    float angular = Float.parseFloat(parts[2]);
                    setVelocityTargets(
                            clamp(linear, -mRoute.maxReverseLinearVelocity, mRoute.maxLinearVelocity),
                            clamp(angular, -mRoute.maxAngularVelocity, mRoute.maxAngularVelocity)
                    );
                } catch (NumberFormatException ex) {
                    TcpSrv.getInstance().sendMsg("[tcp-pro] invalid VEL command");
                }
            } else {
                TcpSrv.getInstance().sendMsg("[tcp-pro] usage: VEL <linear> <angular>");
            }
            return;
        }

        TcpSrv.getInstance().sendMsg("[tcp-pro] unknown command: " + trimmed);
    }

    private void applyJoystick(float forwardNorm, float turnNorm) {
        // Human fingers rarely hold a mathematically perfect "pure turn". When
        // lateral throw is strong and forward throw is tiny, snap forward to zero
        // so the robot pivots in place more reliably on narrow roads.
        if (Math.abs(turnNorm) >= mRoute.pivotTurnAssistMinTurn
                && Math.abs(forwardNorm) <= mRoute.pivotTurnAssistMaxForward) {
            forwardNorm = 0.f;
        }

        // Deadband removes center noise; expo makes the first half of stick travel
        // gentler while preserving full authority at the outer edge.
        float forward = applyExpoAndDeadband(forwardNorm, mRoute.joystickDeadband, mRoute.linearExpo);
        float turn = applyExpoAndDeadband(turnNorm, mRoute.joystickDeadband, mRoute.angularExpo);

        // Positive forward uses the normal max speed, negative forward uses the
        // smaller reverse cap so backing up stays easier to control.
        float linear = forward >= 0.f
                ? forward * mRoute.maxLinearVelocity
                : forward * mRoute.maxReverseLinearVelocity;
        float steerMag = Math.abs(turn);

        // As steering demand increases, automatically shave linear speed. This is
        // one of the main reasons TcpPro feels easier to drive in narrow turns.
        float turnSlowScale = 1.0f - clamp(mRoute.turnSlowdownGain, 0.f, 0.95f) * steerMag;
        linear *= clamp(turnSlowScale, 0.15f, 1.0f);

        // The Segway base interprets positive angular velocity as left turn.
        // The phone UI uses positive X for "right", so we invert the sign here.
        float turnBoost = 1.0f + clamp(mRoute.lowSpeedTurnBoost, 0.f, 1.0f) * (1.0f - Math.min(1.0f, Math.abs(forward)));
        float angular = -turn * mRoute.maxAngularVelocity * turnBoost;

        // On very hard turns, apply a stricter linear cap so the robot commits to
        // turning rather than trying to drive fast and wide through the corner.
        if (steerMag >= clamp(mRoute.sharpTurnThreshold, 0.40f, 0.95f)) {
            float forwardCap = mRoute.maxLinearVelocity * clamp(mRoute.sharpTurnLinearScale, 0.10f, 0.80f);
            float reverseCap = mRoute.maxReverseLinearVelocity * clamp(mRoute.sharpTurnLinearScale, 0.10f, 0.80f);
            linear = clamp(linear, -reverseCap, forwardCap);
        }

        angular = clamp(angular, -mRoute.maxAngularVelocity, mRoute.maxAngularVelocity);
        setVelocityTargets(linear, angular);
    }

    private float applyExpoAndDeadband(float value, float deadband, float expo) {
        float abs = Math.abs(value);
        if (abs <= deadband) return 0.f;
        // After removing the deadband, stretch the remaining [deadband..1] range
        // back to [0..1] before the exponential shaping is applied.
        float normalized = (abs - deadband) / Math.max(0.0001f, 1.f - deadband);
        float shaped = (float) Math.pow(normalized, Math.max(1.0f, expo));
        return Math.signum(value) * shaped;
    }

    private void setVelocityTargets(float linear, float angular) {
        // Targets are the "desired motion". They are not sent to the motors
        // directly; the 40 ms control loop ramps the applied values toward them.
        mTargetLinearVelocity = clamp(linear, -mRoute.maxReverseLinearVelocity, mRoute.maxLinearVelocity);
        mTargetAngularVelocity = clamp(angular, -mRoute.maxAngularVelocity, mRoute.maxAngularVelocity);
    }

    private void rampToTargets() {
        float nextLinear = moveToward(
                mAppliedLinearVelocity,
                mTargetLinearVelocity,
                selectStep(mAppliedLinearVelocity, mTargetLinearVelocity, mRoute.linearRampStep, mRoute.linearBrakeStep)
        );
        float effectiveAngularTarget = applySpeedTurnCoupling(mTargetAngularVelocity, nextLinear);
        float nextAngular = moveToward(
                mAppliedAngularVelocity,
                effectiveAngularTarget,
                selectStep(mAppliedAngularVelocity, effectiveAngularTarget, mRoute.angularRampStep, mRoute.angularBrakeStep)
        );
        if (Math.abs(nextLinear - mAppliedLinearVelocity) < 0.0005f
                && Math.abs(nextAngular - mAppliedAngularVelocity) < 0.0005f) {
            return;
        }
        // Only the smoothed values are ever written to the base SDK.
        Base.getInstance().setLinearVelocity(nextLinear);
        Base.getInstance().setAngularVelocity(nextAngular);
        mAppliedLinearVelocity = nextLinear;
        mAppliedAngularVelocity = nextAngular;
    }

    private float moveToward(float current, float target, float step) {
        if (step <= 0.f) return target;
        float delta = target - current;
        if (Math.abs(delta) <= step) return target;
        return current + Math.signum(delta) * step;
    }

    private float selectStep(float current, float target, float rampStep, float brakeStep) {
        // If magnitude is decreasing or the sign flips, treat it as braking so the
        // robot can stop faster than it accelerates.
        boolean braking = current * target < 0.f || Math.abs(target) < Math.abs(current);
        return braking ? brakeStep : rampStep;
    }

    private float applySpeedTurnCoupling(float angularTarget, float linearVelocity) {
        float maxLinear = Math.max(mRoute.maxLinearVelocity, mRoute.maxReverseLinearVelocity);
        if (maxLinear <= 0.0001f) return angularTarget;
        // High forward speed automatically reduces effective turn command. This is
        // a simple but effective stability measure for FPV driving.
        float speedRatio = clamp(Math.abs(linearVelocity) / maxLinear, 0.f, 1.f);
        float scale = 1.0f - clamp(mRoute.speedTurnCoupling, 0.f, 0.95f) * speedRatio;
        return angularTarget * scale;
    }

    private boolean hasAnyVelocity() {
        return Math.abs(mTargetLinearVelocity) > 0.001f
                || Math.abs(mTargetAngularVelocity) > 0.001f
                || Math.abs(mAppliedLinearVelocity) > 0.001f
                || Math.abs(mAppliedAngularVelocity) > 0.001f;
    }

    private void stopBaseImmediate() {
        // Used by E-STOP, timeout, pause, and STOP command. Targets and applied
        // values are both cleared so the control loop does not ramp back up.
        mTargetLinearVelocity = 0.f;
        mTargetAngularVelocity = 0.f;
        mAppliedLinearVelocity = 0.f;
        mAppliedAngularVelocity = 0.f;
        Base.getInstance().setLinearVelocity(0.f);
        Base.getInstance().setAngularVelocity(0.f);
    }

    private void updateFunSounds(long now) {
        // Sound effects are explicitly optional. TcpPro must remain driveable even
        // if the Loom audio path fails or is not supported by the current device.
        boolean moving = Math.abs(mAppliedLinearVelocity) > 0.03f || Math.abs(mAppliedAngularVelocity) > 0.10f;
        boolean reversing = mAppliedLinearVelocity < -0.05f;

        if (mFunSoundAvailable && mSoundFx != null) {
            try {
                mSoundFx.setMotion(
                        mAppliedLinearVelocity,
                        mAppliedAngularVelocity,
                        mRoute.maxLinearVelocity,
                        mRoute.maxAngularVelocity
                );
                if (mWasMoving && !moving) {
                    mSoundFx.playBrake();
                }
            } catch (Throwable t) {
                mFunSoundAvailable = false;
                TcpSrv.getInstance().sendMsg("[tcp-pro] sound fx stopped: " + t.getClass().getSimpleName());
            }
        } else {
            if (!mWasMoving && moving && now - mLastFallbackEngineSpeakTime > 2500L) {
                SegwayService.speak(ENGINE_FALLBACK_TEXT);
                mLastFallbackEngineSpeakTime = now;
            } else if (mWasMoving && !moving) {
                SegwayService.speak(BRAKE_FALLBACK_TEXT);
            }
        }

        if (reversing && (!mWasReversing || now - mLastReverseSpeakTime > 3500L)) {
            TcpSrv.getInstance().sendMsg("[tcp-pro] speaking: reversing warning");
            SegwayService.speakChineseOrFallback(REVERSE_WARNING_CN, REVERSE_WARNING_EN);
            mLastReverseSpeakTime = now;
        }

        mWasMoving = moving;
        mWasReversing = reversing;
    }

    private String buildStatusLine() {
        return "[tcp-pro] status: mode=" + getModeString()
                + ", estop=" + (mEmergencyStopLatched ? "1" : "0")
                + ", video=" + (mVideoClientConnected ? "1" : "0")
                + ", targetV=" + formatFloat(mTargetLinearVelocity)
                + ", targetW=" + formatFloat(mTargetAngularVelocity)
                + ", v=" + formatFloat(mAppliedLinearVelocity)
                + ", w=" + formatFloat(mAppliedAngularVelocity)
                + ", dist=" + formatFloat(mDistanceTravelled)
                + ", heading=" + formatFloat(mHeadingDegrees);
    }

    private String getModeString() {
        if (mPause) return "PAUSED";
        if (mEmergencyStopLatched) return "E_STOP";
        if (Math.abs(mTargetLinearVelocity) > 0.001f || Math.abs(mTargetAngularVelocity) > 0.001f
                || Math.abs(mAppliedLinearVelocity) > 0.001f || Math.abs(mAppliedAngularVelocity) > 0.001f) {
            return "JOYSTICK";
        }
        return "IDLE";
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void updateOdometry() {
        Pose2D currentPose = SegwayService.base().getOdometryPose(-1);
        if (currentPose == null) return;
        if (mLastPose != null) {
            // Accumulate travel distance from odometry deltas so the phone can show
            // progress even though TcpPro is not following a preplanned route.
            double dx = currentPose.getX() - mLastPose.getX();
            double dy = currentPose.getY() - mLastPose.getY();
            mDistanceTravelled += (float) Math.sqrt(dx * dx + dy * dy);
        }
        mHeadingDegrees = extractHeadingDegrees(currentPose);
        mLastPose = currentPose;
    }

    private float extractHeadingDegrees(Pose2D pose) {
        if (pose == null) return mHeadingDegrees;
        try {
            if (mPoseHeadingMethod == null) {
                // Different SDK builds expose heading under different method names.
                // Reflection keeps TcpPro more portable across Loom environments.
                for (String name : new String[]{"getTheta", "getAngle", "getYaw"}) {
                    try {
                        mPoseHeadingMethod = pose.getClass().getMethod(name);
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
            if (mPoseHeadingMethod != null) {
                Object raw = mPoseHeadingMethod.invoke(pose);
                if (raw instanceof Number) {
                    return normalizeDegrees((float) Math.toDegrees(((Number) raw).doubleValue()));
                }
            }
        } catch (Exception ignored) {
        }
        return mHeadingDegrees;
    }

    private float normalizeDegrees(float degrees) {
        float normalized = degrees % 360.f;
        if (normalized > 180.f) normalized -= 360.f;
        if (normalized < -180.f) normalized += 360.f;
        return normalized;
    }

    private void encodeAndSendFrame(Mat rgba, long timestampMs) {
        if (mVideoSrv == null || !mVideoSrv.hasClient()) return;

        // The Vision SDK gives us RGBA bytes. We convert them into a Bitmap,
        // optionally rescale, JPEG-compress, and then push the result over TCP.
        Bitmap full = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgba, full, true);

        Bitmap scaled = full;
        if (mRoute.videoWidth > 0 && mRoute.videoHeight > 0
                && (full.getWidth() != mRoute.videoWidth || full.getHeight() != mRoute.videoHeight)) {
            scaled = Bitmap.createScaledBitmap(full, mRoute.videoWidth, mRoute.videoHeight, true);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, clamp(mRoute.videoJpegQuality, 20, 95), out);
        mVideoSrv.sendFrame(out.toByteArray(), scaled.getWidth(), scaled.getHeight(), timestampMs);

        if (scaled != full) scaled.recycle();
        full.recycle();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String formatFloat(float value) {
        return String.format(Locale.US, "%.3f", value);
    }
}
