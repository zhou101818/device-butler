package com.zhou101818.butler;

import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.AdbShellStream;
import dadb.AdbStreamClosed;
import dadb.AdbShellPacket;
import dadb.Dadb;

/**
 * 原生 ADB 客户端桥（多设备版）：
 * 可同时连接多台开启了网络 ADB（5555）的安卓设备，
 * 支持监看截图、远程触控、遥控按键、应用装卸与文件互传。
 */
@CapacitorPlugin(name = "AdbBridge")
public class AdbBridgePlugin extends Plugin {

    private static final Map<String, Dadb> clients = new ConcurrentHashMap<>();
    private static final Map<String, JSObject> propsCache = new ConcurrentHashMap<>();
    // 所有长任务跑在私有线程池：不占用 Capacitor 桥线程（否则一次卡顿会排队塞死后续所有 Promise）
    private final ExecutorService executor = Executors.newCachedThreadPool();
    // 每设备命令串行锁：input/swipe/tap 与 screenshot 的 dadb 流并发会互相干扰（截流抢占导致 input 丢失），串行化
    private static final Map<String, Object> deviceLocks = new ConcurrentHashMap<>();

    private Object lockFor(String serial) {
        return deviceLocks.computeIfAbsent(serial == null ? "" : serial, k -> new Object());
    }

    /** 异步执行插件调用（彻底避免桥线程阻塞死锁）；input 类命令与截图互斥串行 */
    private void async(PluginCall call, Runnable body) {
        executor.submit(() -> {
            try { body.run(); }
            catch (Throwable t) { try { call.reject(String.valueOf(t.getMessage())); } catch (Throwable ignored) {} }
        });
    }

