package com.dada.memfill;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;

public class MainActivity extends Activity {
    private static final String TAG = "MemFillTest";

    static {
        System.loadLibrary("memfill");
    }

    private static native int[] nativeAllocStep(int stepMb, int stepIdx);
    private static native void nativeFreeAll();

    private TextView statusView;
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 96, 48, 48);

        statusView = new TextView(this);
        statusView.setTextSize(18f);
        statusView.setText("MemFillTest ready");
        layout.addView(statusView);

        Button btn = new Button(this);
        btn.setText("Start 64MB Ramp");
        btn.setOnClickListener(v -> startRamp(64, 150, 11000, 0));
        layout.addView(btn);
        setContentView(layout);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("autostart", false)) {
            int stepMb = intent.getIntExtra("step_mb", 64);
            int delayMs = intent.getIntExtra("delay_ms", 150);
            int targetMb = intent.getIntExtra("target_mb", 11000);
            int stopAvailMb = intent.getIntExtra("stop_avail_mb", 60);
            startRamp(stepMb, delayMs, targetMb, stopAvailMb);
        }
    }

    private void startRamp(int stepMb, int delayMs, int targetMb, int stopAvailMb) {
        if (running) return;
        running = true;
        new Thread(() -> {
            Log.i(TAG, "ramp_start pid=" + android.os.Process.myPid()
                    + " step_mb=" + stepMb + " delay_ms=" + delayMs
                    + " target_mb=" + targetMb + " stop_avail_mb=" + stopAvailMb);
            int step = 0;
            while (running) {
                step++;
                final int curStep = step;
                int[] res = nativeAllocStep(stepMb, curStep);
                if (res == null) {
                    Log.w(TAG, "ramp_stop_malloc_null step=" + curStep);
                    runOnUiThread(() -> statusView.setText("malloc failed at step " + curStep));
                    break;
                }
                final int allocMb = res[0];
                final int availMb = res[1];
                final int freeMb = res[2];
                final int swapMb = res[3];
                runOnUiThread(() -> statusView.setText(
                        "Allocated: " + allocMb + " MB\nMemAvailable: " + availMb
                                + " MB\nMemFree: " + freeMb + " MB\nSwapFree: " + swapMb + " MB"));
                if (allocMb >= targetMb || (stopAvailMb > 0 && availMb > 0 && availMb <= stopAvailMb)) {
                    Log.i(TAG, "ramp_reached_target alloc_mb=" + allocMb + " mem_avail_mb=" + availMb);
                    break;
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            running = false;
        }, "MemFillWorker").start();
    }

    @Override
    protected void onDestroy() {
        running = false;
        nativeFreeAll();
        super.onDestroy();
    }
}
