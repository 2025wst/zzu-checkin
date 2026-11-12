package com.zzu.checkin;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;

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
 * 关键坑（华为 MatePad 实测）：
 * takeScreenshot() 返回的位图可能是「自然朝向」（1440x2200 竖），
 * 而屏幕此刻是横屏（2200x1440）。这时 OCR 出来的坐标全是转过的：
 *   横屏屏幕 + 竖屏位图： x屏 = 位图高 - y位图 ,  y屏 = x位图
 *   竖屏屏幕 + 横屏位图： x屏 = y位图 ,          y屏 = 位图宽 - x位图
 * 不转换就会点到完全不相干的地方（实测点到了左边首页面板上）。
 */
public final class OcrFinder {

    /** 认这些字眼（越靠前越可信）。 */
    private static final String[] KEYS = new String[]{
            "点击签到", "立即签到", "签到", "立即打卡"
    };

    private OcrFinder() {
    }

    /**
     * 截图 + OCR，找出按钮中心坐标（屏幕坐标）。
     *
     * @param svc  无障碍服务
     * @param minY 只在这个 y 以下找（避开顶部标题「查寝定位打卡」）
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
            int[] pt = recognize(bmp, svc);
            if (pt == null) {
                return null;
            }
            if (pt[1] < minY) {
                CheckinStatus.appendLog("OCR 命中的是标题区，忽略 (" + pt[0] + "," + pt[1] + ")");
                return null;
            }
            CheckinStatus.appendLog("OCR 找到按钮：" + pt[0] + "," + pt[1]
                    + "（位图 " + bmp.getWidth() + "x" + bmp.getHeight() + "）");
            return pt;
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

    /** 识别文字 → 转换坐标 → 挑出最像按钮的那块。 */
    private static int[] recognize(Bitmap bmp, AccessibilityService svc) {
        final TextRecognizer rec = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Text> out = new AtomicReference<>(null);
        try {
            rec.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener(t -> {
                        out.set(t);
                        latch.countDown();
                    })
                    .addOnFailureListener(e -> latch.countDown());
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
        int bestScore = Integer.MIN_VALUE;
        List<Text.TextBlock> blocks = text.getTextBlocks();
        for (Text.TextBlock block : blocks) {
            String s = block.getText();
            if (s == null) {
                continue;
            }
            String compact = s.replaceAll("\\s+", "");
            for (int i = 0; i < KEYS.length; i++) {
                if (!compact.contains(KEYS[i])) {
                    continue;
                }
                Rect r = block.getBoundingBox();
                if (r == null) {
                    break;
                }
                int[] pt = toScreen(bmp, svc, r.centerX(), r.centerY());
                // 关键字越靠前、文字越短、位置越靠下 → 越像按钮
                int score = i * 10000 + pt[1] - Math.min(compact.length(), 60) * 5;
                if (score > bestScore) {
                    bestScore = score;
                    best = pt;
                }
                break;
            }
        }
        if (best == null) {
            CheckinStatus.appendLog("OCR：没找到「签到」字样");
        }
        return best;
    }

    /** 位图坐标 → 屏幕坐标（处理位图朝向与屏幕朝向不一致）。 */
    private static int[] toScreen(Bitmap bmp, AccessibilityService svc, int bx, int by) {
        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        boolean dispLandscape = dm.widthPixels > dm.heightPixels;
        boolean bmpLandscape = bw > bh;
        if (dispLandscape == bmpLandscape) {
            return new int[]{ bx, by };
        }
        if (dispLandscape) {
            return new int[]{ bh - by, bx };
        }
        return new int[]{ by, bw - bx };
    }
}
