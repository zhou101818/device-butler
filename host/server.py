# -*- coding: utf-8 -*-
"""设备管家 v2 宿主服务：多设备管理 + 本地 HTTP API + adb.exe 封装（仅监听 127.0.0.1）"""
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

MANAGED = {}  # serial -> {"props": {...}}
LOCK = threading.Lock()


def run_adb(args, serial=None, timeout=25, raw=False):
    cmd = [ADB]
    if serial and args and args[0] not in ("connect", "disconnect", "devices", "start-server", "kill-server"):
        cmd += ["-s", serial]
    cmd += list(args)
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return 1, "timeout", "" if not raw else b""
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


def pull_props(serial):
    props = {}
    keys = {
        "model": "ro.product.model",
        "brand": "ro.product.brand",
        "version": "ro.build.version.release",
        "sdk": "ro.build.version.sdk",
    }
    for k, prop in keys.items():
        _, out, _ = run_adb(["shell", "getprop", prop], serial=serial, timeout=10)
        props[k] = (out or "").strip()
    _, res, _ = run_adb(["shell", "wm", "size"], serial=serial, timeout=10)
    m = re.search(r"(\d+x\d+)", res or "")
    props["resolution"] = m.group(1) if m else ""
    _, bat, _ = run_adb(["shell", "dumpsys", "battery"], serial=serial, timeout=10)
    m = re.search(r"level:\s*(\d+)", bat or "")
    props["battery"] = (m.group(1) + "%") if m else ""
    with LOCK:
        if serial in MANAGED:
            MANAGED[serial]["props"] = props
            MANAGED[serial]["online"] = True
    return props


