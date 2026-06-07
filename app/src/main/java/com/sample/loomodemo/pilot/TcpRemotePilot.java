package com.sample.loomodemo.pilot;

import android.util.Log;

import com.sample.loomodemo.Pilot;
import com.sample.loomodemo.route.TcpRemoteRoute;
import com.sample.loomodemo.helper.TcpSrv;
import com.segway.robot.sdk.locomotion.head.Head;
import com.segway.robot.sdk.locomotion.sbv.Base;

import java.util.Locale;

public class TcpRemotePilot extends Pilot {
    private static final String TAG = "TcpRemotePilot";

    private final TcpRemoteRoute mRoute;
    private volatile boolean mRun = true;
    private volatile boolean mPause = false;
    private volatile long mLastCommandTime = 0L;
    private volatile float mLastLinearVelocity = 0.f;
    private volatile float mLastAngularVelocity = 0.f;
    private volatile boolean mCruiseEnabled = false;

    private final TcpSrv.TcpMessageListener mMessageListener = this::handleCommand;

    public TcpRemotePilot(TcpRemoteRoute route) {
        super(route);
        mRoute = route;
    }

    @Override
    public void start() {
        mRun = true;
        mPause = false;
        mCruiseEnabled = false;
        mLastCommandTime = System.currentTimeMillis();
        Base.getInstance().setControlMode(Base.CONTROL_MODE_RAW);
        Head.getInstance().setMode(Head.MODE_EMOJI);
        Head.getInstance().setHeadJointYaw(0.f);
        Head.getInstance().setWorldPitch(mRoute.headPitch);
        TcpSrv.getInstance().addMessageListener(mMessageListener);
        TcpSrv.getInstance().sendMsg("[tcp-remote] ready: FORWARD BACKWARD LEFT RIGHT FORWARD_LEFT FORWARD_RIGHT STOP VEL <linear> <angular> CRUISE <linear> <angular> PING");
        new Thread(this::watchdogLoop).start();
    }

    @Override
    public void stop() {
        mRun = false;
        TcpSrv.getInstance().removeMessageListener(mMessageListener);
        stopBase();
    }

    @Override
    public void pause() {
        mPause = true;
        mCruiseEnabled = false;
        stopBase();
    }

    @Override
    public void resume() {
        mPause = false;
    }

    private void watchdogLoop() {
        while (mRun) {
            if (!mPause && !mCruiseEnabled && System.currentTimeMillis() - mLastCommandTime > mRoute.commandTimeoutMs) {
                if (Math.abs(mLastLinearVelocity) > 0.001f || Math.abs(mLastAngularVelocity) > 0.001f) {
                    stopBase();
                    TcpSrv.getInstance().sendMsg("[tcp-remote] timeout stop");
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void handleCommand(String command) {
        if (!mRun) return;
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) return;

        String upper = trimmed.toUpperCase(Locale.US);
        mLastCommandTime = System.currentTimeMillis();

        if ("PING".equals(upper)) {
            TcpSrv.getInstance().sendMsg("[tcp-remote] PONG");
            return;
        }
        if ("FORWARD".equals(upper)) {
            mCruiseEnabled = false;
            applyVelocity(mRoute.forwardLinearVelocity, 0.f, "FORWARD");
            return;
        }
        if ("BACKWARD".equals(upper)) {
            mCruiseEnabled = false;
            applyVelocity(-mRoute.backwardLinearVelocity, 0.f, "BACKWARD");
            return;
        }
        if ("LEFT".equals(upper)) {
            mCruiseEnabled = false;
            applyVelocity(0.f, mRoute.turnAngularVelocity, "LEFT");
            return;
        }
        if ("RIGHT".equals(upper)) {
            mCruiseEnabled = false;
            applyVelocity(0.f, -mRoute.turnAngularVelocity, "RIGHT");
            return;
        }
        if ("FORWARD_LEFT".equals(upper) || "FORWARDLEFT".equals(upper)) {
            mCruiseEnabled = false;
            applyVelocity(mRoute.forwardLinearVelocity, mRoute.turnAngularVelocity, "FORWARD_LEFT");
            return;
        }
        if ("FORWARD_RIGHT".equals(upper) || "FORWARDRIGHT".equals(upper)) {
            mCruiseEnabled = false;
            applyVelocity(mRoute.forwardLinearVelocity, -mRoute.turnAngularVelocity, "FORWARD_RIGHT");
            return;
        }
        if ("STOP".equals(upper)) {
            mCruiseEnabled = false;
            stopBase();
            TcpSrv.getInstance().sendMsg("[tcp-remote] STOP");
            return;
        }
        if (upper.startsWith("VEL ")) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 3) {
                try {
                    float linear = Float.parseFloat(parts[1]);
                    float angular = Float.parseFloat(parts[2]);
                    mCruiseEnabled = false;
                    applyVelocity(linear, angular, "VEL");
                } catch (NumberFormatException ex) {
                    Log.w(TAG, "invalid VEL command: " + trimmed, ex);
                    TcpSrv.getInstance().sendMsg("[tcp-remote] invalid VEL command");
                }
            } else {
                TcpSrv.getInstance().sendMsg("[tcp-remote] usage: VEL <linear> <angular>");
            }
            return;
        }
        if (upper.startsWith("CRUISE ")) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 3) {
                try {
                    float linear = Float.parseFloat(parts[1]);
                    float angular = Float.parseFloat(parts[2]);
                    mCruiseEnabled = true;
                    applyVelocity(linear, angular, "CRUISE");
                } catch (NumberFormatException ex) {
                    Log.w(TAG, "invalid CRUISE command: " + trimmed, ex);
                    TcpSrv.getInstance().sendMsg("[tcp-remote] invalid CRUISE command");
                }
            } else {
                TcpSrv.getInstance().sendMsg("[tcp-remote] usage: CRUISE <linear> <angular>");
            }
            return;
        }

        TcpSrv.getInstance().sendMsg("[tcp-remote] unknown command: " + trimmed);
    }

    private void applyVelocity(float linear, float angular, String source) {
        if (mPause) return;
        float safeLinear = clamp(linear, -mRoute.maxLinearVelocity, mRoute.maxLinearVelocity);
        float safeAngular = clamp(angular, -mRoute.maxAngularVelocity, mRoute.maxAngularVelocity);
        Base.getInstance().setLinearVelocity(safeLinear);
        Base.getInstance().setAngularVelocity(safeAngular);
        mLastLinearVelocity = safeLinear;
        mLastAngularVelocity = safeAngular;
        TcpSrv.getInstance().sendMsg("[tcp-remote] " + source + " -> v="
                + String.format(Locale.US, "%.3f", safeLinear)
                + ", w=" + String.format(Locale.US, "%.3f", safeAngular));
    }

    private void stopBase() {
        Base.getInstance().setLinearVelocity(0.f);
        Base.getInstance().setAngularVelocity(0.f);
        mLastLinearVelocity = 0.f;
        mLastAngularVelocity = 0.f;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
