package com.sample.loomodemo.helper;

import android.graphics.Point;
import android.graphics.PointF;
import android.support.annotation.NonNull;

import java.util.ArrayList;

public class AppConfigHelper {
    // 解析用英文半角逗号分割的 2 个整数构成的 Point
    static @NonNull
    public Point parsePoint2d(String val) {
        String[] vars = val.split(",");
        int x = Integer.parseInt(vars[0].trim());
        int y = Integer.parseInt(vars[1].trim());
        return new Point(x, y);
    }

    // 解析用英文半角逗号分割的 2 个浮点数构成的 Point
    static @NonNull
    public PointF parsePoint2f(String val) {
        String[] vars = val.split(",");
        float x = Float.parseFloat(vars[0].trim());
        float y = Float.parseFloat(vars[1].trim());
        return new PointF(x, y);
    }

    static @NonNull
    public int[] parseIntArray(String val) {
        String[] vars = val.split(",");
        ArrayList<Integer> values = new ArrayList<>();
        for (String var : vars) {
            String trimmed = var.trim();
            if (trimmed.isEmpty()) continue;
            values.add(Integer.parseInt(trimmed));
        }
        int[] ret = new int[values.size()];
        for (int i = 0; i < values.size(); ++i) {
            ret[i] = values.get(i);
        }
        return ret;
    }
}