    private File keyDir() {
        File d = new File(getContext().getFilesDir(), "adbkeys");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private AdbKeyPair loadKeyPair() throws IOException {
        File priv = new File(keyDir(), "adbkey");
        File pub = new File(keyDir(), "adbkey.pub");
        if (!priv.exists()) {
            AdbKeyPair.generate(priv, pub);
        }
        return AdbKeyPair.read(priv, pub);
    }

    /** 控制端本机 adb 公钥（base64 主体，无尾缀），供配对时提交给被控端 */
    @PluginMethod
    public void publicKey(PluginCall call) {
        try {
            File pub = new File(keyDir(), "adbkey.pub");
            if (!pub.exists()) {
                new File(keyDir(), "adbkey").delete();
                loadKeyPair();
            }
            String content = new String(java.nio.file.Files.readAllBytes(pub.toPath())).trim();
            // 格式：base64主体 name@host，取主体
            String body = content.split("\\s+")[0];
            JSObject ret = new JSObject();
            ret.put("ok", true);
            ret.put("key", body);
            call.resolve(ret);
        } catch (Throwable t) {
            call.reject("读取公钥失败：" + String.valueOf(t.getMessage()));
        }
    }

    /** 原生 HTTP 配对：POST http://host:55666/pair（绕开 WebView cleartext 限制） */
    @PluginMethod
    public void pairWith(final PluginCall call) {
        final String host = call.getString("host", "");
        final String code = call.getString("code", "");
        if (host.isEmpty() || code.isEmpty()) { call.reject("host 与 code 必填"); return; }
        executor.submit(() -> {
            java.net.HttpURLConnection conn = null;
            try {
                java.net.URL url = new java.net.URL("http://" + host + ":55666/pair");
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(6000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                File pub = new File(keyDir(), "adbkey.pub");
                if (!pub.exists()) { new File(keyDir(), "adbkey").delete(); loadKeyPair(); }
                String pk = new String(java.nio.file.Files.readAllBytes(pub.toPath())).trim().split("\\s+")[0];
                String payload = "{\"pairCode\":\"" + code + "\",\"publicKey\":\"" + pk + "\",\"name\":\"butler-ctl\"}";
                byte[] data = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(data.length);
                java.io.OutputStream os = conn.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
                int codeR = conn.getResponseCode();
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        codeR >= 400 ? conn.getErrorStream() : conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln);
                br.close();
                String resp = sb.toString();
                JSObject ret = new JSObject();
                if (codeR == 200 && resp.contains("\"ok\":true")) {
                    ret.put("ok", true);
                    call.resolve(ret);
                } else {
                    String msg = resp.contains("msg") ? extractJsonStr(resp, "msg") : ("HTTP " + codeR);
                    call.reject("配对失败：" + msg);
                }
            } catch (java.net.ConnectException ce) {
                call.reject("连不上配对服务：检查对方是否已开启「配对服务」，且两台设备在同一 Wi-Fi");
            } catch (Exception e) {
                call.reject("配对请求失败：" + String.valueOf(e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String extractJsonStr(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int c = json.indexOf(':', i + key.length() + 2);
        int q1 = json.indexOf('"', c + 1);
        if (c < 0 || q1 < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int j = q1 + 1; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (ch == '"') break;
            sb.append(ch);
        }
        return sb.toString();
    }

    private Dadb client(String serial) throws IOException {
        Dadb d = clients.get(serial);
        if (d == null) throw new IOException("设备未连接：" + serial);
        return d;
    }

    /** input 类命令专用临时连接：不复用被截图流占用的主连接（dadb 消息队列跨流干扰会吞掉 input 命令） */
    private void execOnFreshConn(PluginCall call, String serial, String cmd) {
        synchronized (lockFor(serial)) {
            Dadb tmp = null;
            try {
                String host = serial.split(":")[0];
                int port = Integer.parseInt(serial.split(":")[1]);
                tmp = Dadb.create(host, port, loadKeyPair());
                tmp.shell(cmd);
                try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                call.resolve();
            } catch (Throwable t) {
                call.reject(String.valueOf(t.getMessage()));
            } finally {
                if (tmp != null) { try { tmp.close(); } catch (Exception ignored) {} }
            }
        }
    }

    private void dropClient(String serial) {
        Dadb d = clients.remove(serial);
        propsCache.remove(serial);
        if (d != null) {
            final Dadb dd = d;
            executor.submit(() -> { try { dd.close(); } catch (Exception ignored) {} });
        }
    }

    @PluginMethod
    public void connect(PluginCall call) {
        final String addr = call.getString("addr", "");
        if (!addr.matches("^[\\w.-]+:\\d+$")) {
            call.reject("地址格式应为 IP:端口");
            return;
        }
        if (clients.containsKey(addr)) { call.resolve(); return; }
        final String host = addr.substring(0, addr.lastIndexOf(':'));
        final int port = Integer.parseInt(addr.substring(addr.lastIndexOf(':') + 1));
        Future<Dadb> f = executor.submit(() -> {
            AdbKeyPair kp = loadKeyPair();
            return Dadb.create(host, port, kp);
        });
        try {
            Dadb d = f.get(30, TimeUnit.SECONDS);
            clients.put(addr, d);
            call.resolve();
            executor.submit(() -> { try { pullProps(addr); } catch (Exception ignored) {} });
        } catch (TimeoutException te) {
            f.cancel(true);
            call.reject("连接超时：请确认设备已开启网络 ADB 且与手机在同一局域网");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            call.reject("连接失败：" + String.valueOf(cause.getMessage()));
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        String serial = call.getString("serial", "");
        if (serial.isEmpty()) {
            for (String s : clients.keySet()) dropClient(s);
            call.resolve();
            return;
        }
        dropClient(serial);
        call.resolve();
    }

    private void pullProps(String serial) throws IOException {
        Dadb d = client(serial);
        JSObject props = new JSObject();
        props.put("model", shellOut(d, "getprop ro.product.model"));
        props.put("brand", shellOut(d, "getprop ro.product.brand"));
        props.put("version", shellOut(d, "getprop ro.build.version.release"));
        String res = shellOut(d, "wm size");
        Matcher m = Pattern.compile("(\\d+x\\d+)").matcher(res);
        props.put("resolution", m.find() ? m.group(1) : "");
        String bat = shellOut(d, "dumpsys battery");
        Matcher bm = Pattern.compile("level:\\s*(\\d+)").matcher(bat);
        props.put("battery", bm.find() ? bm.group(1) + "%" : "");
        propsCache.put(serial, props);
    }

    private String shellOut(Dadb d, String cmd) throws IOException {
        AdbShellResponse r = d.shell(cmd);
        String s = (r.getOutput() + r.getErrorOutput()).trim();
        return s.isEmpty() ? "-" : s;
    }

    @PluginMethod
    public void status(PluginCall call) {
        async(call, () -> {
            JSArray arr = new JSArray();
            for (String serial : clients.keySet()) {
                JSObject o = new JSObject();
                o.put("serial", serial);
                JSObject p = propsCache.get(serial);
                o.put("props", p != null ? p : new JSObject());
                arr.put(o);
            }
            JSObject ret = new JSObject();
            ret.put("devices", arr);
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void refresh(PluginCall call) {
        String serial = call.getString("serial", "");
        async(call, () -> {
            try {
                requireClient(serial);
                pullProps(serial);
                JSObject ret = new JSObject();
                ret.put("props", propsCache.get(serial));
                call.resolve(ret);
            } catch (IOException e) { call.reject(String.valueOf(e.getMessage())); }
        });
    }

    private void requireClient(String serial) throws IOException {
        if (serial == null || serial.isEmpty() || !clients.containsKey(serial)) {
            throw new IOException("设备未连接：" + serial);
        }
    }

    /** 截图：优先 shell_v2 raw（二进制安全），不支持时降级为落盘 + pull；带总超时防线程悬挂 */
    @PluginMethod
    public void screenshot(PluginCall call) {
        String serial = call.getString("serial", "");
        async(call, () -> {
            try {
                byte[] png = shotBytes(serial);
                if (png == null) throw new IOException("截图超时");
                JSObject ret = new JSObject();
                ret.put("ok", true);
                ret.put("b64", Base64.encodeToString(png, Base64.NO_WRAP));
                call.resolve(ret);
            } catch (IOException | RuntimeException e) {
                call.reject("截图失败：" + String.valueOf(e.getMessage()));
            }
        });
    }

    /** 截图核心：v2 流读取 + 落盘 pull 双路径；超时/失败返回 null；不轻易断连接 */
    private byte[] shotBytes(String serial) {
        Dadb d;
        try { d = client(serial); } catch (IOException e) { return null; }
        byte[] png = null;
        // 路径 A：shell_v2 raw 流
        try {
            long deadline = System.currentTimeMillis() + 12000;
            AdbShellStream s = d.openShell("screencap -p");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            while (System.currentTimeMillis() < deadline) {
                try {
                    AdbShellPacket pkt = s.read();
                    if (pkt instanceof AdbShellPacket.Exit) break;
                    if (pkt instanceof AdbShellPacket.StdOut) {
                        bos.write(((AdbShellPacket.StdOut) pkt).getPayload());
                    }
                } catch (AdbStreamClosed closed) {
                    break;
                } catch (Throwable t) {
                    break;
                }
            }
            try { s.close(); } catch (Exception ignored) {}
            byte[] out = bos.toByteArray();
            if (out.length > 8 && out[0] == (byte) 0x89 && out[1] == (byte) 0x50) png = out;
        } catch (Throwable t) {
            // v2 失败，试降级
        }
        // 路径 B：落盘 + sync pull（双保险）
        if (png == null) {
            try {
                String remote = "/data/local/tmp/.butler_shot_" + Math.abs(serial.hashCode()) + ".png";
                d.shell("screencap -p " + remote);
                File tmp = new File(getContext().getCacheDir(), "shot.png");
                d.pull(tmp, remote);
                d.shell("rm " + remote);
                png = readAll(tmp);
                tmp.delete();
                if (png.length < 8 || png[0] != (byte) 0x89) png = null;
            } catch (Throwable t) {
                png = null;
            }
        }
        return png;
    }

    private byte[] readAll(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        FileInputStream fis = new FileInputStream(f);
        int off = 0;
        while (off < buf.length) {
            int r = fis.read(buf, off, buf.length - off);
            if (r <= 0) break;
            off += r;
        }
        fis.close();
        return buf;
    }

    @PluginMethod
    public void tap(PluginCall call) {
        final String serial = call.getString("serial", "");
        final int x = call.getInt("x", 0), y = call.getInt("y", 0);
        async(call, () -> execOnFreshConn(call, serial, "input tap " + x + " " + y));
    }

    @PluginMethod
    public void key(PluginCall call) {
        final String serial = call.getString("serial", "");
        final String key = call.getString("key", "");
        java.util.Map<String, String> map = new java.util.HashMap<>();
        map.put("home", "3"); map.put("back", "4"); map.put("menu", "82");
        map.put("volup", "24"); map.put("voldown", "25"); map.put("power", "26");
        map.put("recents", "187"); map.put("wake", "224"); map.put("sleep", "223");
        map.put("up", "19"); map.put("down", "20"); map.put("left", "21"); map.put("right", "22"); map.put("confirm", "23");
        final String code = map.containsKey(key) ? map.get(key) : key;
        async(call, () -> execOnFreshConn(call, serial, "input keyevent " + code));
    }

    @PluginMethod
    public void swipe(PluginCall call) {
        final String serial = call.getString("serial", "");
        final int x1 = call.getInt("x1", 0), y1 = call.getInt("y1", 0), x2 = call.getInt("x2", 0), y2 = call.getInt("y2", 0), dur = call.getInt("dur", 300);
        async(call, () -> execOnFreshConn(call, serial, "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + dur));
    }

    @PluginMethod
    public void text(PluginCall call) {
        final String serial = call.getString("serial", "");
        String t = call.getString("text", "");
        if (t == null || t.isEmpty()) { call.reject("空文本"); return; }
        t = t.replace(" ", "%s")
             .replace("&", "\\&").replace("<", "\\<").replace(">", "\\>")
             .replace("|", "\\|").replace("(", "\\(").replace(")", "\\)")
             .replace(";", "\\;").replace("'", "\\'").replace("\"", "\\\"")
             .replace("$", "\\$").replace("`", "\\`").replace("*", "\\*").replace("~", "\\~");
        final String cmd = "input text " + t;
        async(call, () -> execOnFreshConn(call, serial, cmd));
    }

    @PluginMethod
    public void apps(PluginCall call) {
        try {
            Dadb d = client(call.getString("serial", ""));
            AdbShellResponse r = d.shell("pm list packages -3");
            JSArray arr = new JSArray();
            for (String line : (r.getOutput() + r.getErrorOutput()).split("\n")) {
                line = line.trim();
                if (line.startsWith("package:")) arr.put(line.substring(8));
            }
            JSObject ret = new JSObject();
            ret.put("apps", arr);
            call.resolve(ret);
        } catch (IOException e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void uninstall(PluginCall call) {
        try {
            Dadb d = client(call.getString("serial", ""));
            String pkg = call.getString("pkg", "");
            if (!pkg.matches("^[\\w.]+$")) { call.reject("包名不合法"); return; }
            try {
                d.uninstall(pkg);
                call.resolve();
            } catch (IOException e) {
                call.reject("卸载失败：" + String.valueOf(e.getMessage()));
            }
        } catch (IOException e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void install(PluginCall call) {
        try {
            Dadb d = client(call.getString("serial", ""));
            String name = call.getString("name", "app.apk");
            String b64 = call.getString("data", "");
            byte[] data = Base64.decode(b64, Base64.NO_WRAP);
            File f = writeTemp(name, data);
            try {
                d.install(f);
                call.resolve();
            } catch (IOException e) {
                call.reject("安装失败：" + String.valueOf(e.getMessage()));
            } finally {
                f.delete();
            }
        } catch (IOException e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void push(PluginCall call) {
        try {
            Dadb d = client(call.getString("serial", ""));
            String name = call.getString("name", "file.bin");
            String remote = call.getString("remote", "/sdcard/Download/");
            if (!remote.endsWith("/")) remote = remote + "/";
            String b64 = call.getString("data", "");
            byte[] data = Base64.decode(b64, Base64.NO_WRAP);
            okio.Buffer buf = new okio.Buffer();
            buf.write(data);
            // mode 33188 = 0100644；用 Source 重载，避免 dadb 内部读取 unix 文件属性（Android 不支持）
            d.push(buf, remote + name, 33188, System.currentTimeMillis());
            JSObject ret = new JSObject();
            ret.put("msg", "已推送到 " + remote + name);
            call.resolve(ret);
        } catch (IOException e) { call.reject(String.valueOf(e.getMessage())); }
    }

    @PluginMethod
    public void pull(PluginCall call) {
        try {
            Dadb d = client(call.getString("serial", ""));
            String path = call.getString("path", "");
            if (path.isEmpty()) { call.reject("路径为空"); return; }
            File dst = new File(getContext().getCacheDir(), "pull_" + System.currentTimeMillis());
            try {
                d.pull(dst, path);
                byte[] data = readAll(dst);
                JSObject ret = new JSObject();
                ret.put("ok", true);
                ret.put("b64", Base64.encodeToString(data, Base64.NO_WRAP));
                ret.put("name", path.substring(path.lastIndexOf('/') + 1));
                call.resolve(ret);
            } catch (IOException e) {
                call.reject("拉取失败：" + String.valueOf(e.getMessage()));
            } finally {
                dst.delete();
            }
        } catch (IOException e) { call.reject(String.valueOf(e.getMessage())); }
    }

    private File writeTemp(String name, byte[] data) throws IOException {
        File dir = new File(getContext().getCacheDir(), "xfer");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, name);
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(data);
        fos.close();
        return f;
    }
}