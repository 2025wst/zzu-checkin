package com.zzu.checkin;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用 OCR 在屏幕上找「签到」按钮的位置。
 *
 * 为什么需要它：
 * 学习通的打卡页是 H5，且把无障碍无障碍关掉了 —— 我们读不到按钮的文字，
 * 只能"猜"坐标。不同平板、不同版本，按钮位置都不一样，猜就会点空。
 *
 * 做法：无障碍服务截一张图 → ML Kit 中文识别 → 在识别结果里找「签到」两个字
 * → 拿它的坐标 → 点过去。这样换任何布局都能找到。
 *
 * 只在安卓 11（API 30）以上可用（takeScreenshot 是那时加的）。
 */
public final class OcrFinder {

    /** 认这些字眼（按优先级）。 */
    private static final String[] KEYS = new String[]{
            "点击签到", "立即签到", "签到", "立即打卡", "打卡"
    };

    private OcrFinder() {
    }

    /**
     * 截图 + OCR，找出按钮中心坐标。
     *
     * @param svc    无障碍服务
     * @param minY   只在这个 y 以下找（避开顶部标题「查寝定位打卡」）
     * @return {x, y}，找不到返回 null
     */
    public static int[] findButton(AccessibilityService svc, int minY) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        Bitmap bmp = shot(svc, 1200);
        if (bmp == null) {
            CheckinStatus.appendLog("OCR：截图失败");
            return null;
        }
        try {
            return recognize(bmp, minY);
        } catch (Throwable t) {
            CheckinStatus.appendLog("OCR 失败：" + t.getMessage());
            return null;
        } finally {
            try {
                bmp.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 截图（带超时，避免卡住工作线程）。 */
    private static Bitmap shot(AccessibilityService svc, long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> out = new AtomicReference<>(null);
        try {
            svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    svc.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            try {
                                Bitmap b = Bitmap.wrapHardwareBuffer(
                                        result.getHardwareBuffer(), result.getColorSpace());
                                if (b != null) {
                                    out.set(b.copy(Bitmap.Config.ARGB_8888, false));
                                }
                            } catch (Throwable ignored) {
                            } finally {
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            CheckinStatus.appendLog("OCR：截图回调失败 code=" + errorCode);
                            latch.countDown();
                        }
                    });
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            CheckinStatus.appendLog("OCR：截图异常 " + t.getMessage());
        }
        return out.get();
    }

    /** 识别文字并挑出按钮。 */
    private static int[] recognize(Bitmap bmp, int minY) {
        final TextRecognizer rec = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Text> out = new AtomicReference<>(null);
        final AtomicInteger err = new AtomicInteger(0);
        try {
            rec.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(t -> {
                        out.set(t);
                        latch.countDown();
                    })
                    .addOnFailureListener(e -> {
                        err.incrementAndGet();
                        latch.countDown();
                    });
            if (!latch.await(2500, TimeUnit.MILLISECONDS)) {
                CheckinStatus.appendLog("OCR：识别超时");
                return null;
            }
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                rec.close();
            } catch (Throwable ignored) {
            }
        }

        Text text = out.get();
        if (text == null) {
            return null;
        }

        int[] best = null;
        int bestScore = -1;
        List<Text.TextBlock> blocks = text.getTextBlocks();
        for (Text.TextBlock block : blocks) {
            String s = block.getText();
            if (s == null) {
                continue;
            }
            String compact = s.replaceAll("\\s+", "");
            for (int i = 0; i < KEYS.length; i++) {
                if (compact.contains(KEYS[i])) {
                    Rect r = block.getBoundingBox();
                    if (r == null) {
                        break;
                    }
                    // 标题「查寝定位打卡」在顶部，排除掉
                    if (r.centerY() < minY) {
                        break;
                    }
                    // 越靠下、字越短、关键字越靠前 → 越可能是按钮
                    int score = i * 1000 + (r.centerY()) - Math.min(compact.length(), 40);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new int[]{ r.centerX(), r.centerY() };
                    }
                    break;
                }
            }
        }
        if (best != null) {
            CheckinStatus.appendLog("OCR 找到按钮：" + best[0] + "," + best[1]);
        } else {
            CheckinStatus.appendLog("OCR：没找到「签到」字样");
        }
        return best;
    }
}
