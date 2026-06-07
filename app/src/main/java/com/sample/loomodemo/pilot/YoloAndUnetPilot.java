package com.sample.loomodemo.pilot;

import android.graphics.Color;
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
import com.sample.loomodemo.route.YoloAndUnetRoute;
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
import org.opencv.core.Rect;
import org.opencv.core.Rect2d;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.opencv.core.Point;

public class YoloAndUnetPilot extends Pilot {
    private static final String TAG = "YoloAndUnetPilot";
    private final YoloAndUnetRoute mRoute;
    private PilotListener mListener;

    boolean mRun = true;
    // TODO: try fisheye
    private final int mStreamType = StreamType.COLOR;
    private final int mFrameWidth;
    private final int mFrameHeight;
    private YoloV3Tiny mYolo;
    private UNet mUNet;
    private final PresenterChangeInterface mPresenterChangeInterface;

    private boolean mIsDetecting = false;
    private long mLastDetectTime = 0;
    private final Surface mSurface;

    private boolean mPause = false;
//    private YoloV3Tiny.Result mLastResult;

    class Result {
        YoloV3Tiny.Result yoloResult;
        Point unetCenter;
    }
    Result mLastResult = new Result();

    public YoloAndUnetPilot(YoloAndUnetRoute route, TextureView view, PresenterChangeInterface presenterChangeInterface) {
        super(route);
        mRoute = route;
        mPresenterChangeInterface = presenterChangeInterface;
        mSurface = new Surface(view.getSurfaceTexture());
        StreamInfo info = Vision.getInstance().getStreamInfo(mStreamType);
        mFrameWidth = info.getWidth();
        mFrameHeight = info.getHeight();
        Log.i(TAG, "YoloAndUnetPilot stream: " + mFrameWidth + " x " + mFrameHeight);
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
        //SegwayService.head().setHeadJointYaw(1.f); //equivalent to the above line
        Head.getInstance().setWorldPitch(mRoute.headPitch);
        //bySC
        //SegwayService.head().setWorldYaw();
        //Angle aHeadPose = Head.getInstance().getHeadJointYaw();
        SegwayService.head().setMode(Head.MODE_EMOJI); // 头部与底座方向绑定
        //Log.e(TAG,"TTTTTTGGGGGG");

        mYolo = YoloV3Tiny.create(mRoute);
        if (mYolo == null) {
            Log.e(TAG, "start: YoloV3Tiny 模型加载失败！");
            return;
        }
        mUNet = UNet.create(mRoute);
        if (mUNet == null) {
            Log.e(TAG, "start: UNet 模型加载失败！");
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
        mYolo = null;
        mUNet = null;
        mSurface.release();
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

        //bySC for unet debug
        //final int[] colors = new int[] {
        //        Color.rgb( 54,  67, 244),
        //        Color.rgb( 99,  30, 233)};

        Scalar color = new Scalar(255,255,0);
        /*
        if (mLastResult != null) {
            if(mLastResult.box.width < 0 && mLastResult.box.height < 0 && mLastResult.box.x > 0 && mLastResult.box.y > 0)
            {
                Point p1 = new Point();
                p1.x = mLastResult.box.x;
                p1.y = mLastResult.box.y;
                Imgproc.circle(image,p1,5,color,2);
            }
        }
        */
        //--------------------------------------------

        Paint paint = new Paint();
        Bitmap bitmap = Bitmap.createBitmap(mFrameWidth, mFrameHeight, Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(image, bitmap, true);
        final Canvas canvas = mSurface.lockCanvas(null);
        canvas.drawBitmap(bitmap, 0.f, 0.f ,paint);
        mSurface.unlockCanvasAndPost(canvas);
        if (mLastResult != null){
            //bySC
            if(mLastResult.yoloResult != null) {
                mPresenterChangeInterface.drawRect(mLastResult.yoloResult.box);
                //Log.e("XXXXXXXX",  "PPPPPP");
            }
            if(mLastResult.unetCenter != null){
                Point p1 = mLastResult.unetCenter;
                Rect2d r2d2 = new Rect2d();
                r2d2.x = p1.x;
                r2d2.y = p1.y;
                r2d2.height = 3;
                r2d2.width = 3;
                mPresenterChangeInterface.drawRect(r2d2);
                /*
                Log.e("XXXXXXXX", p1 + "");
                Log.e("XXXXXXXX", image + "");
                Log.e("XXXXXXXX", color + "");
                Imgproc.circle(image,p1,5,color,2);
                Log.e("XXXXXXXXX", "hhhhh");*/
            }
            //---------------------
        }

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

    private class YoloRunnable implements Runnable {
        private final Mat mImage;
        private YoloV3Tiny.Result mResult;
        YoloRunnable(Mat image) {
            super();
            mImage = image;
        }

        public YoloV3Tiny.Result getResult() {
            return mResult;
        }

        @Override
        public void run() {
            mResult = mYolo.Detect(mImage);
        }
    }
    private class UNetRunnable implements Runnable {
        private final Mat mImage;
        private UNet.Result mResult;
        UNetRunnable(Mat image) {
            super();
            mImage = image;
        }

        public UNet.Result getResult() {
            return mResult;
        }

        @Override
        public void run() {
            mResult = mUNet.Detect(mImage);
        }
    }

   private void process(Mat image) {
        // Android 7 才开始支持 CompletableFuture.supplyAsync，而我们用的是 Android 5
//        CompletableFuture<YoloV3Tiny.Result> yoloTask = CompletableFuture.supplyAsync(() -> { return mYolo.Detect(image); });
        YoloRunnable yoloRunnable = new YoloRunnable(image);
        UNetRunnable unetRunnable = new UNetRunnable(image);

        Thread yoloThread = new Thread(yoloRunnable);
        Thread unetThread = new Thread(unetRunnable);
        yoloThread.start();
        unetThread.start();

        // 等待两个线程完成
        try {
            yoloThread.join();
            unetThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return;
        }

        YoloV3Tiny.Result yoloResult = yoloRunnable.getResult();
        UNet.Result unetResult = unetRunnable.getResult();
        if (yoloResult == null && unetResult == null) {
            // YOLO 和 UNet 均没有检测结果
            return;
        }
        //bySC
       //boolean tempo = SegwayService.head().isBind();
       //Log.e(TAG, "SSCC: " + tempo);
       //SegwayService.head().setHeadJointYaw(0.0f);  //place the head forward

       //Head.getInstance().setWorldYaw(0.f);


        if (yoloResult != null) {
            // YOLO 有结果，优先使用
            mLastResult.yoloResult = yoloResult;
            mLastResult.unetCenter = null;
            updateByYoloResult(image, yoloResult);
        } else {
            // 根据 UNet 路面分割结果更新
            //bySC
            mLastResult.yoloResult = null;
            mLastResult.unetCenter = new Point();
            mLastResult.unetCenter.x = (float)image.width()*unetResult.groundCenter.x;
            mLastResult.unetCenter.y = (float)image.height()*unetResult.groundCenter.y;

            updateByUNetResult(unetResult);
        }
        /*
       if (unetResult != null) {
           // UNET 有结果，优先使用
           mLastResult.yoloResult = null;
           mLastResult.unetCenter = new Point();
           mLastResult.unetCenter.x = (float)image.width()*unetResult.groundCenter.x;
           mLastResult.unetCenter.y = (float)image.height()*unetResult.groundCenter.y;

           updateByUNetResult(unetResult);
       } else {
           mLastResult.yoloResult = yoloResult;
           mLastResult.unetCenter = null;
           updateByYoloResult(image, yoloResult);
       }*/
    }

    // 根据 UNet 检测结果更新
    private void updateByUNetResult(UNet.Result result) {
        //bySC
        Pose2D curPose = SegwayService.base().getOdometryPose(-1);
        TcpSrv.getInstance().sendMsg("[odometer] current pose: (" + curPose.getX() + ", " + curPose.getY() + ")");
        TcpSrv.getInstance().sendMsg("UNet found ground, pixel count " + result.groundPixelCount + ", center " + result.groundCenter);
        //bySC
        //updateBaseByUNet(result.groundCenter);
        updateBaseByUNet(result);
    }

    // 根据 UNet 调整底座前进转向
   // private void updateBaseByUNet(PointF groundCenter) {
    private void updateBaseByUNet(UNet.Result result) {
        float linearVelocity, angularVelocity;
        PointF groundCenter = result.groundCenter;
        // 线速度
        // 地面中心离底部越远（说明前方可前进的路程更远），前进速度越快
        // 此处认为地面总是从底部开始向前延伸的——当然，这个前提并不一定成立
        linearVelocity = mRoute.maxLinearVelocity * (1.f - groundCenter.y) / 0.5f;
        if (linearVelocity > mRoute.maxLinearVelocity) {
            linearVelocity = mRoute.maxLinearVelocity;
        }

        // 角速度
        // 当目标中心 X 值在此范围内时将直接前进，不转向，超过此范围进行转向
        final float forwardMinX = 0.5f - mRoute.objectMinW / 2;
        final float forwardMaxX = 0.5f + mRoute.objectMinW / 2;
        float centerX = groundCenter.x;
        //if (centerX >= forwardMinX && centerX <= forwardMaxX) {
        //    angularVelocity = 0.f;
        //} else {
            // 左转为正，右转为负
            //bySC
            angularVelocity = mRoute.maxAngularVelocity * (0.5f - centerX) / 0.3f;
            //angularVelocity = mRoute.maxAngularVelocity * (0.5f - centerX) * (0.5f - centerX) / 0.25f;
        //}

        //Base.getInstance().setLinearVelocity(linearVelocity);
        //Base.getInstance().setAngularVelocity(angularVelocity);

        //bySC test
        float fRatio = result.groundPixelCount / mRoute.unetModelWidth / mRoute.unetModelHeight * 2 * 2;
        fRatio = fRatio * 2.6f;
        //if(fRatio > 1.0f)
        //    fRatio = 1.0f;
        int nLoop = (int)(fRatio * 10.0f);
        if(nLoop < 3){
            Base.getInstance().setLinearVelocity(linearVelocity);
            Base.getInstance().setAngularVelocity(angularVelocity);
        }
        else if(nLoop < 8){

            int nSep = nLoop / 2;
            for(int i=0; i < nSep;i++){      //accelerate
                Base.getInstance().setLinearVelocity(linearVelocity/(nSep-i));
                Base.getInstance().setAngularVelocity(angularVelocity/(nSep-i));
                try {
                    Thread.sleep(500); // 给 100ms 时间移动
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            for(int i=nSep; i < nLoop;i++){      //brake
                Base.getInstance().setLinearVelocity(linearVelocity/(i-nSep+1));
                Base.getInstance().setAngularVelocity(angularVelocity/(i-nSep+1));
                try {
                    Thread.sleep(500); // 给 100ms 时间移动
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        else{
            //int nSep = nLoop / 2;
            for(int i=0; i < 3;i++){      //accelerate
                Base.getInstance().setLinearVelocity(linearVelocity/(3-i));
                Base.getInstance().setAngularVelocity(angularVelocity/(3-i));
                try {
                    Thread.sleep(500); // 给 100ms 时间移动
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            for(int i=3; i < 7;i++){      //maintain
                Base.getInstance().setLinearVelocity(linearVelocity);
                Base.getInstance().setAngularVelocity(angularVelocity);
                try {
                    Thread.sleep(500); // 给 100ms 时间移动
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            for(int i=7; i < nLoop;i++){      //brake
                Base.getInstance().setLinearVelocity(linearVelocity/(i-7+1));
                Base.getInstance().setAngularVelocity(angularVelocity/(i-7+1));
                try {
                    Thread.sleep(500); // 给 100ms 时间移动
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        /*
        for(int i=0; i < 3;i++){
            Base.getInstance().setLinearVelocity(linearVelocity/(3-i));
            Base.getInstance().setAngularVelocity(angularVelocity/(3-i));
            try {
                Thread.sleep(500); // 给 100ms 时间移动
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        for(int i=0; i < 5;i++){
            Base.getInstance().setLinearVelocity(linearVelocity/(i+1));
            Base.getInstance().setAngularVelocity(angularVelocity/(i+1));
            try {
                Thread.sleep(500); // 给 100ms 时间移动
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        */
        //Log.e(TAG, "SSCC");
        /////////
    }

    // 根据 YOLO 检测结果更新
    private void updateByYoloResult(Mat image, YoloV3Tiny.Result result) {
        TcpSrv.getInstance().sendMsg("YOLO found object " + result.clsIdx + ", score " + result.score + ", box " + result.box);
        // 到达终点了
        if (result.clsIdx == mRoute.targetClassIdx) {
            TcpSrv.getInstance().sendMsg("YOLO report route destination arrived");
            mRun = false;
            if (this.mListener != null) {
                boolean isLast = (mRoute.toCkptIdx == mRoute.finalCkptIdx);
                // 在新线程中调用，避免死锁
                new Thread(
                        () -> mListener.onCkptArrived(mRoute.toCkptIdx, isLast)
                ).start();
            }
            return;
        }

        // 没到终点，根据检测结果引导前进
        updateBaseByYolo(image, result.box);
    }

    // 根据 YOLO 调整底座前进后退转向
    private void updateBaseByYolo(Mat image, Rect2d box) {
        Rect2d obj = new Rect2d((double) box.x / image.cols(), (double) box.y / image.rows(),
                (double) box.width / image.cols(), (double) box.height / image.rows());
        float linearVelocity, angularVelocity;

        // 线速度
        if (obj.width < mRoute.objectMinW) { // 太远
            linearVelocity = mRoute.maxLinearVelocity;
        } else if (obj.width <= mRoute.objectMaxW) { // 有效前进区间
        //    linearVelocity = (float) (mRoute.maxLinearVelocity * (obj.width - mRoute.objectMinW) / (mRoute.objectMaxW - mRoute.objectMinW));
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
            angularVelocity = mRoute.maxAngularVelocity * (0.5f - centerX) / 0.5f;
        }

        Base.getInstance().setLinearVelocity(linearVelocity);
        Base.getInstance().setAngularVelocity(angularVelocity);
    }
}