def require_serial(serial):
    if serial and serial in parse_devices():
        return True
    return False


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

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
                online = parse_devices()
                for s in list(MANAGED.keys()):
                    MANAGED[s]["online"] = s in online
                devices = []
                for s in online:
                    if s not in MANAGED:
                        MANAGED[s] = {"props": {}, "online": True}
                    entry = MANAGED[s]
                    devices.append({
                        "serial": s,
                        "online": s in online,
                        "props": entry.get("props", {}),
                    })
                self.send_json({"ok": True, "devices": devices})
            return
        if u.path == "/api/screenshot":
            q = parse_qs(u.query)
            serial = unquote((q.get("serial", [""])[0]) or "").strip()
            if not require_serial(serial):
                self.send_json({"ok": False, "msg": "设备不在线"}, 503)
                return
            code, data, _ = run_adb(["exec-out", "screencap", "-p"], serial=serial, timeout=15, raw=True)
            if code == 0 and isinstance(data, bytes) and data[:8].startswith(b"\x89PNG"):
                self.send_bytes(data, "image/png")
            else:
                self.send_json({"ok": False, "msg": "截图失败"}, 500)
            return
        if u.path == "/api/apps":
            q = parse_qs(u.query)
            serial = unquote((q.get("serial", [""])[0]) or "").strip()
            if not require_serial(serial):
                self.send_json({"ok": False, "msg": "设备不在线"}, 503)
                return
            _, out, _ = run_adb(["shell", "pm", "list", "packages", "-3"], serial=serial, timeout=20)
            pkgs = [l.replace("package:", "").strip() for l in (out or "").splitlines() if l.startswith("package:")]
            self.send_json({"ok": True, "apps": pkgs})
            return
        if u.path == "/api/pull":
            q = parse_qs(u.query)
            serial = unquote((q.get("serial", [""])[0]) or "").strip()
            path = unquote((q.get("path", [""])[0]) or "").strip()
            if not require_serial(serial) or not path:
                self.send_json({"ok": False, "msg": "设备不在线或路径为空"}, 400)
                return
            os.makedirs(os.path.join(ROOT, "tmp"), exist_ok=True)
            tmp = os.path.join(ROOT, "tmp", "pull_%d" % int(time.time() * 1000))
            code, out, _ = run_adb(["pull", path, tmp], serial=serial, timeout=120)
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
            ok = ("connected" in (out or "")) or ("already" in (out or ""))
            if ok:
                with LOCK:
                    if addr not in MANAGED:
                        MANAGED[addr] = {"props": {}, "online": True}
                    threading.Thread(target=pull_props, args=(addr,), daemon=True).start()
            self.send_json({"ok": ok, "msg": (out or "").strip()[:200]})
            return
        if u.path == "/api/disconnect":
            body = self.body_json()
            serial = (body.get("serial") or "").strip()
            if not serial:
                self.send_json({"ok": False, "msg": "缺少 serial"})
                return
            with LOCK:
                MANAGED.pop(serial, None)
            if ":" in serial:
                run_adb(["disconnect", serial], timeout=10)
            self.send_json({"ok": True})
            return
        if u.path == "/api/refresh":
            body = self.body_json()
            serial = (body.get("serial") or "").strip()
            if not require_serial(serial):
                self.send_json({"ok": False, "msg": "设备不在线"}, 503)
                return
            props = pull_props(serial)
            self.send_json({"ok": True, "props": props})
            return
        body = self.body_json() if self.headers.get("Content-Type", "").startswith("application/json") else {}
        serial = (body.get("serial") or "").strip()
        if not require_serial(serial):
            self.send_json({"ok": False, "msg": "设备不在线"}, 503)
            return
        if u.path == "/api/tap":
            run_adb(["shell", "input", "tap", str(int(body.get("x", 0))), str(int(body.get("y", 0)))], serial=serial, timeout=10)
            self.send_json({"ok": True})
            return
        if u.path == "/api/key":
            keys = {"home": "3", "back": "4", "menu": "82", "volup": "24", "voldown": "25", "power": "26", "recents": "187", "wake": "224", "sleep": "223", "up": "19", "down": "20", "left": "21", "right": "22", "confirm": "23"}
            code = keys.get(body.get("key", ""), body.get("key", ""))
            run_adb(["shell", "input", "keyevent", str(code)], serial=serial, timeout=10)
            self.send_json({"ok": True})
            return
        if u.path == "/api/swipe":
            run_adb(["shell", "input", "swipe", str(int(body.get("x1", 0))), str(int(body.get("y1", 0))), str(int(body.get("x2", 0))), str(int(body.get("y2", 0))), str(int(body.get("dur", 300)))], serial=serial, timeout=10)
            self.send_json({"ok": True})
            return
        if u.path == "/api/text":
            t = (body.get("text") or "")
            if not t:
                self.send_json({"ok": False, "msg": "空文本"})
                return
            t = t.replace(" ", "%s").replace("&", "\\&").replace("<", "\\<").replace(">", "\\>").replace("|", "\\|").replace("(", "\\(").replace(")", "\\)").replace(";", "\\;").replace("'", "\\'").replace('"', '\\"')
            run_adb(["shell", "input", "text", t], serial=serial, timeout=10)
            self.send_json({"ok": True})
            return
        if u.path == "/api/uninstall":
            pkg = (body.get("pkg") or "").strip()
            if not re.match(r"^[\w.]+$", pkg):
                self.send_json({"ok": False, "msg": "包名不合法"})
                return
            code, out, _ = run_adb(["shell", "pm", "uninstall", pkg], serial=serial, timeout=30)
            self.send_json({"ok": "Success" in (out or ""), "msg": (out or "").strip()[:200]})
            return
        if u.path in ("/api/install", "/api/push"):
            name = unquote(self.headers.get("X-Filename") or "file.bin")
            serial = self.headers.get("X-Serial") or serial
            if not require_serial(serial):
                self.send_json({"ok": False, "msg": "设备不在线"}, 503)
                return
            data = self.body_raw()
            os.makedirs(os.path.join(ROOT, "tmp"), exist_ok=True)
            tmp = os.path.join(ROOT, "tmp", "up_" + str(int(time.time() * 1000)) + "_" + os.path.basename(name))
            open(tmp, "wb").write(data)
            if u.path == "/api/install":
                code, out, _ = run_adb(["install", "-r", "-g", tmp], serial=serial, timeout=180)
                os.remove(tmp)
                self.send_json({"ok": "Success" in (out or ""), "msg": (out or "").strip()[:300]})
            else:
                remote = self.headers.get("X-Remote") or "/sdcard/Download/"
                remote = remote if remote.endswith("/") else remote + "/"
                code, out, _ = run_adb(["push", tmp, remote + os.path.basename(name)], serial=serial, timeout=300)
                os.remove(tmp)
                self.send_json({"ok": code == 0, "msg": (out or "").strip()[:300] or ("已推送到 " + remote + os.path.basename(name))})
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
