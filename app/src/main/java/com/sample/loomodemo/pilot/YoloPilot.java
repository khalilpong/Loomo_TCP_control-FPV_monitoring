package com.sample.loomodemo.pilot;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import com.sample.loomodemo.Pilot;
import com.sample.loomodemo.PilotListener;
import com.sample.loomodemo.helper.PresenterChangeInterface;
import com.sample.loomodemo.helper.SegwayService;
import com.sample.loomodemo.helper.TcpSrv;
import com.sample.loomodemo.helper.YoloV3Tiny;
import com.sample.loomodemo.route.YoloRoute;
import com.segway.robot.sdk.locomotion.head.Head;
import com.segway.robot.sdk.locomotion.sbv.Base;
import com.segway.robot.sdk.vision.Vision;
import com.segway.robot.sdk.vision.frame.Frame;
import com.segway.robot.sdk.vision.stream.StreamInfo;
import com.segway.robot.sdk.vision.stream.StreamType;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Rect2d;

public class YoloPilot extends Pilot {
    private static final String TAG = "YoloPilot";
    private final YoloRoute mRoute;
    private PilotListener mListener;

    boolean mRun = true;
    // TODO: try fisheye
    private final int mStreamType = StreamType.COLOR;
    private final int mFrameWidth;
    private final int mFrameHeight;
    private YoloV3Tiny mDetector;
    private PresenterChangeInterface mPresenterChangeInterface;

    private boolean mIsDetecting = false;
    private long mLastDetectTime = 0;
    private final Surface mSurface;

    private boolean mPause = false;
    private YoloV3Tiny.Result mLastResult;

    public YoloPilot(YoloRoute route, TextureView view, PresenterChangeInterface presenterChangeInterface) {
        super(route);
        mRoute = route;
        mPresenterChangeInterface = presenterChangeInterface;
        mSurface = new Surface(view.getSurfaceTexture());
        StreamInfo info = Vision.getInstance().getStreamInfo(mStreamType);
        mFrameWidth = info.getWidth();
        mFrameHeight = info.getHeight();
        Log.i(TAG, "YOLO stream: " + mFrameWidth + " x " + mFrameHeight);
    }

    @Override
    public void setPilotListener(PilotListener listener) {
        mListener = listener;
    }

    @Override
    public void start() {
        mRun = true;
        mPause = false;
        Base.getInstance().setControlMode(Base.CONTROL_MODE_RAW);
        // 头部回正
        Head.getInstance().setHeadJointYaw(0.f);
        Head.getInstance().setWorldPitch(mRoute.headPitch);
        //bySC
        SegwayService.head().setMode(Head.MODE_EMOJI);// 头部与底座方向绑定

        mDetector = YoloV3Tiny.create(mRoute);
        if (mDetector == null) {
            Log.e(TAG, "start: YoloV3Tiny 模型加载失败！");
            return;
        }
        Vision.getInstance().startListenFrame(mStreamType, this::onNewFrame);
    }

    @Override
    public void stop() {
        mRun = false;
        Vision.getInstance().stopListenFrame(mStreamType);
        // 一直等待直到最后一帧图像分析完毕
        while (mIsDetecting) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        mDetector = null;
        mSurface.release();
        Log.i(TAG, "stopped");
    }

    @Override
    public void pause() {
        mPause = true;
    }

    @Override
    public void resume() {
        mPause = false;
    }

