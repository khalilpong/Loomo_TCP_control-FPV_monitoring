package com.sample.loomodemo;

import android.graphics.PointF;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import com.sample.loomodemo.databinding.ActivityMainBinding;
import com.sample.loomodemo.helper.CommHelper;
import com.sample.loomodemo.helper.PresenterChangeInterface;
import com.sample.loomodemo.helper.SegwayService;
import com.sample.loomodemo.helper.TcpSrv;
import com.sample.loomodemo.helper.ToastUtil;
import com.sample.loomodemo.helper.VoiceControl;
import com.sample.loomodemo.pilot.DTSPilot;
import com.sample.loomodemo.pilot.ConeTrackPilot;
import com.sample.loomodemo.pilot.TcpProPilot;
import com.sample.loomodemo.pilot.TcpRemotePilot;
import com.sample.loomodemo.pilot.UNetRoadPilot;
import com.sample.loomodemo.pilot.VLSPilot;
import com.sample.loomodemo.pilot.VlsYoloPilot;
import com.sample.loomodemo.pilot.YoloAndUnetPilot;
import com.sample.loomodemo.pilot.YoloPilot;
import com.sample.loomodemo.route.DTSRoute;
import com.sample.loomodemo.route.ConeTrackRoute;
import com.sample.loomodemo.route.TcpProRoute;
import com.sample.loomodemo.route.TcpRemoteRoute;
import com.sample.loomodemo.route.UNetRoadRoute;
import com.sample.loomodemo.route.VlsYoloRoute;
import com.sample.loomodemo.route.YoloAndUnetRoute;
import com.sample.loomodemo.route.YoloRoute;
import com.sample.loomodemo.view.AutoFitDrawableView;
import com.segway.robot.algo.dts.DTSPerson;

import org.opencv.android.InstallCallbackInterface;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Rect2d;

import java.util.Locale;

