package com.zhou101818.butler;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * 被控模式桥：通过 Shizuku（免 root）在本机开启网络 ADB（5555），
 * 让局域网内的「设备管家」控制端直连本机。
 */
@CapacitorPlugin(name = "ShizukuBridge")
public class ShizukuBridgePlugin extends Plugin {

    private static final String[] SHIZUKU_PKGS = {"moe.shizuku.android", "rikka.shizuku"};

    private boolean managerInstalled() {
        for (String pkg : SHIZUKU_PKGS) {
            try {
                PackageInfo pi = getContext().getPackageManager().getPackageInfo(pkg, 0);
                if (pi != null) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean ping() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean granted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("installed", managerInstalled());
        ret.put("ping", ping());
        int version = -1;
        try { version = Shizuku.getVersion(); } catch (Throwable ignored) {}
        ret.put("version", version);
        ret.put("granted", granted());
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermission(final PluginCall call) {
        if (!managerInstalled()) { call.reject("未安装 Shizuku 管理器，请先到 shizuku.rikka.app 下载安装"); return; }
        if (!ping()) { call.reject("Shizuku 服务未运行：请打开 Shizuku 应用，按引导用「无线调试」或 root 启动服务"); return; }
        if (granted()) { call.resolve(); return; }
        try {
            pendingPermCall = call;
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != PERM_REQ_CODE) return;
                    try { Shizuku.removeRequestPermissionResultListener(this); } catch (Throwable ignored) {}
                    if (pendingPermCall == null) return;
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        pendingPermCall.resolve();
                    } else {
                        pendingPermCall.reject("权限被拒绝");
                    }
                    pendingPermCall = null;
                }
            });
            Shizuku.requestPermission(PERM_REQ_CODE);
        } catch (Throwable t) {
            pendingPermCall = null;
            call.reject("请求权限失败：" + String.valueOf(t.getMessage()));
        }
    }

    private static com.zhou101818.butler.IShellUserService userService = null;
    private static final Object svcLock = new Object();

    private com.zhou101818.butler.IShellUserService ensureUserService() throws Exception {
        synchronized (svcLock) {
            if (userService != null) return userService;
            android.content.ComponentName cn = new android.content.ComponentName(getContext(), ShellUserService.class);
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(cn)
                    .debuggable(false)
                    .version(1);
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final android.os.IBinder[] ref = new android.os.IBinder[1];
            final Throwable[] err = new Throwable[1];
            android.content.ServiceConnection conn = new android.content.ServiceConnection() {
                @Override
                public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                    ref[0] = binder;
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(android.content.ComponentName name) {
                    userService = null;
                }
            };
            Shizuku.bindUserService(args, conn);
            if (!latch.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IOException(err[0] != null ? String.valueOf(err[0].getMessage()) : "绑定用户服务超时");
            }
            userService = com.zhou101818.butler.IShellUserService.Stub.asInterface(ref[0]);
            return userService;
        }
    }

    private String runShizukuCmd(String cmd) throws Exception {
        com.zhou101818.butler.IShellUserService svc = ensureUserService();
        return svc.exec(cmd);
    }

    @PluginMethod
    public void enableNetworkAdb(final PluginCall call) {
        try {
            if (!managerInstalled()) { call.reject("未安装 Shizuku 管理器"); return; }
            if (!ping()) { call.reject("Shizuku 服务未运行：请先在 Shizuku 应用中启动服务"); return; }
            if (!granted()) { call.reject("尚未授予 Shizuku 权限，请先点「授权」"); return; }
            // 在后台线程执行（adbd 重启会造成短暂卡顿）
            executor.submit(() -> {
                try {
                    String out = runShizukuCmd(ENABLE_CMD);
                    JSObject ret = new JSObject();
                    boolean ok = out.contains("5555");
                    ret.put("ok", ok);
                    ret.put("persist", runShizukuCmd("getprop persist.adb.tcp.port").contains("5555"));
                    ret.put("msg", ok ? "网络 ADB 已开启（5555）" : "开启结果异常：" + out);
                    call.resolve(ret);
                } catch (Throwable t) {
                    call.reject("执行失败：" + String.valueOf(t.getMessage()));
                }
            });
        } catch (Throwable t) {
            call.reject(String.valueOf(t.getMessage()));
        }
    }

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newCachedThreadPool();

    @PluginMethod
    public void localIps(PluginCall call) {
        try {
            JSArray arr = new JSArray();
            List<java.net.NetworkInterface> ifaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifaces) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
                for (InetAddress a : addrs) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        arr.put(a.getHostAddress());
                    }
                }
            }
            JSObject ret = new JSObject();
            ret.put("ips", arr);
            call.resolve(ret);
        } catch (Throwable t) {
            call.reject(String.valueOf(t.getMessage()));
        }
    }
}