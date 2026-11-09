package com.zzu.checkin;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * 设备档案：识别品牌 / 系统版本，决定"该走哪条路"。
 *
 * 为什么要这个类：
 * 华为、vivo、OPPO 的「自启动 / 后台活动」设置页完全不同，
 * 同一个 App 装到不同品牌的平板上，需要跳到不同的设置页，
 * 后台能不能活下来也很不一样。这里统一收口。
 */
public final class DeviceProfile {

    public enum Brand { HUAWEI, HONOR, VIVO, OPPO, XIAOMI, SAMSUNG, OTHER }

    private DeviceProfile() {
    }

    public static Brand brand() {
        String m = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER).toLowerCase();
        String b = (Build.BRAND == null ? "" : Build.BRAND).toLowerCase();
        String s = m + " " + b;
        if (s.contains("huawei")) return Brand.HUAWEI;
        if (s.contains("honor")) return Brand.HONOR;
        if (s.contains("vivo") || s.contains("iqoo")) return Brand.VIVO;
        if (s.contains("oppo") || s.contains("realme") || s.contains("oneplus")) return Brand.OPPO;
        if (s.contains("xiaomi") || s.contains("redmi") || s.contains("poco")) return Brand.XIAOMI;
        if (s.contains("samsung")) return Brand.SAMSUNG;
        return Brand.OTHER;
    }

    public static String brandName() {
        return Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")";
    }

    /** 一句话描述本机需要额外注意什么。 */
    public static String advice() {
        switch (brand()) {
            case HUAWEI:
            case HONOR:
                return "设置 → 电池 → 应用启动管理 → 本应用：关闭自动管理，三个开关全开";
            case VIVO:
                return "设置 → 电池 → 后台高耗电 → 本应用：允许；"
                        + "i管家 → 应用管理 → 自启动 → 本应用：打开";
            case OPPO:
                return "设置 → 电池 → 应用耗电管理 → 本应用：允许后台运行、允许自启动";
            case XIAOMI:
                return "设置 → 应用设置 → 本应用 → 权限 → 自启动：打开；省电策略：无限制";
            default:
                return "设置 → 电池 → 本应用：不受限制 / 允许后台活动";
        }
    }

    /**
     * 尝试跳到本品牌的「自启动管理」页。
     * 能跳过去就行，具体开关仍需用户点（系统不允许第三方代改）。
     * 返回 true 表示成功跳转。
     */
    public static boolean openAutoStartSettings(Context ctx) {
        String[][] candidates;
        switch (brand()) {
            case HUAWEI:
            case HONOR:
                candidates = new String[][]{
                        {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                        {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                };
                break;
            case VIVO:
                candidates = new String[][]{
                        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                };
                break;
            case OPPO:
                candidates = new String[][]{
                        {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                        {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
                };
                break;
            case XIAOMI:
                candidates = new String[][]{
                        {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                };
                break;
            default:
                candidates = new String[0][];
        }
        for (String[] c : candidates) {
            try {
                Intent i = new Intent();
                i.setClassName(c[0], c[1]);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                CheckinStatus.appendLog("已跳转到自启动设置页：" + c[0]);
                return true;
            } catch (Throwable ignored) {
            }
        }
        // 兜底：跳到应用详情页，用户自己找
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            CheckinStatus.appendLog("已跳转到应用详情页（+ " + advice() + "）");
            return true;
        } catch (Throwable t) {
            CheckinStatus.appendLog("打开设置失败：" + t.getMessage());
            return false;
        }
    }
}
