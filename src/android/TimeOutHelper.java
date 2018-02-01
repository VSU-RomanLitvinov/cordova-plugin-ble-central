package com.megster.cordova.ble.central;

import android.os.Handler;
import android.os.Looper;

public class TimeOutHelper {

    private static Handler handler = new android.os.Handler(Looper.getMainLooper());

    public static void setTimeout(Runnable runnable, int delay){
      handler.postDelayed(runnable, delay);
    }

    public static void clearTimeOut(Runnable runnable) {
      handler.removeCallbacks(runnable);
    }
}
