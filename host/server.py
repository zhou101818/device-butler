# -*- coding: utf-8 -*-
"""设备管家 v1 宿主服务：本地 HTTP API + adb.exe 封装（仅监听 127.0.0.1）"""
import json
import os
import re
import subprocess
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

ROOT = os.path.dirname(os.path.abspath(__file__))
DOCS = os.path.join(ROOT, "..", "docs")
ADB = os.path.join(ROOT, "bin", "adb.exe")
PORT = 8970

STATE = {"serial": "", "props": {}}
LOCK = threading.Lock()


def run_adb(args, timeout=25, raw=False):
    """执行 adb 命令；有设备时自动带 -s"""
    cmd = [ADB]
    s = STATE.get("serial")
    if s and args and args[0] not in ("connect", "disconnect", "devices", "start-server", "kill-server"):
        cmd += ["-s", s]
    cmd += list(args)
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return 1, "timeout", ""
    out = p.stdout
    if isinstance(out, bytes):
        try:
            out = out.decode("utf-8", "replace")
        except Exception:
            out = str(out)
    if raw:
        return p.returncode, p.stdout, ""
    return p.returncode, out, ""


def parse_devices():
    code, out, _ = run_adb(["devices"])
    result = []
    if code == 0 and out:
        for line in out.splitlines()[1:]:
            line = line.strip()
            if not line or line.startswith("*"):
                continue
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                result.append(parts[0])
    return result


