package com.zhou101818.butler;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AdbBridgePlugin.class);
        registerPlugin(ShizukuBridgePlugin.class);
        registerPlugin(UIBridgePlugin.class);
        super.onCreate(savedInstanceState);
        // 把系统栏高度注入 WebView CSS 变量（不依赖 fitsSystemWindows 的最终表现）
        getWindow().getDecorView().post(() -> {
            final WebView web = getBridge() != null ? getBridge().getWebView() : null;
            if (web == null) return;
            applyInsets(web);
            web.getViewTreeObserver().addOnGlobalLayoutListener(() -> applyInsets(web));
        });
    }

    private void applyInsets(WebView web) {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
            int top = r.top;
            if (top > 0) {
                final String js = "document.documentElement.style.setProperty('--sat','" + top + "px');"
                        + "if(window.__applySafeInsets)window.__applySafeInsets();";
                web.post(() -> web.evaluateJavascript(js, null));
            }
        } catch (Throwable ignored) {}
    }
}

/** UI 桥：打开外部链接（安卓 WebView 拦 window.open） */
@CapacitorPlugin(name = "UIBridge")
class UIBridgePlugin extends Plugin {
    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url", "");
        if (url == null || url.isEmpty()) { call.reject("空地址"); return; }
        if (!(url.startsWith("https://") || url.startsWith("http://"))) { call.reject("仅支持 http(s)"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("无法打开：" + e.getMessage());
        }
    }

    @PluginMethod
    public void insets(PluginCall call) {
        android.graphics.Rect r = new android.graphics.Rect();
        if (getActivity() != null) {
            getActivity().getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
        }
        JSObject ret = new JSObject();
        ret.put("top", r.top);
        call.resolve(ret);
    }
}