package com.sample.loomodemo.pilot;

import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import com.sample.loomodemo.Pilot;
import com.sample.loomodemo.helper.HeadControlHandlerImpl;
import com.sample.loomodemo.helper.PresenterChangeInterface;
import com.sample.loomodemo.helper.SegwayService;
import com.sample.loomodemo.route.DTSRoute;
import com.segway.robot.algo.dts.BaseControlCommand;
import com.segway.robot.algo.dts.DTSPerson;
import com.segway.robot.algo.dts.PersonDetectListener;
import com.segway.robot.algo.dts.PersonTrackingListener;
import com.segway.robot.algo.dts.PersonTrackingProfile;
import com.segway.robot.algo.dts.PersonTrackingWithPlannerListener;
import com.segway.robot.sdk.locomotion.head.Head;
import com.segway.robot.sdk.locomotion.sbv.Base;
import com.segway.robot.sdk.vision.DTS;
import com.segway.robot.sdk.vision.Vision;
import com.segway.robot.support.control.HeadPIDController;

public class DTSPilot extends Pilot {
    public DTSPilot(DTSRoute route, PresenterChangeInterface presenterChangeInterface, TextureView view) {
        super(route);
        mRoute = route;
        mTextureView = view;
        mPresenterChangeInterface = presenterChangeInterface;
    }
    private static final String TAG = "DTSPilot";
    private final DTSRoute mRoute;
    private final TextureView mTextureView;
    private final Vision mVision = Vision.getInstance();
    private final Head mHead = Head.getInstance();
    private final Base mBase = Base.getInstance();
    private final PresenterChangeInterface mPresenterChangeInterface;
    private final HeadPIDController mHeadPIDController = new HeadPIDController();
    private DTS mDts;
    private boolean mPause = false;
    /**
     * the second parameter is the distance between loomo and the followed target. must > 1.0f
     */
    private final PersonTrackingProfile mPersonTrackingProfile = new PersonTrackingProfile(3, 1.0f);

    @Override
    public void start() {
        mHeadPIDController.init(new HeadControlHandlerImpl(mHead));
        mHeadPIDController.setHeadFollowFactor(1.0f);

        mDts = mVision.getDTS();
        mDts.setVideoSource(DTS.VideoSource.CAMERA);
        Surface surface = new Surface(mTextureView.getSurfaceTexture());
        mDts.setPreviewDisplay(surface);
        mDts.start();

        // 调整好 head 的角度再开始检测
        SegwayService.head().setHeadJointYaw(0.0f);
        SegwayService.head().setWorldPitch(0.8f);
        SegwayService.speak("start detect person");
        // 开始检测人体
        mDts.startDetectingPerson(mPersonDetectListener);
    }

    @Override
    public void stop() {
        mDts.stop();
        mHeadPIDController.stop();
    }

    @Override
    public void pause() {
        mPause = true;
    }

    @Override
    public void resume() {
        mPause = false;
    }

    // 人体检测侦听器
    private final PersonDetectListener mPersonDetectListener = new PersonDetectListener() {
        @Override
        public void onPersonDetected(DTSPerson[] person) {
            if (person.length == 0) {
                return;
            }
            Log.i(TAG, "onPersonDetected: " + person.length);
            mPresenterChangeInterface.drawPersons(person);
            mHead.setMode(Head.MODE_ORIENTATION_LOCK);
            mHeadPIDController.updateTarget(person[0].getTheta(), person[0].getDrawingRect(), 480);
            SegwayService.speak("Found you, will start follow!");

            // 开启一个新线程停止检测开始跟踪
            new Thread(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                // 停止检测
                Log.i(TAG, "run: Will stop detect person");
                mDts.stopDetectingPerson();
                // 开始跟踪
                Log.i(TAG, "run: Will start track person");
                startTracker(person[0]);
            }).start();
        }

        @Override
        public void onPersonDetectionResult(DTSPerson[] person) {
        }

        @Override
        public void onPersonDetectionError(int errorCode, String message) {
            Log.e(TAG, "Person Detect Error: " + errorCode + ", " + message);
        }
    };

    // 开始跟踪人体
    private void startTracker(DTSPerson person) {
        if (mRoute.avoidObstacle) {
            // Loomo will detect obstacles and avoid them when invoke startPlannerPersonTracking()
            mDts.startPlannerPersonTracking(person, mPersonTrackingProfile, 60 * 1000 * 1000, mPersonTrackingWithPlannerListener);
        } else {
            // Without obstacle detection and avoidance
            mDts.startPersonTracking(person, 60 * 1000 * 1000, mPersonTrackingListener);
        }
    }

    // 人体跟踪
    private final PersonTrackingListener mPersonTrackingListener = new PersonTrackingListener() {
        @Override
        public void onPersonTracking(DTSPerson person) {
            mPresenterChangeInterface.drawPerson(person);
            mHead.setMode(Head.MODE_ORIENTATION_LOCK);
            mHeadPIDController.updateTarget(person.getTheta(), person.getDrawingRect(), 480);

            if (mPause) {
                return;
            }
            mBase.setControlMode(Base.CONTROL_MODE_FOLLOW_TARGET);
            float personDistance = person.getDistance();
            // There is a bug in DTS, while using person.getDistance(), please check the result
            // The correct distance is between 0.35 meters and 5 meters
            if (personDistance > 0.35 && personDistance < 5) {
                float followDistance = (float) (personDistance - 1.2);
                float theta = person.getTheta();
                mBase.updateTarget(followDistance, theta);
            }
        }

        @Override
        public void onPersonTrackingResult(DTSPerson person) {
        }

        @Override
        public void onPersonTrackingError(int errorCode, String message) {
            Log.e(TAG, "PersonTrackingListener: " + message);
        }
    };

    // 带主动避障的人体跟踪
    private final PersonTrackingWithPlannerListener mPersonTrackingWithPlannerListener = new PersonTrackingWithPlannerListener() {
        @Override
        public void onPersonTrackingWithPlannerResult(DTSPerson person, BaseControlCommand baseControlCommand) {
            mPresenterChangeInterface.drawPerson(person);
            mHead.setMode(Head.MODE_ORIENTATION_LOCK);
            mHeadPIDController.updateTarget(person.getTheta(), person.getDrawingRect(), 480);

            switch (baseControlCommand.getFollowState()) {
                case BaseControlCommand.State.NORMAL_FOLLOW:
                    setBaseVelocity(baseControlCommand.getLinearVelocity(), baseControlCommand.getAngularVelocity());
                    break;
                case BaseControlCommand.State.HEAD_FOLLOW_BASE:
                    mBase.setControlMode(Base.CONTROL_MODE_FOLLOW_TARGET);
                    mBase.updateTarget(0, person.getTheta());
                    break;
                case BaseControlCommand.State.SENSOR_ERROR:
                    setBaseVelocity(0, 0);
                    break;
            }
        }

        @Override
        public void onPersonTrackingWithPlannerError(int errorCode, String message) {
            Log.e(TAG, "PersonTrackingWithPlannerListener: " + message);
        }
    };

    private void setBaseVelocity(float linearVelocity, float angularVelocity) {
        mBase.setControlMode(Base.CONTROL_MODE_RAW);
        mBase.setLinearVelocity(linearVelocity);
        mBase.setAngularVelocity(angularVelocity);
    }
}
