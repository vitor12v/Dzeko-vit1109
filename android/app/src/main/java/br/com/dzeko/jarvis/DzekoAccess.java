package br.com.dzeko.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * "Dzeko · controle do celular".
 * It only receives events from WhatsApp (see res/xml/dzeko_access.xml), so it can press
 * "Enviar" on a message the user asked Dzeko to send. Banks, gallery and every other app
 * are never visible to it. It also performs the screen actions the user asks for.
 */
public class DzekoAccess extends AccessibilityService {

    static volatile DzekoAccess instance;
    private static volatile long sendUntil = 0;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Arms one automatic tap on WhatsApp's send button, valid for a few seconds. */
    static void armSend(long ms) {
        sendUntil = System.currentTimeMillis() + ms;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (System.currentTimeMillis() > sendUntil || event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !pkg.toString().startsWith("com.whatsapp")) return;
        main.postDelayed(this::tryPressSend, 350);
    }

    private void tryPressSend() {
        if (System.currentTimeMillis() > sendUntil) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !pkg.toString().startsWith("com.whatsapp")) return;
        AccessibilityNodeInfo send = find(root, pkg + ":id/send");
        if (send == null) send = findByDescription(root);
        if (send != null && send.isEnabled() && send.isVisibleToUser()) {
            AccessibilityNodeInfo target = send;
            while (target != null && !target.isClickable()) target = target.getParent();
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                sendUntil = 0;
                // back to Dzeko so it can keep talking
                main.postDelayed(() -> {
                    Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    try { startActivity(i); } catch (Exception ignored) { }
                }, 900);
            }
        }
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String viewId) {
        try {
            List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(viewId);
            return l == null || l.isEmpty() ? null : l.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static AccessibilityNodeInfo findByDescription(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence d = node.getContentDescription();
        if (d != null) {
            String s = d.toString().toLowerCase(Locale.ROOT).trim();
            if (s.equals("enviar") || s.equals("send")) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findByDescription(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    /** voltar, inicio, recentes, notificacoes, ajustes rapidos, bloquear, print */
    boolean global(String what) {
        String w = what == null ? "" : what.toLowerCase(Locale.ROOT);
        int action;
        if (w.contains("volt") || w.contains("back")) action = GLOBAL_ACTION_BACK;
        else if (w.contains("inic") || w.contains("home")) action = GLOBAL_ACTION_HOME;
        else if (w.contains("recent")) action = GLOBAL_ACTION_RECENTS;
        else if (w.contains("notific")) action = GLOBAL_ACTION_NOTIFICATIONS;
        else if (w.contains("rapid")) action = GLOBAL_ACTION_QUICK_SETTINGS;
        else if ((w.contains("bloq") || w.contains("trav")) && Build.VERSION.SDK_INT >= 28) action = GLOBAL_ACTION_LOCK_SCREEN;
        else if ((w.contains("print") || w.contains("captur")) && Build.VERSION.SDK_INT >= 28) action = GLOBAL_ACTION_TAKE_SCREENSHOT;
        else return false;
        return performGlobalAction(action);
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
