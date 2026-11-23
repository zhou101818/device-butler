package com.zhou101818.butler;

import android.content.Context;
import android.content.SharedPreferences;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 配对码服务：被控端监听 55666 端口。
 * 控制端 POST {pairCode, publicKey, name} ——
 *   校验 6 位配对码通过后，把控制端公钥写入 adb 白名单（经 Shizuku shell），
 *   控制端随后连接 5555 就不再弹「允许调试」。
 */
@CapacitorPlugin(name = "PairServer")
public class PairServerPlugin extends Plugin {

    private static final int PORT = 55666;
    private static volatile ServerSocket server = null;
    private static volatile boolean running = false;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("butler_pair", Context.MODE_PRIVATE);
    }

    private String shizukuExec(String cmd) throws Exception {
        return ShizukuBridgePlugin.shellExec(cmd);
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("running", running);
        ret.put("port", PORT);
        ret.put("codeSet", prefs().contains("pair_code"));
        call.resolve(ret);
    }

    @PluginMethod
    public void setCode(PluginCall call) {
        String code = call.getString("code", "");
        if (code == null || !code.matches("\\d{6}")) {
            call.reject("配对码需为 6 位数字");
            return;
        }
        prefs().edit().putString("pair_code", code).apply();
        JSObject ret = new JSObject();
        ret.put("ok", true);
        ret.put("code", code);
        call.resolve(ret);
    }

    @PluginMethod
    public void getCode(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("code", prefs().getString("pair_code", ""));
        call.resolve(ret);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (running) { call.resolve(); return; }
        // 起服务前先验证 Shizuku shell 通道可用（配对时要用它注入公钥）
        executor.submit(() -> {
            try {
                ShizukuBridgePlugin.probeShell(); // 无返回即通道 OK
                server = new ServerSocket(PORT);
                running = true;
                executor.submit(this::serveLoop);
                JSObject ret = new JSObject();
                ret.put("ok", true);
                ret.put("port", PORT);
                call.resolve(ret);
            } catch (Throwable t) {
                call.reject("需先完成 Shizuku 授权并开启网络 ADB：" + String.valueOf(t.getMessage()));
            }
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        server = null;
        call.resolve();
    }

    private void serveLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                executor.submit(() -> handle(s));
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);

            String line = in.readLine();
            if (line == null || !line.startsWith("POST /pair")) { httpOut(out, 404, "{\"ok\":false}"); s.close(); return; }
            int len = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                String low = line.toLowerCase();
                if (low.startsWith("content-length:")) {
                    try { len = Integer.parseInt(low.substring(15).trim()); } catch (Exception ignored) {}
                }
            }
            if (len <= 0 || len > 8192) { httpOut(out, 400, "{\"ok\":false,\"msg\":\"bad length\"}"); s.close(); return; }
            char[] buf = new char[len];
            int read = 0;
            while (read < len) {
                int r = in.read(buf, read, len - read);
                if (r <= 0) break;
                read += r;
            }
            String body = new String(buf, 0, read);

            String code = jsonStr(body, "pairCode");
            String publicKey = jsonStr(body, "publicKey");
            String name = jsonStr(body, "name");

            String expect = prefs().getString("pair_code", "");
            JSObject resp = new JSObject();
            if (expect.isEmpty()) {
                resp.put("ok", false); resp.put("msg", "被控端未设置配对码");
                httpOut(out, 403, resp.toString());
            } else if (code == null || !code.equals(expect)) {
                resp.put("ok", false); resp.put("msg", "配对码错误");
                httpOut(out, 403, resp.toString());
            } else if (publicKey == null || publicKey.length() < 200 || !publicKey.matches("[A-Za-z0-9+/=]+")) {
                resp.put("ok", false); resp.put("msg", "公钥格式错误");
                httpOut(out, 400, resp.toString());
            } else {
                String cleanKey = publicKey.trim();
                String marker = "butler_" + sha6(cleanKey);
                String cmd = "grep -qF '" + cleanKey + "' /data/misc/adb/adb_keys 2>/dev/null || echo '"
                        + cleanKey + " " + marker + "' >> /data/misc/adb/adb_keys";
                shizukuExec(cmd);
                resp.put("ok", true);
                resp.put("name", name == null ? "" : name);
                httpOut(out, 200, resp.toString());
                prefs().edit().putString("last_paired", name == null ? "" : name).putLong("last_paired_at", System.currentTimeMillis()).apply();
            }
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void httpOut(PrintWriter out, int status, String body) {
        String reason = status == 200 ? "OK" : (status == 403 ? "Forbidden" : "Bad Request");
        out.print("HTTP/1.1 " + status + " " + reason + "\r\n");
        out.print("Content-Type: application/json; charset=utf-8\r\n");
        out.print("Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n");
        out.print("Connection: close\r\n\r\n");
        out.print(body);
        out.flush();
    }

    private static String jsonStr(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        int c = json.indexOf(':', i + key.length() + 2);
        if (c < 0) return null;
        int q1 = json.indexOf('"', c + 1);
        if (q1 < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int j = q1 + 1; j < json.length(); j++) {
            char ch = json.charAt(j);
            if (ch == '\\' && j + 1 < json.length()) { sb.append(json.charAt(++j)); continue; }
            if (ch == '"') break;
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String sha6(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            String hex = sb.toString();
            long v = 0;
            for (int i = 0; i < 12; i++) v = v * 16 + Character.digit(hex.charAt(i), 16);
            return String.format("%06d", v % 1000000);
        } catch (Exception e) {
            return "000000";
        }
    }
}