package com.zzu.checkin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

/**
 * 常驻守护服务。
 *
 * 为什么必须有它：
 * 华为的 iAware（智能资源调度）会把“看起来没在用”的后台进程清掉。
 * 进程一死，无障碍服务跟着死，系统还会把它标记为 Crashed，
 * 之后不再自动重连——于是“某天突然打不上卡，之后天天打不上”。
 *
 * 两个对策：
 * 1. 用前台服务 + 常驻通知，让系统把我们当“用户可见”的进程，不敢随便清；
 * 2. 每 10 分钟自检一次无障碍，掉了立刻发高优先级通知，点一下直接跳设置页。
 */
public final class Guard extends Service {

    private static final String CH_KEEP = "guard_keep";
    private static final String CH_ALERT = "guard_alert";
    private static final int ID_KEEP = 2101;
    private static final int ID_ALERT = 2102;
    private static final long CHECK_MS = 10 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            checkAccessibility(Guard.this, true);
            handler.postDelayed(this, CHECK_MS);
        }
    };

    /** 幂等地把守护服务拉起来。任何入口（开机、启动 App、闹钟、巡检）都可以调。 */
    public static void start(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Intent i = new Intent(ctx, Guard.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            CheckinStatus.appendLog("守护服务启动失败：" + t.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannels(this);
        try {
            startForeground(ID_KEEP, buildKeepNotification(this));
        } catch (Throwable t) {
            CheckinStatus.appendLog("常驻通知失败：" + t.getMessage());
        }
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        checkAccessibility(this, true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        // 被系统杀掉后尽量自己回来
        start(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ---------------- 无障碍自检 ---------------- */

    /**
     * 检查无障碍是否真的连着。
     *
     * 注意：不能只查“系统设置里是否勾选”，因为服务崩溃后设置仍然是勾着的，
     * 系统却不会再绑定它。真正的依据是 CheckinService 有没有连上来。
     */
    public static boolean accessibilityAlive() {
        return CheckinStatus.isAccessibilityEnabled();
    }

    /**
     * @param alert 是否发告警通知（定时巡检传 true；只做轻量检查传 false）
     */
    public static boolean checkAccessibility(Context ctx, boolean alert) {
        if (accessibilityAlive()) {
            cancelAlert(ctx);
            return true;
        }
        // 进程刚起来时服务可能还在连接，给它一点时间，避免误报
        if (alert) {
            showA11yAlert(ctx);
        }
        return false;
    }

    public static void showA11yAlert(Context ctx) {
        try {
            ensureChannels(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 4004, i, flags);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(ctx, CH_ALERT);
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setSmallIcon(android.R.drawable.stat_notify_error);
            b.setContentTitle("⚠️ 打卡助手的无障碍被关掉了");
            b.setContentText("今晚会打不上卡。点这里重新开启（只需一下）");
            b.setContentIntent(pi);
            b.setAutoCancel(true);
            b.setOngoing(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                b.setPriority(Notification.PRIORITY_MAX);
            }
            nm.notify(ID_ALERT, b.build());
            CheckinStatus.appendLog("检测到无障碍掉线，已发告警通知");
        } catch (Throwable t) {
            CheckinStatus.appendLog("告警通知失败：" + t.getMessage());
        }
    }

    private static void cancelAlert(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(ID_ALERT);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 通知 ---------------- */

    private static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel keep = new NotificationChannel(CH_KEEP, "后台守护", NotificationManager.IMPORTANCE_MIN);
        keep.setDescription("让系统知道打卡助手正在运行（不打扰、不响铃）");
        keep.setSound(null, null);
        keep.enableVibration(false);
        keep.setShowBadge(false);
        nm.createNotificationChannel(keep);

        NotificationChannel alert = new NotificationChannel(CH_ALERT, "权限告警", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("无障碍被关掉时立刻提醒，否则当晚打不上卡");
        alert.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(alert);
    }

    private static Notification buildKeepNotification(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 4005, i, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(ctx, CH_KEEP);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(android.R.drawable.stat_notify_sync_noanim);
        b.setContentTitle("查寝打卡助手守护中");
        b.setContentText(accessibilityAlive() ? "无障碍正常，今晚会按时打卡" : "⚠️ 无障碍未连接，点开检查");
        b.setContentIntent(pi);
        b.setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            b.setPriority(Notification.PRIORITY_MIN);
        }
        return b.build();
    }
}
