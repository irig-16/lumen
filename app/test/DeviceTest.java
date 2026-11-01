package com.lumen.launcher;
public class DeviceTest {
  static int pass, fail;
  static void eq(Object g, Object w, String what) { if (String.valueOf(g).equals(String.valueOf(w))) pass++; else { fail++; System.out.println("FAIL " + what + ": [" + g + "] want [" + w + "]"); } }
  static DeviceInfo c(String plat, String hw, String board, String soc, String maker, String brand, String model) {
    return DeviceInfo.classify(plat, hw, board, soc, maker, brand, model);
  }
  public static void main(String[] a) {
    DeviceInfo hy = c("ares", "sun50iw12p1", "exdroid", "", "", "Android", "HY300 Pro");
    eq(hy.family, "Allwinner", "HY300 family"); eq(hy.chip, "H713", "HY300 chip"); eq(hy.h713, true, "h713 flag");
    eq(hy.chipLabel(), "Allwinner H713 · HY300 family", "label"); eq(hy.mayHavePictureProcessor(), true, "pq on allwinner");
    DeviceInfo h616 = c("sun50iw9p1", "sun50iw9p1", "", "", "", "Magcubic", "T10"); eq(h616.family, "Allwinner", "H616"); eq(h616.h713, false, "not h713");
    eq(h616.chip, "H616/H618", "h616 chip");
    DeviceInfo aml = c("sc2", "amlogic", "ohm", "S905X4", "Amlogic", "Xgimi", "MoGo 2"); eq(aml.family, "Amlogic", "amlogic"); eq(aml.chip, "S905X4", "amlogic chip");
    eq(aml.mayHavePictureProcessor(), false, "no aw pq on amlogic");
    eq(c("g12a", "amlogic", "", "", "", "", "").family, "Amlogic", "amlogic old (no soc props)");
    DeviceInfo rk = c("rk3566", "rk30board", "rk30sdk", "", "", "Wanbo", "T6R Max"); eq(rk.family, "Rockchip", "rockchip"); eq(rk.chip, "RK3566", "rk chip");
    eq(c("rk356x", "rk30board", "", "", "", "", "").family, "Rockchip", "rk356x platform");
    DeviceInfo mtk = c("mt8695", "mt8695", "", "", "", "", ""); eq(mtk.family, "MediaTek", "mediatek"); eq(mtk.chip, "MT8695", "mtk chip");
    eq(c("m7332", "maserati", "", "", "", "", "").family, "MediaTek", "mstar tv");
    eq(c("", "realtek", "stark", "", "", "", "").family, "Realtek", "realtek");
    eq(c("bigfish", "bigfish", "Hi3798MV300", "", "", "", "").family, "HiSilicon", "hisilicon");
    eq(c("msm8953", "qcom", "", "", "", "", "").family, "Qualcomm", "qualcomm");
    eq(c("ums512", "ums512_1h10", "", "", "", "", "").family, "Unisoc", "unisoc");
    DeviceInfo un = c("", "", "", "unknown", "", "Generic", "Projector"); eq(un.family, "", "unknown"); eq(un.chipLabel(), "Android projector", "generic label");
    hy.release = "11"; hy.width = 1280; hy.height = 720; hy.ramMb = 938;
    eq(hy.summary(), "Android HY300 Pro · Allwinner H713 · Android 11 · 1280×720 · 1 GB", "summary");
    aml.release = "11"; aml.width = 1920; aml.height = 1080; aml.ramMb = 2048;
    eq(aml.summary(), "Xgimi MoGo 2 · Amlogic S905X4 · Android 11 · 1920×1080 · 2 GB", "summary 2");
    eq(aml.lowMemory(), false, "2 GB not low"); eq(hy.lowMemory(), true, "1 GB low");
    // heat guard: each chip's own shutdown limit, and sensor names across chips
    eq(HeatGuard.criticalFrom(new String[]{"passive", "passive", "critical"}, new String[]{"85000", "95000", "110000"}), 110, "critical trip (m°C)");
    eq(HeatGuard.criticalFrom(new String[]{"active", "hot"}, new String[]{"70", "100"}), 100, "hot when no critical (°C)");
    eq(HeatGuard.criticalFrom(new String[]{"critical"}, new String[]{"200000"}), -1, "implausible ignored");
    eq(HeatGuard.criticalFrom(new String[]{"", ""}, new String[]{"", ""}), -1, "unreadable");
    eq(HeatGuard.isCpuZone("cpu-thermal"), true, "allwinner/rockchip cpu"); eq(HeatGuard.isCpuZone("soc_thermal"), true, "amlogic soc");
    eq(HeatGuard.isCpuZone("mtktscpu"), true, "mediatek cpu"); eq(HeatGuard.isGpuZone("gpu-thermal"), true, "gpu");
    eq(HeatGuard.isGpuZone("mali"), true, "mali"); eq(HeatGuard.isCpuZone("battery"), false, "not battery");
    System.out.println(pass + "/" + (pass + fail) + " passed");
  }
}
