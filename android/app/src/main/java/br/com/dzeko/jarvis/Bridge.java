package br.com.dzeko.jarvis;

import android.Manifest;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import android.os.Handler;
import android.os.Looper;

/**
 * Everything the assistant can do on the phone. Each method is called from the web app
 * as window.DzekoAndroid.method(...). Methods run directly, with no tap from the user.
 */
public class Bridge {

    private final MainActivity act;
    private final Context ctx;

    private SpeechRecognizer recognizer;
    private int srId = 0;
    private boolean srEnded = true;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final List<String[]> ttsQueue = new ArrayList<>();

    private String pendingCall;
    private String[] pendingWhats;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean srMuted = false;
    private int srRetry = 0;
    private Object[] pendingEvent;

    Bridge(MainActivity activity) {
        act = activity;
        ctx = activity.getApplicationContext();
        tts = new TextToSpeech(ctx, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setLanguage(new Locale("pt", "BR"));
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { act.emit("tts", "end", "{\"id\":" + JSONObject.quote(id) + "}"); }
                    @Override public void onError(String id) { act.emit("tts", "error", "{\"id\":" + JSONObject.quote(id) + "}"); }
                    @Override public void onStop(String id, boolean interrupted) { act.emit("tts", "end", "{\"id\":" + JSONObject.quote(id) + "}"); }
                });
                synchronized (ttsQueue) {
                    for (String[] q : ttsQueue) ttsSpeak(q[0], q[1], Float.parseFloat(q[2]), Float.parseFloat(q[3]));
                    ttsQueue.clear();
                }
            }
        });
    }

    // ---------------------------------------------------------------- info

    @JavascriptInterface
    public String info() {
        try {
            JSONObject o = new JSONObject();
            o.put("app", "Dzeko Android");
            o.put("version", ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("recognizer", SpeechRecognizer.isRecognitionAvailable(ctx));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // ---------------------------------------------------------------- clock

    /** Creates a real alarm in the phone's Clock app, without opening it. */
    @JavascriptInterface
    public boolean setAlarm(int hour, int minute, String label) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label == null ? "Dzeko" : label)
                .putExtra(AlarmClock.EXTRA_VIBRATE, true)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        return start(i);
    }

    /** Starts a real countdown timer in the phone's Clock app. */
    @JavascriptInterface
    public boolean setTimer(int seconds, String label) {
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, Math.max(1, seconds))
                .putExtra(AlarmClock.EXTRA_MESSAGE, label == null ? "Dzeko" : label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        return start(i);
    }

    @JavascriptInterface
    public boolean showAlarms() {
        return start(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
    }

    // ---------------------------------------------------------------- safety: no banks, no gallery

    private static final String[] BLOCK_PKG = {"bank", "banco", "nubank", "com.nu.", "itau", "bradesco", "santander", "caixa", "br.com.bb", "intermedium",
            "bancointer", "picpay", "mercadopago", "pagbank", "uol.ps", "pagseguro", "sicoob", "sicredi", "neon", "btg", "binance", "paypal", "c6bank",
            "nubank", "gallery", "apps.photos", "huawei.photos", "miui.gallery", "photos.", "wallet", "carteira"};
    private static final String[] BLOCK_WORD = {"banco", "bank", "nubank", "itau", "bradesco", "santander", "caixa", "picpay", "pagbank", "sicoob", "sicredi",
            "galeria", "gallery", "fotos", "photos", "carteira", "wallet", "binance", "paypal"};

    static boolean blocked(String pkg, String label) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        for (String b : BLOCK_PKG) if (p.contains(b)) return true;
        String l = " " + norm(label) + " ";
        for (String w : BLOCK_WORD) if (l.contains(" " + w + " ")) return true;
        return false;
    }

    // ---------------------------------------------------------------- apps & links

    /** Opens an installed app by its name ("WhatsApp", "Instagram", "Calculadora"...). Returns its real name, or "" if not found. */
    @JavascriptInterface
    public String openApp(String name) {
        String want = norm(name);
        if (want.isEmpty()) return "";
        PackageManager pm = ctx.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        ResolveInfo best = null;
        int bestScore = 0;
        if (blocked("", name)) return "__blocked__";
        for (ResolveInfo r : apps) {
            String label = norm(String.valueOf(r.loadLabel(pm)));
            String pkg = r.activityInfo.packageName.toLowerCase(Locale.ROOT);
            int score = 0;
            if (label.equals(want)) score = 100;
            else if (label.startsWith(want) || want.startsWith(label)) score = 80;
            else if (label.contains(want)) score = 60;
            else if (pkg.contains(want.replace(" ", ""))) score = 40;
            if (score > bestScore) { bestScore = score; best = r; }
        }
        if (best == null) return "";
        if (blocked(best.activityInfo.packageName, String.valueOf(best.loadLabel(pm)))) return "__blocked__";
        Intent i = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (i == null) return "";
        return start(i) ? String.valueOf(best.loadLabel(pm)) : "";
    }

    @JavascriptInterface
    public boolean openUrl(String url) {
        try {
            return openUri(Uri.parse(url));
        } catch (Exception e) {
            return false;
        }
    }

    boolean openUri(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("intent")) return false; // never run raw intents coming from web pages
        String host = u.getHost() == null ? "" : u.getHost();
        if (blocked(host, "") || scheme.contains("bank")) return false;
        Intent i = new Intent(Intent.ACTION_VIEW, u);
        if (scheme.equals("mailto")) i = new Intent(Intent.ACTION_SENDTO, u);
        return start(i);
    }

    /** Plays music straight away (Spotify by default), the same way Google Assistant does. */
    @JavascriptInterface
    public boolean playMusic(String query, String app) {
        String pkg = "youtube".equalsIgnoreCase(app) ? "com.google.android.apps.youtube.music" : "com.spotify.music";
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(SearchManager.QUERY, query == null ? "" : query);
        if (installed(pkg)) {
            i.setPackage(pkg);
            if (start(i)) {
                // some versions open the search and wait: a "play" key starts it (play never pauses)
                main.postDelayed(() -> mediaKey("play"), 3500);
                return true;
            }
        }
        if (installed("com.spotify.music") && !"youtube".equalsIgnoreCase(app)) {
            return start(new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))));
        }
        return start(i) || openUrl("https://music.youtube.com/search?q=" + Uri.encode(query));
    }

    /** Controls whatever is playing (Spotify, YouTube Music...): play, pause, play_pause, next, previous. */
    @JavascriptInterface
    public boolean mediaKey(String key) {
        int code;
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        switch (k) {
            case "next": code = android.view.KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": code = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "pause": code = android.view.KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "play": code = android.view.KeyEvent.KEYCODE_MEDIA_PLAY; break;
            default: code = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
        }
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Starts turn-by-turn navigation in Google Maps. mode: carro, a pé, bicicleta, moto. */
    @JavascriptInterface
    public boolean navigate(String destination, String mode) {
        String m = "d";
        String md = mode == null ? "" : mode.toLowerCase(Locale.ROOT);
        if (md.contains("pé") || md.contains("pe") || md.contains("andando") || md.contains("caminh")) m = "w";
        else if (md.contains("bici") || md.contains("bike")) m = "b";
        else if (md.contains("moto")) m = "l";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=" + m));
        if (installed("com.google.android.apps.maps")) i.setPackage("com.google.android.apps.maps");
        if (start(i)) return true;
        return start(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(destination))));
    }

    // ---------------------------------------------------------------- phone call

    /** Calls a number right away. The first time, Android asks for the "Phone" permission. */
    @JavascriptInterface
    public String call(String number) {
        String digits = number == null ? "" : number.replaceAll("[^0-9+]", "");
        if (digits.replace("+", "").length() < 3) {
            // a name: look it up in the contacts
            if (!act.has(Manifest.permission.READ_CONTACTS)) {
                pendingCall = "name:" + number;
                act.ask(new String[]{Manifest.permission.READ_CONTACTS}, MainActivity.REQ_CONTACTS);
                return "asked";
            }
            String found = findNumber(number);
            if (found == null) return "notfound";
            digits = found;
        }
        if (act.has(Manifest.permission.CALL_PHONE)) {
            return start(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + digits))) ? "calling" : "failed";
        }
        pendingCall = digits;
        act.ask(new String[]{Manifest.permission.CALL_PHONE}, MainActivity.REQ_CALL);
        return "asked";
    }

    void onContactsPermission(boolean ok) {
        if (pendingCall != null && pendingCall.startsWith("name:")) {
            String name = pendingCall.substring(5);
            pendingCall = null;
            if (ok) call(name);
        }
        if (pendingWhats != null) {
            String[] w = pendingWhats;
            pendingWhats = null;
            whatsapp(ok ? w[0] : "", w[1]);
        }
        act.emit("perm", "contacts", "{\"ok\":" + ok + "}");
    }

    void onCallPermission(boolean ok) {
        if (pendingCall == null) return;
        String n = pendingCall;
        pendingCall = null;
        start(new Intent(ok ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + n)));
    }

    // ---------------------------------------------------------------- WhatsApp

    /** Opens the chat with the message written. With the control service on, it is sent by itself.
     *  Returns: sending, opened, notfound, asked, failed. */
    @JavascriptInterface
    public String whatsapp(String contact, String message) {
        String c = contact == null ? "" : contact.trim();
        String num = "";
        if (!c.isEmpty()) {
            String d = c.replaceAll("[^0-9]", "");
            if (d.length() >= 8) num = d;
            else {
                if (!act.has(Manifest.permission.READ_CONTACTS)) {
                    pendingWhats = new String[]{c, message == null ? "" : message};
                    act.ask(new String[]{Manifest.permission.READ_CONTACTS}, MainActivity.REQ_CONTACTS);
                    return "asked";
                }
                String f = findNumber(c);
                if (f == null) return "notfound";
                num = f.replaceAll("[^0-9]", "");
            }
            if (num.startsWith("0")) num = num.replaceFirst("^0+", "");
            if (num.length() == 10 || num.length() == 11) num = "55" + num;
        }
        String url = "https://api.whatsapp.com/send?" + (num.isEmpty() ? "" : "phone=" + num + "&") + "text=" + Uri.encode(message == null ? "" : message);
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (installed("com.whatsapp")) i.setPackage("com.whatsapp");
        else if (installed("com.whatsapp.w4b")) i.setPackage("com.whatsapp.w4b");
        if (!start(i)) return "failed";
        return "opened";
    }

    /** Best phone number for a contact name ("Mãe", "João Silva"), or null. */
    private String findNumber(String name) {
        String want = norm(name);
        if (want.isEmpty()) return null;
        String best = null;
        int bestScore = 0;
        String[] proj = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY};
        try (Cursor c = ctx.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                String n = norm(c.getString(0));
                String number = c.getString(1);
                if (n.isEmpty() || number == null) continue;
                int score = 0;
                if (n.equals(want)) score = 100;
                else if (n.startsWith(want + " ")) score = 85;
                else if ((" " + n + " ").contains(" " + want + " ")) score = 70;
                else if (n.startsWith(want)) score = 55;
                else if (n.contains(want)) score = 40;
                if (score > 0 && c.getInt(2) == 1) score += 5;
                if (score > bestScore) { bestScore = score; best = number; }
            }
        } catch (Exception e) {
            return null;
        }
        return best;
    }

    @JavascriptInterface
    public boolean hasContacts() {
        return act.has(Manifest.permission.READ_CONTACTS);
    }

    @JavascriptInterface
    public void requestContacts() {
        act.ask(new String[]{Manifest.permission.READ_CONTACTS}, MainActivity.REQ_CONTACTS);
    }

    // ---------------------------------------------------------------- phone control
    // No accessibility service: in Brazil, Play Protect blocks sideloaded apps that declare one.

    @JavascriptInterface
    public boolean accessEnabled() { return false; }

    @JavascriptInterface
    public boolean globalAction(String what) { return false; }

    // ---------------------------------------------------------------- calendar

    /** Saves an event straight into the phone's calendar, with a reminder 30 minutes before. */
    @JavascriptInterface
    public String addEvent(String title, double startMs, double endMs, boolean allDay) {
        if (!act.has(Manifest.permission.WRITE_CALENDAR) || !act.has(Manifest.permission.READ_CALENDAR)) {
            pendingEvent = new Object[]{title, (long) startMs, (long) endMs, allDay};
            act.ask(new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, MainActivity.REQ_CAL);
            return "asked";
        }
        return insertEvent(title, (long) startMs, (long) endMs, allDay) ? "saved" : "failed";
    }

    void onCalendarPermission(boolean ok) {
        if (pendingEvent == null) return;
        Object[] e = pendingEvent;
        pendingEvent = null;
        boolean saved = ok && insertEvent((String) e[0], (Long) e[1], (Long) e[2], (Boolean) e[3]);
        if (!saved) {
            Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, (String) e[0])
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, (Long) e[1])
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, (Long) e[2])
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, (Boolean) e[3]);
            start(i);
        }
        act.emit("cal", saved ? "saved" : "opened", "{}");
    }

    private boolean insertEvent(String title, long start, long end, boolean allDay) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            long calId = -1;
            String[] proj = {CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE};
            try (Cursor c = cr.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)) {
                if (c != null) {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        boolean primary = !c.isNull(1) && c.getInt(1) == 1;
                        int access = c.getInt(2);
                        boolean visible = c.getInt(3) == 1;
                        if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR || !visible) continue;
                        if (calId == -1 || primary) calId = id;
                        if (primary) break;
                    }
                }
            }
            if (calId == -1) return false;
            ContentValues v = new ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, calId);
            v.put(CalendarContract.Events.TITLE, title);
            v.put(CalendarContract.Events.DTSTART, start);
            v.put(CalendarContract.Events.DTEND, end);
            v.put(CalendarContract.Events.ALL_DAY, allDay ? 1 : 0);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, allDay ? "UTC" : TimeZone.getDefault().getID());
            v.put(CalendarContract.Events.HAS_ALARM, 1);
            Uri ev = cr.insert(CalendarContract.Events.CONTENT_URI, v);
            if (ev == null) return false;
            ContentValues r = new ContentValues();
            r.put(CalendarContract.Reminders.EVENT_ID, Long.parseLong(ev.getLastPathSegment()));
            r.put(CalendarContract.Reminders.MINUTES, 30);
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- device

    @JavascriptInterface
    public boolean torch(boolean on) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cm.setTorchMode(id, on);
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** cmd: "mais", "menos", "mudo", "max" or a percentage 0-100. Returns the new volume in %. */
    @JavascriptInterface
    public int volume(String cmd) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int s = AudioManager.STREAM_MUSIC, max = am.getStreamMaxVolume(s), cur = am.getStreamVolume(s);
        String c = cmd == null ? "" : cmd.trim().toLowerCase(Locale.ROOT);
        int target = cur;
        if (c.startsWith("mais") || c.startsWith("aumenta") || c.equals("+")) target = cur + Math.max(1, max / 5);
        else if (c.startsWith("menos") || c.startsWith("diminui") || c.equals("-")) target = cur - Math.max(1, max / 5);
        else if (c.startsWith("mudo") || c.startsWith("silen")) target = 0;
        else if (c.startsWith("max")) target = max;
        else {
            try { target = Math.round(Float.parseFloat(c.replaceAll("[^0-9.]", "")) / 100f * max); } catch (Exception ignored) { }
        }
        target = Math.max(0, Math.min(max, target));
        am.setStreamVolume(s, target, AudioManager.FLAG_SHOW_UI);
        return Math.round(100f * target / max);
    }

    /** Opens a settings screen or quick panel: wifi, bluetooth, internet, som, bateria, tela, local, geral. */
    @JavascriptInterface
    public boolean openSettings(String which) {
        String w = norm(which);
        String action = Settings.ACTION_SETTINGS;
        if (w.contains("wi")) action = Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS;
        else if (w.contains("blue")) action = Settings.ACTION_BLUETOOTH_SETTINGS;
        else if (w.contains("internet") || w.contains("dados")) action = Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIRELESS_SETTINGS;
        else if (w.contains("som") || w.contains("volume")) action = Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_VOLUME : Settings.ACTION_SOUND_SETTINGS;
        else if (w.contains("bateria") || w.contains("energia")) action = Settings.ACTION_BATTERY_SAVER_SETTINGS;
        else if (w.contains("tela") || w.contains("brilho")) action = Settings.ACTION_DISPLAY_SETTINGS;
        else if (w.contains("local") || w.contains("gps")) action = Settings.ACTION_LOCATION_SOURCE_SETTINGS;
        else if (w.contains("nfc")) action = Settings.ACTION_NFC_SETTINGS;
        return start(new Intent(action));
    }

    @JavascriptInterface
    public void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(Math.max(10, Math.min(ms, 2000)), VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void keepScreenOn(boolean on) {
        act.keepScreenOn(on);
    }

    // ---------------------------------------------------------------- speech recognition (the WebView has none)

    /** continuous = the "Dzeko" wake loop (its start/stop beeps are muted); otherwise push-to-talk. */
    @JavascriptInterface
    public void srStart(final int id, final String lang, final boolean partial) {
        srStart2(id, lang, partial, false);
    }

    @JavascriptInterface
    public void srStart2(final int id, final String lang, final boolean partial, final boolean continuous) {
        main.post(() -> {
            if (!act.has(Manifest.permission.RECORD_AUDIO)) {
                act.emit("sr", "error", "{\"id\":" + id + ",\"error\":\"not-allowed\"}");
                act.emit("sr", "end", "{\"id\":" + id + "}");
                act.ask(new String[]{Manifest.permission.RECORD_AUDIO}, MainActivity.REQ_START);
                return;
            }
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
                act.emit("sr", "error", "{\"id\":" + id + ",\"error\":\"service-not-allowed\"}");
                act.emit("sr", "end", "{\"id\":" + id + "}");
                return;
            }
            // a fresh recognizer every time: reusing one that is still closing gives "busy" errors
            if (recognizer != null) {
                try { recognizer.cancel(); recognizer.destroy(); } catch (Exception ignored) { }
                recognizer = null;
            }
            if (!srEnded && srId != id) endSr(srId, null);
            srId = id;
            srEnded = false;
            srRetry = 0;
            main.postDelayed(() -> begin(id, lang, partial, continuous), 120);
        });
    }

    private void begin(int id, String lang, boolean partial, boolean continuous) {
        if (id != srId || srEnded) return;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(new Listener(id, lang, partial, continuous));
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang == null || lang.isEmpty() ? "pt-BR" : lang)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang == null || lang.isEmpty() ? "pt-BR" : lang)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partial)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName())
                    // don't cut you off after a short pause
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, continuous ? 1200 : 1800)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, continuous ? 1200 : 1800)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500);
            if (continuous) mute(true);
            recognizer.startListening(i);
        } catch (Exception e) {
            endSr(id, "network");
        }
    }

    /** The recognizer's start/stop beep plays on the notification and system sounds: silenced only during the wake loop. */
    private void mute(boolean on) {
        if (on == srMuted) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int dir = on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0);
            srMuted = on;
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public void srStop(final int id) {
        main.post(() -> { if (recognizer != null && id == srId) recognizer.stopListening(); });
    }

    @JavascriptInterface
    public void srAbort(final int id) {
        main.post(() -> {
            if (id != srId) return;
            if (recognizer != null) { try { recognizer.cancel(); } catch (Exception ignored) { } }
            endSr(id, null);
        });
    }

    void stopListening() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            endSr(srId, null);
        }
        mute(false);
    }

    private void endSr(int id, String error) {
        if (srEnded && id == srId) return;
        if (id == srId) {
            srEnded = true;
            main.postDelayed(() -> { if (srEnded) mute(false); }, 400);
        }
        if (error != null) act.emit("sr", "error", "{\"id\":" + id + ",\"error\":\"" + error + "\"}");
        act.emit("sr", "end", "{\"id\":" + id + "}");
    }

    private class Listener implements RecognitionListener {
        final int id;
        final String lang;
        final boolean partial, continuous;
        Listener(int id, String lang, boolean partial, boolean continuous) { this.id = id; this.lang = lang; this.partial = partial; this.continuous = continuous; }

        private void result(Bundle b, boolean fin) {
            ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty() || r.get(0) == null || r.get(0).isEmpty()) return;
            act.emit("sr", "result", "{\"id\":" + id + ",\"final\":" + fin + ",\"text\":" + JSONObject.quote(r.get(0)) + "}");
        }

        @Override public void onReadyForSpeech(Bundle params) {
            act.emit("sr", "start", "{\"id\":" + id + "}");
            if (continuous) main.postDelayed(() -> mute(false), 700);
        }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle partialResults) { result(partialResults, false); }
        @Override public void onEvent(int eventType, Bundle params) { }

        @Override public void onResults(Bundle results) {
            result(results, true);
            endSr(id, null);
        }

        @Override public void onError(int error) {
            if (id != srId) return;
            // the recognizer was still busy: try once more instead of failing
            if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) && srRetry < 2 && !srEnded) {
                srRetry++;
                try { if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); } } catch (Exception ignored) { }
                recognizer = null;
                main.postDelayed(() -> begin(id, lang, partial, continuous), 400);
                return;
            }
            String e;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: e = "no-speech"; break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: e = "not-allowed"; break;
                case SpeechRecognizer.ERROR_AUDIO: e = "audio-capture"; break;
                case SpeechRecognizer.ERROR_CLIENT: e = "aborted"; break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: e = "busy"; break;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: e = "language"; break;
                default: e = "network"; break;
            }
            endSr(id, e);
        }
    }

    // ---------------------------------------------------------------- text to speech (phone voice fallback)

    @JavascriptInterface
    public void ttsSpeak(String id, String text, float rate, float pitch) {
        if (!ttsReady) {
            synchronized (ttsQueue) { ttsQueue.add(new String[]{id, text, String.valueOf(rate), String.valueOf(pitch)}); }
            return;
        }
        tts.setSpeechRate(rate <= 0 ? 1f : rate);
        tts.setPitch(pitch <= 0 ? 1f : pitch);
        Bundle p = new Bundle();
        tts.speak(text == null ? "" : text, TextToSpeech.QUEUE_ADD, p, id);
    }

    @JavascriptInterface
    public void ttsStop() {
        if (tts != null) tts.stop();
    }

    // ---------------------------------------------------------------- helpers

    private boolean start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean installed(String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    void shutdown() {
        try { if (recognizer != null) recognizer.destroy(); } catch (Exception ignored) { }
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) { }
    }
}
