package com.sample.loomodemo.helper;

import static org.opencv.imgproc.Imgproc.COLOR_RGBA2RGB;
import static org.opencv.imgproc.Imgproc.cvtColor;

import android.os.Environment;
import android.util.Log;

import com.sample.loomodemo.route.YoloRoute;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfInt;
import org.opencv.core.MatOfRect2d;
import org.opencv.core.Point;
import org.opencv.core.Rect2d;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.utils.Converters;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class YoloV3Tiny {
    private Net mNet;
    private List<String> mOutputLayers;
    private YoloRoute mRoute;

    public static class Result {
        public Rect2d box;
        public int clsIdx;
        public float score;
    }

    private YoloV3Tiny() {}

    public static YoloV3Tiny create(YoloRoute route) {
        File externalStorageDirectory = Environment.getExternalStorageDirectory();
        String cfgFilepath = externalStorageDirectory + "/model/yolov3tiny/" + route.modelCfgFilename;
        String weightsFilepath = externalStorageDirectory + "/model/yolov3tiny/" + route.modelWeightsFilename;
        YoloV3Tiny inst = new YoloV3Tiny();
        inst.mRoute = route;
        try {
            inst.mNet = Dnn.readNetFromDarknet(cfgFilepath, weightsFilepath);
        } catch (Exception ex) {
            Log.e("YoloV3Tiny", "model load failed");
            Log.e("YoloV3Tiny", cfgFilepath);
            Log.e("YoloV3Tiny", weightsFilepath);
            return null;
        }
        inst.mNet.setPreferableTarget(Dnn.DNN_TARGET_OPENCL);
        inst.mOutputLayers = inst.mNet.getUnconnectedOutLayersNames();
        return inst;
    }

    public Result Detect(Mat image) {
        List<Result> detections = detectByClasses(image, mRoute.confidence, mRoute.objectClassIdx, mRoute.targetClassIdx);
        if (detections.isEmpty()) {
            return null;
        }

        List<Result> pilotObjects = new ArrayList<>();
        List<Result> targetObjects = new ArrayList<>();
        for (Result cur : detections) {
            if (cur.clsIdx == mRoute.objectClassIdx) {
                pilotObjects.add(cur);
            } else if (cur.clsIdx == mRoute.targetClassIdx) {
                targetObjects.add(cur);
            }
        }

        List<Result> preferred = targetObjects.isEmpty() ? pilotObjects : targetObjects;
        Result ret = null;
        for (Result cur : preferred) {
            if (ret == null || cur.box.width > ret.box.width) {
                ret = cur;
            }
        }
        return ret;
    }

    public List<Result> detectSelected(Mat image, float confidenceThreshold, int... classIds) {
        return detectByClasses(image, confidenceThreshold, classIds);
    }

    private List<Result> detectByClasses(Mat image, float confidenceThreshold, int... classIds) {
        Mat rgb = new Mat();
        cvtColor(image, rgb, COLOR_RGBA2RGB);
        Mat blob = Dnn.blobFromImage(rgb, 1 / 255.f, new Size(), Scalar.all(0.0), false, false);
        mNet.setInput(blob);

        List<Mat> outputBlobs = new ArrayList<>();
        mNet.forward(outputBlobs, mOutputLayers);
        List<Result> results = new ArrayList<>();
        if (outputBlobs.isEmpty()) {
            return results;
        }

        List<Integer> clsIds = new ArrayList<>();
        List<Float> confs = new ArrayList<>();
        List<Rect2d> rects = new ArrayList<>();
        for (Mat out : outputBlobs) {
            for (int r = 0; r < out.rows(); ++r) {
                Mat row = out.row(r);
                Mat scores = row.colRange(5, out.cols());
                Core.MinMaxLocResult mm = Core.minMaxLoc(scores);
                float confidence = (float) mm.maxVal;
                if (confidence < confidenceThreshold) {
                    continue;
                }
                Point classIdPoint = mm.maxLoc;
                int centerX = (int) (row.get(0, 0)[0] * image.cols());
                int centerY = (int) (row.get(0, 1)[0] * image.rows());
                int width = (int) (row.get(0, 2)[0] * image.cols());
                int height = (int) (row.get(0, 3)[0] * image.rows());

                clsIds.add((int) classIdPoint.x);
                confs.add(confidence);
                rects.add(new Rect2d(centerX - width / 2.0, centerY - height / 2.0, width, height));
            }
        }

        if (confs.isEmpty()) {
            return results;
        }

        final float nmsThresh = 0.5f;
        MatOfFloat confidences = new MatOfFloat(Converters.vector_float_to_Mat(confs));
        Rect2d[] boxesArray = rects.toArray(new Rect2d[0]);
        MatOfRect2d boxes = new MatOfRect2d(boxesArray);
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxes, confidences, confidenceThreshold, nmsThresh, indices);
        if (indices.empty()) {
            return results;
        }

        Set<Integer> selectedClasses = new HashSet<>();
        for (int cls : classIds) {
            selectedClasses.add(cls);
        }

        int[] ind = indices.toArray();
        for (int idx : ind) {
            int clsIdx = clsIds.get(idx);
            if (!selectedClasses.contains(clsIdx)) {
                TcpSrv.getInstance().sendMsg("YOLO got object type: " + clsIdx + ", " + confs.get(idx) + ", but will ignore it");
                continue;
            }
            Result cur = new Result();
            cur.box = boxesArray[idx];
            cur.clsIdx = clsIdx;
            cur.score = confs.get(idx);
            results.add(cur);
        }

        return results;
    }
}
