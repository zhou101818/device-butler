package com.zhou101818.butler;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AdbBridgePlugin.class);
        registerPlugin(ShizukuBridgePlugin.class);
        super.onCreate(savedInstanceState);
        // 状态栏留给系统：网页内容从状态栏下方开始布局
        android.view.Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        // 让 WebView 自己不再延伸到系统栏
        View content = window.getDecorView();
        content.setFitsSystemWindows(true);
        // 顶部安全区常量注入（供 CSS 使用；Bridge 初始化后 WebView 才可用）
        getWindow().getDecorView().post(() -> {
            WebView web = getBridge() != null ? getBridge().getWebView() : null;
            if (web == null) return;
            View.OnApplyWindowInsetsListener lis = (v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                final String js = "window.__SAFETY__ = {top:" + top + ",bottom:" + bottom + "};"
                        + "document.documentElement.style.setProperty('--sat'," + top + "px);"
                        + "document.documentElement.style.setProperty('--sab'," + bottom + "px);"
                        + "if (window.__applySafeInsets) window.__applySafeInsets();";
                web.post(() -> web.evaluateJavascript(js, null));
                return insets;
            };
            web.setOnApplyWindowInsetsListener(lis);
            web.requestApplyInsets();
        });
    }
}