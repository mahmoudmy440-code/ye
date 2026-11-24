package com.raddad.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class WhatsAppForwarderAccessibility extends AccessibilityService {

    public static volatile boolean pendingAutoSend = false;
    public static volatile long pendingTimestamp = 0L;

    public static void armAutoSend() {
        pendingAutoSend = true;
        pendingTimestamp = System.currentTimeMillis();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!pendingAutoSend) return;

        // إلغاء المهمة تلقائياً إذا مر أكثر من 15 ثانية
        if (System.currentTimeMillis() - pendingTimestamp > 15000L) {
            pendingAutoSend = false;
            return;
        }

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        // البحث عن زر الإرسال في واتساب أو واتساب للأعمال
        String[] sendButtonIds = {
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send"
        };

        for (String viewId : sendButtonIds) {
            List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node.isClickable() && node.isEnabled()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        pendingAutoSend = false;
                        return;
                    }
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        pendingAutoSend = false;
    }
}
