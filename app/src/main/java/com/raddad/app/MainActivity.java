package com.raddad.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.evaluateJavascript("if(typeof refreshSystemStatus === 'function') refreshSystemStatus();", null);
        }
    }

    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public boolean isNotificationServiceEnabled() {
            String pkgName = mContext.getPackageName();
            final String flat = Settings.Secure.getString(mContext.getContentResolver(), "enabled_notification_listeners");
            if (!TextUtils.isEmpty(flat)) {
                final String[] names = flat.split(":");
                for (String name : names) {
                    final ComponentName cn = ComponentName.unflattenFromString(name);
                    if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
            return false;
        }

        @JavascriptInterface
        public boolean isAccessibilityServiceEnabled() {
            String expectedComponentName = new ComponentName(mContext, WhatsAppForwarderAccessibility.class).flattenToString();
            String enabledServicesSetting = Settings.Secure.getString(mContext.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServicesSetting == null) return false;
            TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
            colonSplitter.setString(enabledServicesSetting);
            while (colonSplitter.hasNext()) {
                String componentName = colonSplitter.next();
                if (componentName.equalsIgnoreCase(expectedComponentName)) {
                    return true;
                }
            }
            return false;
        }

        // جلب جميع التطبيقات المثبتة فعلياً على الهاتف مع أسمائها وأيقوناتها
        @JavascriptInterface
        public String getInstalledApps() {
            JSONArray result = new JSONArray();
            try {
                PackageManager pm = mContext.getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                List<AppItem> userApps = new ArrayList<>();

                String myPkg = mContext.getPackageName();
                for (ApplicationInfo appInfo : apps) {
                    if (appInfo.packageName.equals(myPkg)) continue;
                    // إظهار التطبيقات التي لها واجهة تشغيل (Launcher)
                    if (pm.getLaunchIntentForPackage(appInfo.packageName) != null) {
                        String label = pm.getApplicationLabel(appInfo).toString();
                        userApps.add(new AppItem(label, appInfo.packageName, appInfo));
                    }
                }

                Collections.sort(userApps, new Comparator<AppItem>() {
                    @Override
                    public int compare(AppItem a, AppItem b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });

                for (AppItem item : userApps) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", item.label);
                    obj.put("package", item.packageName);
                    obj.put("icon", getIconBase64(pm, item.info));
                    result.put(obj);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return result.toString();
        }

        private String getIconBase64(PackageManager pm, ApplicationInfo info) {
            try {
                Drawable drawable = pm.getApplicationIcon(info);
                Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 80, baos);
                return "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void saveSettings(
                boolean enabled,
                String appsCommaSeparated,
                String defaultReply,
                String rulesJson,
                int cooldownSeconds,
                boolean aiEnabled,
                String aiApiKey,
                String aiModel,
                String aiSystemPrompt,
                String aiMode,
                String adminWhatsAppNumber,
                String adminWhatsAppPkg,
                boolean autoForwardOrder
        ) {
            SharedPreferences prefs = mContext.getSharedPreferences("RaddadPrefs", Context.MODE_PRIVATE);
            prefs.edit()
                 .putBoolean("service_enabled", enabled)
                 .putString("allowed_apps", appsCommaSeparated)
                 .putString("default_reply", defaultReply)
                 .putString("custom_rules", rulesJson)
                 .putInt("cooldown_seconds", cooldownSeconds)
                 .putBoolean("ai_enabled", aiEnabled)
                 .putString("ai_api_key", aiApiKey)
                 .putString("ai_model", aiModel)
                 .putString("ai_system_prompt", aiSystemPrompt)
                 .putString("ai_mode", aiMode)
                 .putString("admin_whatsapp_number", adminWhatsAppNumber)
                 .putString("admin_whatsapp_pkg", adminWhatsAppPkg)
                 .putBoolean("auto_forward_order", autoForwardOrder)
                 .apply();
        }

        @JavascriptInterface
        public String loadSettings() {
            SharedPreferences prefs = mContext.getSharedPreferences("RaddadPrefs", Context.MODE_PRIVATE);
            try {
                JSONObject obj = new JSONObject();
                obj.put("enabled", prefs.getBoolean("service_enabled", true));
                obj.put("apps", prefs.getString("allowed_apps", "com.whatsapp,com.whatsapp.w4b"));
                obj.put("defaultReply", prefs.getString("default_reply", "أهلاً بك! تم استلام رسالتك وسيتم الرد عليك في أقرب وقت."));
                obj.put("rules", prefs.getString("custom_rules", "[]"));
                obj.put("cooldown", prefs.getInt("cooldown_seconds", 45));
                obj.put("aiEnabled", prefs.getBoolean("ai_enabled", true));
                obj.put("aiApiKey", prefs.getString("ai_api_key", ""));
                obj.put("aiModel", prefs.getString("ai_model", "gemini-2.5-flash"));
                obj.put("aiSystemPrompt", prefs.getString("ai_system_prompt", "أنت موظف مبيعات وخدمة عملاء ذكي. ساعد العميل في اختيار المنتج، وإذا أراد عمل أوردر اطلب منه اسمه بالكامل ورقم هاتفه وعنوانه بالتفصيل وتفاصيل الطلب."));
                obj.put("aiMode", prefs.getString("ai_mode", "primary"));
                obj.put("adminWhatsAppNumber", prefs.getString("admin_whatsapp_number", ""));
                obj.put("adminWhatsAppPkg", prefs.getString("admin_whatsapp_pkg", "com.whatsapp"));
                obj.put("autoForwardOrder", prefs.getBoolean("auto_forward_order", true));
                return obj.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String getOrdersLog() {
            SharedPreferences prefs = mContext.getSharedPreferences("RaddadPrefs", Context.MODE_PRIVATE);
            return prefs.getString("orders_log", "[]");
        }

        @JavascriptInterface
        public void clearOrdersLog() {
            SharedPreferences prefs = mContext.getSharedPreferences("RaddadPrefs", Context.MODE_PRIVATE);
            prefs.edit().putString("orders_log", "[]").apply();
        }

        @JavascriptInterface
        public void sendOrderManuallyToWhatsApp(String orderSummaryText) {
            SharedPreferences prefs = mContext.getSharedPreferences("RaddadPrefs", Context.MODE_PRIVATE);
            String phone = prefs.getString("admin_whatsapp_number", "").replaceAll("[^0-9]", "");
            String pkg = prefs.getString("admin_whatsapp_pkg", "com.whatsapp");
            if (phone.isEmpty()) return;

            try {
                String encoded = URLEncoder.encode(orderSummaryText, StandardCharsets.UTF_8.toString());
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + phone + "&text=" + encoded));
                intent.setPackage(pkg);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                WhatsAppForwarderAccessibility.armAutoSend();
                mContext.startActivity(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static class AppItem {
        String label;
        String packageName;
        ApplicationInfo info;

        AppItem(String label, String packageName, ApplicationInfo info) {
            this.label = label;
            this.packageName = packageName;
            this.info = info;
        }
    }
}