def pull_props():
    props = {}
    if not STATE["serial"]:
        return props
    keys = {
        "model": "ro.product.model",
        "brand": "ro.product.brand",
        "version": "ro.build.version.release",
        "sdk": "ro.build.version.sdk",
    }
    for k, prop in keys.items():
        _, out, _ = run_adb(["shell", "getprop", prop], timeout=10)
        props[k] = (out or "").strip()
    _, res, _ = run_adb(["shell", "wm", "size"], timeout=10)
    m = re.search(r"(\d+x\d+)", res or "")
    props["resolution"] = m.group(1) if m else ""
    _, bat, _ = run_adb(["shell", "dumpsys", "battery"], timeout=10)
    m = re.search(r"level:\s*(\d+)", bat or "")
    props["battery"] = (m.group(1) + "%") if m else ""
    STATE["props"] = props
    return props


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    # ---------- helpers ----------
    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, data, ctype, name=None):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        if name:
            self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + name)
        self.end_headers()
        self.wfile.write(data)

    def body_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except Exception:
            return {}

    def body_raw(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n > 0 else b""

    # ---------- routes ----------
    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.end_headers()

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/" or u.path == "/index.html":
            data = open(os.path.join(DOCS, "index.html"), "rb").read()
            self.send_bytes(data, "text/html; charset=utf-8")
            return
        if u.path == "/api/status":
            with LOCK:
                devices = parse_devices()
                serial = STATE.get("serial")
                if serial and serial not in devices:
                    STATE["serial"] = ""
                    STATE["props"] = {}
                self.send_json({"ok": True, "connected": bool(STATE["serial"]), "serial": STATE["serial"], "devices": devices, "props": STATE["props"]})
            return
        if u.path == "/api/screenshot":
            if not STATE["serial"]:
                self.send_json({"ok": False, "msg": "未连接设备"}, 503)
                return
            code, data, _ = run_adb(["exec-out", "screencap", "-p"], timeout=15, raw=True)
            if code == 0 and isinstance(data, bytes) and data[:8].startswith(b"\x89PNG"):
                self.send_bytes(data, "image/png")
            else:
                self.send_json({"ok": False, "msg": "截图失败"}, 500)
            return
        if u.path == "/api/apps":
            if not STATE["serial"]:
                self.send_json({"ok": False, "msg": "未连接设备"}, 503)
                return
            _, out, _ = run_adb(["shell", "pm", "list", "packages", "-3"], timeout=20)
            pkgs = [l.replace("package:", "").strip() for l in (out or "").splitlines() if l.startswith("package:")]
            self.send_json({"ok": True, "apps": pkgs})
            return
        if u.path == "/api/pull":
            q = parse_qs(u.query)
            path = unquote((q.get("path", [""])[0]) or "").strip()
            if not STATE["serial"] or not path:
                self.send_json({"ok": False, "msg": "未连接或路径为空"}, 400)
                return
            os.makedirs(os.path.join(ROOT, "tmp"), exist_ok=True)
            tmp = os.path.join(ROOT, "tmp", "pull_%d" % int(time.time() * 1000))
            code, out, _ = run_adb(["pull", path, tmp], timeout=120)
            if code == 0 and os.path.exists(tmp):
                data = open(tmp, "rb").read()
                os.remove(tmp)
                self.send_bytes(data, "application/octet-stream", os.path.basename(path).replace('"', ''))
            else:
                self.send_json({"ok": False, "msg": "拉取失败：" + str(out)[:200]}, 500)
            return
        self.send_json({"ok": False, "msg": "not found"}, 404)

    def do_POST(self):
        u = urlparse(self.path)
        if u.path == "/api/connect":
            body = self.body_json()
            addr = (body.get("addr") or "").strip()
            if not re.match(r"^[\w.-]+:\d+$", addr):
                self.send_json({"ok": False, "msg": "地址格式应为 IP:端口"})
                return
            run_adb(["start-server"], timeout=15)
            code, out, _ = run_adb(["connect", addr], timeout=20)
            ok = "connected" in (out or "") or "already" in (out or "")
            if ok:
                with LOCK:
                    STATE["serial"] = addr
                    STATE["props"] = {}
                    pull_props()
            self.send_json({"ok": ok, "msg": (out or "").strip()[:200]})
            return
        if u.path == "/api/disconnect":
            with LOCK:
                s = STATE["serial"]
                STATE["serial"] = ""
                STATE["props"] = {}
            if s:
                run_adb(["disconnect", s], timeout=10)
            self.send_json({"ok": True})
            return
        if u.path == "/api/refresh":
            with LOCK:
                pull_props()
                self.send_json({"ok": True, "props": STATE["props"]})
            return
        if not STATE["serial"]:
            self.send_json({"ok": False, "msg": "未连接设备"}, 503)
            return
        if u.path == "/api/tap":
            b = self.body_json()
            run_adb(["shell", "input", "tap", str(int(b.get("x", 0))), str(int(b.get("y", 0)))], timeout=10)
            self.send_json({"ok": True})
            return
        if u.path == "/api/key":
            b = self.body_json()
            keys = {"home": "3", "back": "4", "menu": "82", "volup": "24", "voldown": "25", "power": "26", "recents": "187", "wake": "224", "sleep": "223"}
            code = keys.get(b.get("key", ""), b.get("key", ""))
            run_adb(["shell", "input", "keyevent", str(code)], timeout=10)
            self.send_json({"ok": True})
            return
        if u.path == "/api/swipe":
            b = self.body_json()
            run_adb(["shell", "input", "swipe", str(int(b.get("x1", 0))), str(int(b.get("y1", 0))), str(int(b.get("x2", 0))), str(int(b.get("y2", 0))), str(int(b.get("dur", 300)))], timeout=10)
            self.send_json({"ok": True})
            return
        if u.path == "/api/text":
            b = self.body_json()
            t = (b.get("text") or "")
            if not t:
                self.send_json({"ok": False, "msg": "空文本"})
                return
            t = t.replace(" ", "%s").replace("&", "\\&").replace("<", "\\<").replace(">", "\\>").replace("|", "\\|").replace("(", "\\(").replace(")", "\\)").replace(";", "\\;").replace("'", "\\'").replace('"', '\\"')
            run_adb(["shell", "input", "text", t], timeout=10)
            self.send_json({"ok": True})
            return
        if u.path == "/api/uninstall":
            b = self.body_json()
            pkg = (b.get("pkg") or "").strip()
            if not re.match(r"^[\w.]+$", pkg):
                self.send_json({"ok": False, "msg": "包名不合法"})
                return
            code, out, _ = run_adb(["shell", "pm", "uninstall", pkg], timeout=30)
            self.send_json({"ok": "Success" in (out or ""), "msg": (out or "").strip()[:200]})
            return
        if u.path in ("/api/install", "/api/push"):
            name = unquote(self.headers.get("X-Filename") or "file.bin")
            data = self.body_raw()
            os.makedirs(os.path.join(ROOT, "tmp"), exist_ok=True)
            tmp = os.path.join(ROOT, "tmp", "up_" + str(int(time.time() * 1000)) + "_" + os.path.basename(name))
            open(tmp, "wb").write(data)
            if u.path == "/api/install":
                code, out, _ = run_adb(["install", "-r", "-g", tmp], timeout=180)
                os.remove(tmp)
                self.send_json({"ok": "Success" in (out or ""), "msg": (out or "").strip()[:300]})
            else:
                remote = self.headers.get("X-Remote") or "/sdcard/Download/"
                remote = remote if remote.endswith("/") else remote + "/"
                code, out, _ = run_adb(["push", tmp, remote + os.path.basename(name)], timeout=300)
                os.remove(tmp)
                ok = code == 0
                self.send_json({"ok": ok, "msg": (out or "").strip()[:300] or ("已推送到 " + remote + os.path.basename(name))})
            return
        self.send_json({"ok": False, "msg": "not found"}, 404)


def main():
    if not os.path.exists(ADB):
        print("缺少 bin/adb.exe")
        sys.exit(1)
    run_adb(["start-server"], timeout=20)
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    url = "http://127.0.0.1:%d/" % PORT
    print("设备管家已启动：" + url)
    threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