    private void onNewFrame(int streamType, Frame frame) {
        if (streamType != mStreamType || !mRun || mPause) return;
        Mat image = new Mat(mFrameHeight, mFrameWidth, CvType.CV_8UC4, frame.getByteBuffer());

        Paint paint = new Paint();
        Bitmap bitmap = Bitmap.createBitmap(mFrameWidth, mFrameHeight, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(image, bitmap, true);
        final Canvas canvas = mSurface.lockCanvas(null);
        canvas.drawBitmap(bitmap, 0.f, 0.f ,paint);
        mSurface.unlockCanvasAndPost(canvas);
        if (mLastResult != null) mPresenterChangeInterface.drawRect(mLastResult.box);

        //bySC
        //if (mLastResult != null){
        //    mPresenterChangeInterface.drawRect(mLastResult.box);
            //Log.e("XXXXXXXX",  "PPPPPP");
        //}

        long curTick = System.currentTimeMillis();
        if (!mIsDetecting && curTick - mLastDetectTime >= mRoute.detectInterval) {
            mIsDetecting = true;
            mLastDetectTime = curTick;
            new Thread(
                    () -> {
                        process(image);
                        mIsDetecting = false;
                    }
            ).start();
        }
    }

    private void process(Mat image) {
        final YoloV3Tiny.Result result = mDetector.Detect(image);
        mLastResult = result;
        if (result == null) {
            return;
        }
        TcpSrv.getInstance().sendMsg("YOLO found object " + result.clsIdx + ", score " + result.score + ", box " + result.box);
        // 到达终点了
        if (result.clsIdx == mRoute.targetClassIdx) {
            TcpSrv.getInstance().sendMsg("YOLO report route destination arrived");
            mRun = false;
            if (this.mListener != null) {
                boolean isLast = mRoute.toCkptIdx == mRoute.finalCkptIdx;
                // 在新线程中调用，避免死锁
                new Thread(
                        () -> mListener.onCkptArrived(mRoute.toCkptIdx, isLast)
                ).start();
            }
            return;
        }

        // 没到终点，根据检测结果引导前进
        updateTarget(image, result.box);
    }

    private void updateTarget(Mat image, Rect2d box) {
        Rect2d obj = new Rect2d((double) box.x / image.cols(), (double) box.y / image.rows(),
                (double) box.width / image.cols(), (double) box.height / image.rows());
        updateBaseLinear(obj);
    }

    // 调整底座前进后退
    private void updateBaseLinear(Rect2d obj) {
        float linearVelocity, angularVelocity;

        // 线速度
        if (obj.width < mRoute.objectMinW) { // 太远
            linearVelocity = mRoute.maxLinearVelocity;
        } else if (obj.width <= mRoute.objectMaxW) { // 有效前进区间
            //linearVelocity = (float) (mRoute.maxLinearVelocity * (obj.width - mRoute.objectMinW) / (mRoute.objectMaxW - mRoute.objectMinW));
            //bySC
            linearVelocity = (float) (mRoute.maxLinearVelocity * (1.f-(obj.width - mRoute.objectMinW) / (mRoute.objectMaxW - mRoute.objectMinW)));
        } else if (obj.width <= 1.1f * mRoute.objectMaxW) { // 停止区间
            linearVelocity = 0.f;
        } else { // 固定速度后退
            linearVelocity = -1.f * mRoute.backLinearVelocity;
        }

        // 角速度
        // 当目标中心 X 值在此范围内时将直接前进，不转向，超过此范围进行转向
        //final float forwardMinX = 0.5f - mRoute.objectMinW / 2;
        //final float forwardMaxX = 0.5f + mRoute.objectMinW / 2;
        //bySC no turning around when target is very big
        final float forwardMinX = (float) (0.5f - obj.width / 2.f);
        final float forwardMaxX = (float) (0.5f + obj.width / 2.f);

        float centerX = (float) (obj.x + obj.width / 2.f);
        if (centerX >= forwardMinX && centerX <= forwardMaxX) {
            angularVelocity = 0.f;
        } else {
            // 左转为正，右转为负
            //angularVelocity = mRoute.maxAngularVelocity * (0.5f - centerX) / 0.5f;
            //bySC
            angularVelocity = mRoute.maxAngularVelocity * (0.5f - centerX); //slow down
        }

        Base.getInstance().setLinearVelocity(linearVelocity);
        Base.getInstance().setAngularVelocity(angularVelocity);
    }
}
