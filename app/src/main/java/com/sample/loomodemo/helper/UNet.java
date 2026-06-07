package com.sample.loomodemo.helper;

import static org.opencv.imgproc.Imgproc.COLOR_RGBA2RGB;
import static org.opencv.imgproc.Imgproc.cvtColor;

import android.graphics.PointF;
import android.os.Environment;
import android.util.Log;

import com.sample.loomodemo.route.YoloAndUnetRoute;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class UNet {
    private Net mNet;
    private YoloAndUnetRoute mRoute;

    public static class Result {
        public int groundPixelCount;
        public PointF groundCenter;
        public PointF nearCenter;
        public PointF farCenter;
        public float nearWidth;
        public float farWidth;
    }

    private UNet() {}

    public static UNet create(YoloAndUnetRoute route) {
        File externalStorageDirectory = Environment.getExternalStorageDirectory();
        String modelFilepath = externalStorageDirectory + "/model/unet/" + route.unetModelFilename;
        UNet inst = new UNet();
        inst.mRoute = route;
        try {
            inst.mNet = Dnn.readNetFromTensorflow(modelFilepath);
        } catch (Exception ex) {
            Log.e("UNet", "model load failed");
            Log.e("UNet", modelFilepath);
            return null;
        }
        inst.mNet.setPreferableTarget(Dnn.DNN_TARGET_OPENCL);
        return inst;
    }

    public Result Detect(Mat image) {
        Mat rgb = new Mat();
        cvtColor(image, rgb, COLOR_RGBA2RGB);
        Mat blob = Dnn.blobFromImage(
                rgb,
                1 / 255.f,
                new Size(mRoute.unetModelWidth, mRoute.unetModelHeight),
                Scalar.all(0.0),
                false
        );
        mNet.setInput(blob);
        List<Mat> outputBlobs = new ArrayList<>();
        mNet.forward(outputBlobs);
        if (outputBlobs.isEmpty()) {
            return null;
        }

        final int outW = mRoute.unetModelWidth / 2;
        final int outH = mRoute.unetModelHeight / 2;
        final int classes = 9;

        Mat outByRow = outputBlobs.get(0).reshape(1, classes);
        Mat outByCol = outByRow.t();

        long avgX = 0;
        long avgY = 0;
        int groundCnt = 0;
        long nearAvgX = 0;
        long nearAvgY = 0;
        int nearCnt = 0;
        long farAvgX = 0;
        long farAvgY = 0;
        int farCnt = 0;
        float nearWidthSum = 0.f;
        int nearWidthRows = 0;
        float farWidthSum = 0.f;
        int farWidthRows = 0;

        int startRow = outH / 2;
        int nearStartY = Math.max(startRow, (int)(outH * 0.75f));
        int farStartY = startRow;
        int farEndY = Math.max(farStartY + 1, nearStartY);

        for (int y = startRow; y < outH; ++y) {
            int left = -1;
            int right = -1;
            for (int x = 0; x < outW; ++x) {
                int idx = y * outW + x;
                Core.MinMaxLocResult mm = Core.minMaxLoc(outByCol.row(idx));
                int clsIdx = (int)mm.maxLoc.x;
                if (clsIdx != mRoute.unetRoadClassIdx) continue;
                ++groundCnt;
                avgX += x;
                avgY += y;
                if (left == -1) left = x;
                right = x;
            }

            if (left == -1 || right == -1) continue;

            float centerX = (left + right) / 2.f;
            float width = (right - left + 1.f) / outW;
            if (y >= nearStartY) {
                nearAvgX += centerX;
                nearAvgY += y;
                ++nearCnt;
                nearWidthSum += width;
                ++nearWidthRows;
            } else if (y >= farStartY && y < farEndY) {
                farAvgX += centerX;
                farAvgY += y;
                ++farCnt;
                farWidthSum += width;
                ++farWidthRows;
            }
        }

        if (groundCnt == 0 || (float)groundCnt / (outW * outH) < mRoute.unetRoadPixelMinCnt) {
            return null;
        }

        Result ret = new Result();
        ret.groundPixelCount = groundCnt;
        ret.groundCenter = new PointF(((float)avgX / groundCnt) / outW, ((float)avgY / groundCnt) / outH);
        ret.nearCenter = nearCnt > 0
                ? new PointF(((float)nearAvgX / nearCnt) / outW, ((float)nearAvgY / nearCnt) / outH)
                : ret.groundCenter;
        ret.farCenter = farCnt > 0
                ? new PointF(((float)farAvgX / farCnt) / outW, ((float)farAvgY / farCnt) / outH)
                : ret.groundCenter;
        ret.nearWidth = nearWidthRows > 0 ? nearWidthSum / nearWidthRows : 0.f;
        ret.farWidth = farWidthRows > 0 ? farWidthSum / farWidthRows : 0.f;

        Log.i("UNet", "groundCnt " + groundCnt + ", near=" + ret.nearCenter + ", far=" + ret.farCenter);
        return ret;
    }
}