import static com.sample.loomodemo.helper.RouteDetailHelper.getRouteDetail;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
            Log.i(TAG, "onSurfaceTextureAvailable");
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {}

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}
    };

    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;
    private AutoFitDrawableView mAutoFitDrawableView;
    private final PresenterChangeInterface mPresenterChangeInterface = new PresenterChangeInterface() {
        @Override
        public void showToast(final String message) {
            runOnUiThread(() -> ToastUtil.showToast(MainActivity.this, message));
        }

        @Override
        public void drawPersons(DTSPerson[] dtsPersons) {
            mAutoFitDrawableView.drawRect(dtsPersons);
        }

        @Override
        public void drawPerson(DTSPerson dtsPerson) {
            mAutoFitDrawableView.drawRect(dtsPerson.getDrawingRect());
        }

        @Override
        public void drawRect(Rect2d rect) {
            int xConvert = PREVIEW_WIDTH - (int) rect.x;
            android.graphics.Rect temp = new android.graphics.Rect(
                    xConvert,
                    (int) rect.y,
                    (int) (xConvert + rect.width),
                    (int) (rect.y + rect.height)
            );
            mAutoFitDrawableView.drawRect(temp);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        enableFullScreen();
        setContentView(binding.getRoot());
        binding.startStop.setEnabled(false);

        TcpSrv.getInstance().sendMsg("hello world");

        mCfg = new AppConfig();
        if (!mCfg.isValid()) {
            binding.startStop.setText("config load failed");
            return;
        }
        binding.checkPointCnt.setText(String.format(Locale.CHINA, "%d", mCfg.getCheckpoints().length - 1));
        binding.startStop.setOnClickListener(view -> {
            if (mRunning) stop();
            else start();
        });

        binding.curState.setText("idle");

        SegwayService.bindService(getApplicationContext(), this::onBindDone);

        Log.i(TAG, "will init opencv");
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mOpencvLoadCB);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            mOpencvLoadCB.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    private final LoaderCallbackInterface mOpencvLoadCB = new LoaderCallbackInterface() {
        @Override
        public void onManagerConnected(int status) {
            Log.i(TAG, "opencv onManagerConnected " + status);
        }

        @Override
        public void onPackageInstall(int operation, InstallCallbackInterface callback) {
            Log.i(TAG, "opencv onPackageInstall: " + operation);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        mAutoFitDrawableView = binding.autoDrawable;
        mAutoFitDrawableView.setPreviewSizeAndRotation(PREVIEW_WIDTH, PREVIEW_HEIGHT, Surface.ROTATION_0);
        mAutoFitDrawableView.setSurfaceTextureListenerForPerview(mSurfaceTextureListener);
    }

    private void enableFullScreen() {
        ActionBar actionBar = getSupportActionBar();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (actionBar != null) actionBar.hide();
    }

    private void start() {
        mRunning = true;
        mCkptChecker = new CkptChecker(mCfg, this::onCkptArrived);
        binding.curState.setText("running");
        binding.startStop.setText("stop");
        mStartTime = System.currentTimeMillis();

        Log.i(TAG, "current pose " + SegwayService.base().getOdometryPose(-1));

        mTickCountHandler = new Handler();
        mTickCountHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!mRunning) return;
                long cost = (System.currentTimeMillis() - mStartTime) / 1000;
                binding.curTimeCost.setText(CommHelper.SpanToTime(cost));
                if (mRunning) mTickCountHandler.postDelayed(this, 1000);
            }
        }, 1000);
        mCurRouteIdx = -1;
        getNextRoute();
    }

    private void stop() {
        mRunning = false;
        binding.curState.setText("finished");
        binding.startStop.setText("start");
        if (mCkptChecker != null) {
            mCkptChecker.stop();
            mCkptChecker = null;
        }
        if (mCurRoutePilot != null) {
            mCurRoutePilot.stop();
        }
        SegwayService.speak("we are arrived");
    }

    private String getCkptStr(int idx, final PointF ckpt) {
        return "C" + idx + ": (" + ckpt.x + ", " + ckpt.y + ")";
    }

    private void getNextRoute() {
        if (mCurRoutePilot != null) {
            mCurRoutePilot.stop();
            mCurRoutePilot = null;
        }
        ++mCurRouteIdx;
        if (mCurRouteIdx == mCfg.getRoutes().length) {
            return;
        }

        mCurRoute = mCfg.getRoutes()[mCurRouteIdx];
        String fromStr = mCurRoute.fromCkptIdx == 0 ? "start" : "C" + mCurRoute.fromCkptIdx;
        String toStr = "C" + mCurRoute.toCkptIdx;
        runOnUiThread(() -> {
            final String text = "R" + (mCurRouteIdx + 1) + "(" + fromStr + " ~ " + toStr + ")";
            binding.curRoute.setText(text);
            binding.checkpointFrom.setText(getCkptStr(mCurRoute.fromCkptIdx, mCurRoute.checkpoints[0]));
            binding.checkPointTo.setText(getCkptStr(mCurRoute.fromCkptIdx + 1, mCurRoute.checkpoints[1]));
            binding.curStrategy.setText(getRouteDetail(mCurRoute));
            handleCurRoute();
        });
    }

    private void handleCurRoute() {
        switch (mCurRoute.mode) {
            case "VLS":
                mCurRoutePilot = new VLSPilot(mCurRoute);
                break;
            case "VLS&YOLO":
                mAutoFitDrawableView.setPreviewSizeAndRotation(PREVIEW_WIDTH, PREVIEW_HEIGHT, Surface.ROTATION_0);
                mCurRoutePilot = new VlsYoloPilot((VlsYoloRoute) mCurRoute, mAutoFitDrawableView.getPreview());
                break;
            case "ConeTrack":
                mAutoFitDrawableView.setPreviewSizeAndRotation(PREVIEW_WIDTH, PREVIEW_HEIGHT, Surface.ROTATION_0);
                mCurRoutePilot = new ConeTrackPilot((ConeTrackRoute) mCurRoute, mAutoFitDrawableView.getPreview(), mPresenterChangeInterface);
                break;
            case "TcpRemote":
                mCurRoutePilot = new TcpRemotePilot((TcpRemoteRoute) mCurRoute);
                break;
            case "TcpPro":
                // TcpPro is the FPV version: joystick control + telemetry + video.
                // The phone app talks to this pilot over TCP and the pilot owns all
                // motion smoothing, watchdog, and video streaming behavior.
                mCurRoutePilot = new TcpProPilot((TcpProRoute) mCurRoute);
                break;
            case "DTS":
                mAutoFitDrawableView.setPreviewSizeAndRotation(PREVIEW_WIDTH, PREVIEW_HEIGHT, Surface.ROTATION_90);
                mCurRoutePilot = new DTSPilot((DTSRoute) mCurRoute, mPresenterChangeInterface, mAutoFitDrawableView.getPreview());
                break;
            case "YOLO":
                mAutoFitDrawableView.setPreviewSizeAndRotation(PREVIEW_WIDTH, PREVIEW_HEIGHT, Surface.ROTATION_0);
                mCurRoutePilot = new YoloPilot((YoloRoute) mCurRoute, mAutoFitDrawableView.getPreview(), mPresenterChangeInterface);
                break;
            case "YOLO&UNet":
                mAutoFitDrawableView.setPreviewSizeAndRotation(PREVIEW_WIDTH, PREVIEW_HEIGHT, Surface.ROTATION_0);
                mCurRoutePilot = new YoloAndUnetPilot((YoloAndUnetRoute) mCurRoute, mAutoFitDrawableView.getPreview(), mPresenterChangeInterface);
                break;
            case "UNetRoad":
                mAutoFitDrawableView.setPreviewSizeAndRotation(PREVIEW_WIDTH, PREVIEW_HEIGHT, Surface.ROTATION_0);
                mCurRoutePilot = new UNetRoadPilot((UNetRoadRoute) mCurRoute, mAutoFitDrawableView.getPreview(), mPresenterChangeInterface);
                break;
            default:
                throw new IllegalStateException("Unknown mode: " + mCurRoute.mode);
        }
        TcpSrv.getInstance().sendMsg("current route: " + mCurRoute.mode + ", from ckpt" + mCurRoute.fromCkptIdx + " to ckpt" + mCurRoute.toCkptIdx);
        mCurRoutePilot.setPilotListener((idx, isLast) -> {
            TcpSrv.getInstance().sendMsg("[pilot] arrived ckpt " + idx);
            if (isLast) {
                runOnUiThread(this::stop);
                return;
            }
            if (idx == mCurRoute.toCkptIdx) {
                getNextRoute();
            }
        });
        if (mCurRoute.checkDestByCkpt()) {
            mCkptChecker.resetCkpt(mCurRoute.fromCkptIdx + 1);
            mCkptChecker.resume();
        } else {
            mCkptChecker.pause();
        }
        mCurRoutePilot.start();
    }

    public native String stringFromJNI();

    private static final String TAG = "MainActivity";
    private AppConfig mCfg;
    private boolean mRunning = false;
    private Handler mTickCountHandler;
    private long mStartTime;
    private CkptChecker mCkptChecker;

    private Pilot mCurRoutePilot;
    private Route mCurRoute;
    private int mCurRouteIdx = -1;

    private void onCkptArrived(int idx, PointF ckpt, boolean isLast) {
        runOnUiThread(() -> {
            binding.checkpointFrom.setText(getCkptStr(idx, mCurRoute.checkpoints[idx - mCurRoute.fromCkptIdx]));
            if (mCurRoute.checkDestByCkpt() && !isLast) {
                binding.checkPointTo.setText(getCkptStr(idx + 1, mCfg.getCheckpoints()[idx + 1]));
            }
        });
        if (mCurRoute.checkDestByCkpt()) {
            TcpSrv.getInstance().sendMsg("[odometer] arrived ckpt " + idx);
        }
        if (mCurRoute.checkDestByCkpt() && isLast) {
            runOnUiThread(this::stop);
            return;
        }
        if (mCurRoute.checkDestByCkpt() && idx == mCurRoute.toCkptIdx) {
            getNextRoute();
        }
    }

    private void onBindDone() {
        TcpSrv.getInstance().sendMsg("ready to go");
        SegwayService.speak("ready to go!");
        SegwayService.head().setHeadJointYaw(0.0f);
        SegwayService.head().setWorldPitch(0.8f);
        runOnUiThread(() -> binding.startStop.setEnabled(true));
        VoiceControl.getInstance().init(mCfg.getVoiceControlParams(), new VoiceControl.VoiceCmdListener() {
            @Override
            public void onRecognizeStart() {
                if (mCurRoutePilot != null) mCurRoutePilot.pause();
            }

            @Override
            public void onRecognizeDone() {
                if (mCurRoutePilot != null) mCurRoutePilot.resume();
            }

            @Override
            public void onNextRoute() {
                if (mCurRoutePilot != null) mCurRoutePilot.stop();
                if (mCurRouteIdx == mCfg.getRoutes().length - 1) {
                    runOnUiThread(MainActivity.this::stop);
                    return;
                }
                getNextRoute();
            }
        });
    }
}
