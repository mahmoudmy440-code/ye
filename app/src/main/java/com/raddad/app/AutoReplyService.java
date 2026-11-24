package com.raddad.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutoReplyService extends NotificationListenerService {

    private static final Map<String, Long> lastReplyMap = new HashMap<>();
    // ذاكرة محادثة لكل عميل حتى يفهم الذكاء الاصطناعي سياق الطلب متعدد الرسائل
    private static final Map<String, List<String>> conversationMemory = new HashMap<>();
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        final SharedPreferences prefs = getSharedPreferences("RaddadPrefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("service_enabled", true)) {
            return;
        }

        final String packageName = sbn.getPackageName();
        String allowedAppsStr = prefs.getString("allowed_apps", "");
        if (allowedAppsStr.trim().isEmpty()) return;

        Set<String> allowedApps = new HashSet<>(Arrays.asList(allowedAppsStr.split(",")));
        if (!allowedApps.contains(packageName)) {
            return;
        }

        final Notification notification = sbn.getNotification();
        if (notification == null || (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
            return;
        }

        Bundle extras = notification.extras;
        if (extras == null) return;

        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (titleCs == null || textCs == null) return;

        final String sender = titleCs.toString().trim();
        final String incomingMessage = textCs.toString().trim();
        if (sender.isEmpty() || incomingMessage.isEmpty()) return;

        int cooldownSeconds = prefs.getInt("cooldown_seconds", 45);
        final String senderKey = packageName + "::" + sender;
        final long now = System.currentTimeMillis();
        Long lastTime = lastReplyMap.get(senderKey);
        if (lastTime != null && (now - lastTime) < (cooldownSeconds * 1000L)) {
            return;
        }

        lastReplyMap.put(senderKey, now);

        executor.execute(() -> {
            String appLabel = getAppName(packageName);
            appendHistory(senderKey, "العميل (" + sender + "): " + incomingMessage);

            String replyToSend = processMessageAndDetectOrder(prefs, packageName, appLabel, senderKey, sender, incomingMessage);
            if (replyToSend != null && !replyToSend.trim().isEmpty()) {
                boolean sent = sendDirectReply(notification, replyToSend.trim());
                if (sent) {
                    appendHistory(senderKey, "ردك: " + replyToSend.trim());
                    lastReplyMap.put(senderKey, System.currentTimeMillis());
                } else {
                    lastReplyMap.remove(senderKey);
                }
            } else {
                lastReplyMap.remove(senderKey);
            }
        });
    }

    private void appendHistory(String senderKey, String line) {
        synchronized (conversationMemory) {
            List<String> list = conversationMemory.get(senderKey);
            if (list == null) {
                list = new ArrayList<>();
                conversationMemory.put(senderKey, list);
            }
            list.add(line);
            if (list.size() > 10) {
                list.remove(0);
            }
        }
    }

    private String getHistoryText(String senderKey) {
        synchronized (conversationMemory) {
            List<String> list = conversationMemory.get(senderKey);
            if (list == null || list.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String s : list) {
                sb.append(s).append("
");
            }
            return sb.toString();
        }
    }

    private String getAppName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private String processMessageAndDetectOrder(
            SharedPreferences prefs,
            String packageName,
            String appLabel,
            String senderKey,
            String sender,
            String incomingMessage
    ) {
        boolean aiEnabled = prefs.getBoolean("ai_enabled", true);
        String aiApiKey = prefs.getString("ai_api_key", "").trim();
        String aiModel = prefs.getString("ai_model", "gemini-2.5-flash").trim();
        String aiSystemPrompt = prefs.getString("ai_system_prompt", "").trim();
        String aiMode = prefs.getString("ai_mode", "primary");

        String keywordMatch = findMatchingRuleReply(prefs.getString("custom_rules", "[]"), incomingMessage);

        if ("fallback".equals(aiMode) && keywordMatch != null) {
            return keywordMatch;
        }

        if (aiEnabled && !aiApiKey.isEmpty()) {
            AIAnalysisResult aiResult = callGeminiStructured(
                    aiApiKey,
                    aiModel,
                    aiSystemPrompt,
                    appLabel,
                    sender,
                    getHistoryText(senderKey),
                    incomingMessage
            );

            if (aiResult != null && aiResult.reply != null && !aiResult.reply.isEmpty()) {
                // إذا اكتشف الذكاء الاصطناعي أن العميل أكد أوردر حقيقي مكتمل البيانات
                if (aiResult.isRealOrder) {
                    handleConfirmedOrder(prefs, appLabel, sender, aiResult);
                }
                return aiResult.reply;
            }
        }

        if (keywordMatch != null) {
            return keywordMatch;
        }

        return prefs.getString("default_reply", "").trim();
    }

    private void handleConfirmedOrder(SharedPreferences prefs, String appLabel, String sender, AIAnalysisResult order) {
        try {
            String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());

            JSONObject orderRecord = new JSONObject();
            orderRecord.put("time", timeStr);
            orderRecord.put("app", appLabel);
            orderRecord.put("senderAccount", sender);
            orderRecord.put("customerName", order.customerName);
            orderRecord.put("phone", order.customerPhone);
            orderRecord.put("address", order.customerAddress);
            orderRecord.put("items", order.orderItems);
            orderRecord.put("notes", order.orderNotes);

            // حفظ الطلب في سجل التطبيق
            String existingLog = prefs.getString("orders_log", "[]");
            JSONArray logArr = new JSONArray(existingLog);
            JSONArray updatedArr = new JSONArray();
            updatedArr.put(orderRecord);
            for (int i = 0; i < Math.min(logArr.length(), 49); i++) {
                updatedArr.put(logArr.get(i));
            }
            prefs.edit().putString("orders_log", updatedArr.toString()).apply();

            // تجهيز رسالة بيانات العميل لإرسالها لرقم واتساب الإدارة
            StringBuilder msg = new StringBuilder();
            msg.append("🛒 *طلب جديد مؤكد (Raddad AI)*
");
            msg.append("📱 *التطبيق:* ").append(appLabel).append("
");
            msg.append("👤 *حساب المرسل:* ").append(sender).append("
");
            msg.append("📝 *اسم العميل:* ").append(order.customerName).append("
");
            msg.append("📞 *رقم الهاتف:* ").append(order.customerPhone).append("
");
            msg.append("📍 *العنوان:* ").append(order.customerAddress).append("
");
            msg.append("📦 *تفاصيل الأوردر:* ").append(order.orderItems).append("
");
            if (!order.orderNotes.isEmpty()) {
                msg.append("🗒 *ملاحظات:* ").append(order.orderNotes).append("
");
            }
            msg.append("⏰ *الوقت:* ").append(timeStr);

            boolean autoForward = prefs.getBoolean("auto_forward_order", true);
            String adminPhone = prefs.getString("admin_whatsapp_number", "").replaceAll("[^0-9]", "");
            String adminPkg = prefs.getString("admin_whatsapp_pkg", "com.whatsapp");

            if (autoForward && !adminPhone.isEmpty()) {
                String encoded = URLEncoder.encode(msg.toString(), StandardCharsets.UTF_8.toString());
                Intent waIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + adminPhone + "&text=" + encoded));
                waIntent.setPackage(adminPkg);
                waIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // تفعيل الضغط التلقائي على زر الإرسال في واتساب
                WhatsAppForwarderAccessibility.armAutoSend();
                startActivity(waIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private AIAnalysisResult callGeminiStructured(
            String apiKey,
            String model,
            String userBusinessPrompt,
            String appLabel,
            String sender,
            String history,
            String currentMessage
    ) {
        HttpURLConnection conn = null;
        try {
            String cleanModel = model.isEmpty() ? "gemini-2.5-flash" : model;
            String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + cleanModel + ":generateContent?key=" + apiKey;
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setDoOutput(true);

            String fullSystemInstruction =
                "تعليمات النشاط التجاري:
" + userBusinessPrompt + "

" +
                "مهمتك المزدوجة:
" +
                "1. الرد على العميل بشكل طبيعي ومفيد ومختصر باللغة العربية.
" +
                "2. تحليل المحادثة لتحديد هل العميل يقوم بعمل (أوردر حقيقي ومؤكد) أم مجرد استفسار عادي.
" +
                "يُعتبر الأوردر حقيقياً ومؤكداً (is_real_order = true) فقط عندما يبدي العميل رغبة صريحة في الشراء/الحجز ويذكر تفاصيل الطلب مع بيانات التواصل (مثل الاسم أو رقم الهاتف أو العنوان). إذا كان مجرد سؤال عن السعر أو التفاصيل أو لم يذكر بياناته بعد، اجعل is_real_order = false واطلب منه بلطف إرسال الاسم ورقم الهاتف والعنوان وتفاصيل الطلب لتأكيد الأوردر.

" +
                "يجب أن يكون ردك بصيغة JSON صالحة فقط بدون أي نص خارجها، بهذا الهيكل الدقيق:
" +
                "{
" +
                "  "reply": "نص الرد الذي سيصل للعميل",
" +
                "  "is_real_order": true or false,
" +
                "  "customer_name": "اسم العميل إن وجد أو اسم حسابه",
" +
                "  "customer_phone": "رقم هاتف العميل إن وجد أو غير محدد",
" +
                "  "customer_address": "عنوان العميل إن وجد أو غير محدد",
" +
                "  "order_items": "المنتجات أو الخدمة المطلوبة",
" +
                "  "order_notes": "أي ملاحظات إضافية"
" +
                "}";

            JSONObject requestBody = new JSONObject();

            JSONObject sysInst = new JSONObject();
            JSONArray sysParts = new JSONArray();
            JSONObject sysText = new JSONObject();
            sysText.put("text", fullSystemInstruction);
            sysParts.put(sysText);
            sysInst.put("parts", sysParts);
            requestBody.put("system_instruction", sysInst);

            JSONObject genConfig = new JSONObject();
            genConfig.put("responseMimeType", "application/json");
            requestBody.put("generationConfig", genConfig);

            JSONArray contents = new JSONArray();
            JSONObject userContent = new JSONObject();
            userContent.put("role", "user");
            JSONArray userParts = new JSONArray();
            JSONObject userText = new JSONObject();
            userText.put("text",
                "التطبيق: " + appLabel + "
" +
                "حساب المرسل: " + sender + "
" +
                "سجل المحادثة الأخيرة:
" + history + "
" +
                "الرسالة الحالية: " + currentMessage
            );
            userParts.put(userText);
            userContent.put("parts", userParts);
            contents.put(userContent);
            requestBody.put("contents", contents);

            byte[] input = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(input, 0, input.length);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();

                JSONObject jsonResponse = new JSONObject(response.toString());
                JSONArray candidates = jsonResponse.optJSONArray("candidates");
                if (candidates != null && candidates.length() > 0) {
                    JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                    if (content != null) {
                        JSONArray parts = content.optJSONArray("parts");
                        if (parts != null && parts.length() > 0) {
                            String rawJson = parts.getJSONObject(0).optString("text", "").trim();
                            rawJson = rawJson.replace("```json", "").replace("```", "").trim();
                            JSONObject parsed = new JSONObject(rawJson);

                            AIAnalysisResult res = new AIAnalysisResult();
                            res.reply = parsed.optString("reply", "").trim();
                            res.isRealOrder = parsed.optBoolean("is_real_order", false);
                            res.customerName = parsed.optString("customer_name", sender).trim();
                            res.customerPhone = parsed.optString("customer_phone", "غير محدد").trim();
                            res.customerAddress = parsed.optString("customer_address", "غير محدد").trim();
                            res.orderItems = parsed.optString("order_items", currentMessage).trim();
                            res.orderNotes = parsed.optString("order_notes", "").trim();
                            return res;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private String findMatchingRuleReply(String rulesJson, String message) {
        try {
            JSONArray array = new JSONArray(rulesJson);
            String lowerMsg = message.toLowerCase();
            for (int i = 0; i < array.length(); i++) {
                JSONObject rule = array.getJSONObject(i);
                String keyword = rule.optString("keyword", "").trim().toLowerCase();
                String reply = rule.optString("reply", "").trim();
                if (!keyword.isEmpty() && !reply.isEmpty() && lowerMsg.contains(keyword)) {
                    return reply;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean sendDirectReply(Notification notification, String replyMessage) {
        if (notification.actions == null) return false;

        for (Notification.Action action : notification.actions) {
            if (action.getRemoteInputs() != null) {
                for (RemoteInput remoteInput : action.getRemoteInputs()) {
                    if (remoteInput.getAllowFreeFormInput()) {
                        Intent intent = new Intent();
                        Bundle bundle = new Bundle();
                        bundle.putCharSequence(remoteInput.getResultKey(), replyMessage);
                        RemoteInput.addResultsToIntent(action.getRemoteInputs(), intent, bundle);

                        try {
                            action.actionIntent.send(this, 0, intent);
                            return true;
                        } catch (PendingIntent.CanceledException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }
        return false;
    }

    private static class AIAnalysisResult {
        String reply = "";
        boolean isRealOrder = false;
        String customerName = "";
        String customerPhone = "";
        String customerAddress = "";
        String orderItems = "";
        String orderNotes = "";
    }
}
