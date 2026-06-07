package com.sample.loomodemo.helper;

public class CommHelper {
    // 将整型的秒数转成时分秒格式
    public static String SpanToTime(long span) {
        if (span <= 0) {
            return "00:00:00";
        }
        long sec = span % 60;
        long min = (span / 60) % 60;
        long hour = span / 3600;
        return UnitFormat(hour) + ":" + UnitFormat(min) + ":" + UnitFormat(sec);
    }

    // 将 0 到 59 的整型数字按需加上前导 0
    private static String UnitFormat(long n) {
        return (n < 10 ? "0" : "") + Long.toString(n);
    }
}
