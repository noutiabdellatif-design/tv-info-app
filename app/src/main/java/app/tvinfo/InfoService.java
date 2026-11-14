package app.tvinfo;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Draws the info card over the Projectivy home screen and plays the adhan at prayer times. */
public class InfoService extends Service {
    static final String TAG = "TvInfo";
    static final String ACTION_TEST = "app.tvinfo.TEST_ADHAN";
    static final String ACTION_STOP = "app.tvinfo.STOP_ADHAN";
    static final String HOME_PKG = "com.spocky.projengmenu";
    static volatile boolean running = false;

    private static final ZoneId ZONE = ZoneId.of("Africa/Algiers");
    private static final String[] NAMES = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final int[] SHOWN = {0, 2, 3, 4, 5};
    private static final String[] DAYS = {"الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد"};
    private static final String[] MONTHS = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس",
            "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};
    private static final String[] HMONTHS = {"محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
            "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة"};

    private static final int C_BG = 0xE6091A1E, C_LINE = 0x55D6C496, C_TEXT = 0xFFF1ECE0,
            C_MUTED = 0xFFA9B9B6, C_GOLD = 0xFFE0BD6A, C_GOLD_BG = 0x33E0BD6A;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WindowManager wm;
    private LinearLayout card;
    private WindowManager.LayoutParams lp;
    private int screenW, screenH;
    private float unit;
    private int builtScale = -1, builtMx = -1, builtMy = -1;

    private TextView tvTemp, tvCond, tvMinMax, tvGreg, tvHijri, tvClock, tvNext;
    private final TextView[] tvTimes = new TextView[5];
    private final LinearLayout[] boxTimes = new LinearLayout[5];

    private String lastFg = "";
    private long lastQuery = 0;
    private int tickCount = 0;

    private volatile String wTemp = "--", wCond = "جارٍ التحميل", wMinMax = "";
    private long lastWeather = 0;

    private MediaPlayer player;
    private int adhanIdx = -1;
    private long adhanUntil = 0;

    private LocalDate cachedDate;
    private int[] today, tomorrow;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { onTick(); } catch (Throwable t) { Log.e(TAG, "tick", t); }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        startAsForeground();
        running = true;
        handler.post(tick);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            if (ACTION_TEST.equals(intent.getAction())) playAdhan(2);
            else if (ACTION_STOP.equals(intent.getAction())) stopAdhan();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        stopAdhan();
        removeCard();
        io.shutdownNow();
        super.onDestroy();
    }

    private void startAsForeground() {
        String id = "tvinfo";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(id, "TV info", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, id)
                .setContentTitle("Mouzaia info")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    // ---------------------------------------------------------------- per-second work

    private void onTick() {
        tickCount++;
        LocalDateTime now = LocalDateTime.now(ZONE);
        ensureTimes(now.toLocalDate());
        int nowMin = now.getHour() * 60 + now.getMinute();

        checkAdhan(now, nowMin);
        if (tickCount % 2 == 0) pollForeground();
        if (System.currentTimeMillis() - lastWeather > 15 * 60_000L) refreshWeather();

        boolean adhanActive = System.currentTimeMillis() < adhanUntil;
        boolean show = adhanActive || homeVisible();
        if (!Settings.canDrawOverlays(this)) { removeCard(); return; }
        ensureCard();
        if (card == null) return;
        card.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) updateTexts(now, nowMin, adhanActive);
    }

    private void ensureTimes(LocalDate d) {
        if (!d.equals(cachedDate)) {
            cachedDate = d;
            today = PrayerTimes.minutes(d);
            tomorrow = PrayerTimes.minutes(d.plusDays(1));
        }
    }

    // ---------------------------------------------------------------- adhan

    private void checkAdhan(LocalDateTime now, int nowMin) {
        for (int idx : SHOWN) {
            if (today[idx] == nowMin) {
                String key = now.toLocalDate() + "#" + idx;
                if (!key.equals(Cfg.lastFired(this))) {
                    Cfg.setLastFired(this, key);
                    playAdhan(idx);
                }
            }
        }
    }

    private void playAdhan(int idx) {
        stopAdhan();
        adhanIdx = idx;
        long dur = 0;
        try {
            File dir = getExternalFilesDir(null);
            File f = new File(dir, idx == 0 ? "adhan_fajr.mp3" : "adhan.mp3");
            if (!f.exists()) f = new File(dir, "adhan.mp3");
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            if (f.exists()) mp.setDataSource(f.getAbsolutePath());
            else mp.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION));
            mp.setOnCompletionListener(m -> stopAdhan());
            mp.prepare();
            mp.start();
            dur = mp.getDuration();
            player = mp;
        } catch (Throwable t) {
            Log.e(TAG, "adhan failed", t);
        }
        adhanUntil = System.currentTimeMillis() + Math.max(dur, 30_000L) + 60_000L;
    }

    private void stopAdhan() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        adhanUntil = Math.min(adhanUntil, System.currentTimeMillis() + 60_000L);
    }

    // ---------------------------------------------------------------- foreground detection

    private boolean usageAllowed() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    @SuppressWarnings("deprecation")
    private void pollForeground() {
        if (!usageAllowed()) return;
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        long from = lastQuery == 0 ? now - 6 * 3600_000L : lastQuery - 1500;
        UsageEvents ev = usm.queryEvents(from, now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) lastFg = e.getPackageName();
        }
        lastQuery = now;
    }

    /** Without the usage permission the card is simply always visible. */
    private boolean homeVisible() {
        if (!usageAllowed()) return true;
        return lastFg.isEmpty() || lastFg.equals(HOME_PKG) || lastFg.equals(getPackageName());
    }

    // ---------------------------------------------------------------- weather

    private void refreshWeather() {
        lastWeather = System.currentTimeMillis() - 10 * 60_000L; // retry in 5 min on failure
        io.execute(() -> {
            try {
                String u = "https://api.open-meteo.com/v1/forecast?latitude=36.4667&longitude=2.6833"
                        + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
                        + "&timezone=Africa%2FAlgiers&forecast_days=1";
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                InputStream is = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
                JSONObject j = new JSONObject(bo.toString("UTF-8"));
                JSONObject cur = j.getJSONObject("current");
                JSONObject daily = j.getJSONObject("daily");
                wTemp = Math.round(cur.getDouble("temperature_2m")) + "°";
                wCond = condition(cur.getInt("weather_code"));
                wMinMax = "العظمى " + Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)) + "°"
                        + "  ·  الصغرى " + Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)) + "°";
                lastWeather = System.currentTimeMillis();
            } catch (Throwable t) {
                Log.e(TAG, "weather", t);
                if (wTemp.equals("--")) wCond = "لا يوجد اتصال";
            }
        });
    }

    private static String condition(int c) {
        if (c == 0) return "صافٍ";
        if (c <= 2) return "قليل الغيوم";
        if (c == 3) return "غائم";
        if (c <= 48) return "ضباب";
        if (c <= 57) return "رذاذ";
        if (c <= 67) return "مطر";
        if (c <= 77) return "ثلج";
        if (c <= 82) return "زخات مطر";
        if (c <= 86) return "زخات ثلج";
        return "عاصفة رعدية";
    }

    // ---------------------------------------------------------------- overlay

    private void measureScreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            screenW = b.width();
            screenH = b.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
    }

    private void ensureCard() {
        int scale = Cfg.scale(this), mx = Cfg.mx(this), my = Cfg.my(this);
        if (card != null && scale == builtScale && mx == builtMx && my == builtMy) return;
        boolean rebuild = card == null || scale != builtScale;
        measureScreen();
        if (rebuild) {
            removeCard();
            unit = screenW / 100f * (scale / 100f);
            buildCard();
            lp = new WindowManager.LayoutParams(Math.round(27f * unit), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.RIGHT;
            lp.x = Math.round(screenW * mx / 100f);
            lp.y = Math.round(screenH * my / 100f);
            try { wm.addView(card, lp); } catch (Throwable t) { Log.e(TAG, "addView", t); card = null; return; }
        } else {
            lp.x = Math.round(screenW * mx / 100f);
            lp.y = Math.round(screenH * my / 100f);
            try { wm.updateViewLayout(card, lp); } catch (Throwable t) { Log.e(TAG, "update", t); }
        }
        builtScale = scale; builtMx = mx; builtMy = my;
    }

    private void removeCard() {
        if (card != null) {
            try { wm.removeView(card); } catch (Throwable ignored) { }
            card = null;
        }
        builtScale = -1;
    }

    private int px(float u) { return Math.round(u * unit); }

    private TextView tv(float sizeU, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeU * unit);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout box(int orientation) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(orientation);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return l;
    }

    private void buildCard() {
        card = box(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_BG);
        bg.setCornerRadius(px(1.2f));
        bg.setStroke(Math.max(1, px(0.1f)), C_LINE);
        card.setBackground(bg);
        card.setPadding(px(1.5f), px(1.3f), px(1.5f), px(1.1f));

        // Place on the right, temperature on the left
        LinearLayout top = box(LinearLayout.HORIZONTAL);
        LinearLayout place = box(LinearLayout.VERTICAL);
        TextView name = tv(1.5f, C_GOLD, true);
        name.setText("موزاية");
        TextView sub = tv(0.85f, C_MUTED, false);
        sub.setText("البليدة، الجزائر");
        place.addView(name);
        place.addView(sub);
        top.addView(place);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout temp = box(LinearLayout.VERTICAL);
        temp.setGravity(Gravity.LEFT);
        tvTemp = tv(3.0f, C_TEXT, true);
        tvTemp.setTextDirection(View.TEXT_DIRECTION_LTR);
        tvCond = tv(0.95f, C_MUTED, false);
        tvMinMax = tv(0.8f, C_MUTED, false);
        temp.addView(tvTemp);
        temp.addView(tvCond);
        temp.addView(tvMinMax);
        top.addView(temp);
        card.addView(top);

        card.addView(line());

        LinearLayout dates = box(LinearLayout.HORIZONTAL);
        LinearLayout dl = box(LinearLayout.VERTICAL);
        tvGreg = tv(1.05f, C_TEXT, false);
        tvHijri = tv(0.9f, C_MUTED, false);
        dl.addView(tvGreg);
        dl.addView(tvHijri);
        dates.addView(dl);
        dates.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        tvClock = tv(1.6f, C_TEXT, true);
        tvClock.setTextDirection(View.TEXT_DIRECTION_LTR);
        dates.addView(tvClock);
        card.addView(dates);

        card.addView(line());

        LinearLayout times = box(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 5; i++) {
            LinearLayout col = box(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(0, px(0.5f), 0, px(0.5f));
            TextView n = tv(0.85f, C_MUTED, false);
            n.setText(NAMES[SHOWN[i]]);
            TextView t = tv(1.15f, C_TEXT, false);
            t.setTextDirection(View.TEXT_DIRECTION_LTR);
            col.addView(n);
            col.addView(t);
            tvTimes[i] = t;
            boxTimes[i] = col;
            times.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(times);

        tvNext = tv(0.95f, C_MUTED, false);
        tvNext.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nl.topMargin = px(0.6f);
        card.addView(tvNext, nl);
    }

    private View line() {
        View v = new View(this);
        v.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, px(0.08f)));
        p.topMargin = px(0.8f);
        p.bottomMargin = px(0.8f);
        v.setLayoutParams(p);
        return v;
    }

    private void updateTexts(LocalDateTime now, int nowMin, boolean adhanActive) {
        tvTemp.setText(wTemp);
        tvCond.setText(wCond);
        tvMinMax.setText(wMinMax);
        LocalDate d = now.toLocalDate();
        tvGreg.setText(DAYS[d.getDayOfWeek().getValue() - 1] + " " + d.getDayOfMonth() + " "
                + MONTHS[d.getMonthValue() - 1] + " " + d.getYear());
        try {
            HijrahDate h = HijrahDate.from(d);
            tvHijri.setText(h.get(ChronoField.DAY_OF_MONTH) + " " + HMONTHS[h.get(ChronoField.MONTH_OF_YEAR) - 1]
                    + " " + h.get(ChronoField.YEAR) + " هـ");
        } catch (Throwable t) {
            tvHijri.setText("");
        }
        tvClock.setText(PrayerTimes.fmt(nowMin));

        int next = -1, nextMin = 0;
        for (int i = 0; i < 5; i++) {
            if (today[SHOWN[i]] > nowMin) { next = i; nextMin = today[SHOWN[i]]; break; }
        }
        if (next < 0) { next = 0; nextMin = tomorrow[SHOWN[0]] + 1440; }

        int highlight = next;
        if (adhanActive) {
            for (int i = 0; i < 5; i++) if (SHOWN[i] == adhanIdx) highlight = i;
        }
        for (int i = 0; i < 5; i++) {
            tvTimes[i].setText(PrayerTimes.fmt(today[SHOWN[i]]));
            boolean on = i == highlight;
            boxTimes[i].setBackground(on ? pill() : null);
            tvTimes[i].setTextColor(on ? C_GOLD : C_TEXT);
        }
        if (adhanActive) {
            tvNext.setText("حان الآن وقت صلاة " + NAMES[adhanIdx]);
            tvNext.setTextColor(C_GOLD);
        } else {
            int diff = nextMin - nowMin;
            String left = (diff >= 60 ? (diff / 60) + " س " : "") + (diff % 60) + " د";
            tvNext.setText("الصلاة القادمة: " + NAMES[SHOWN[next]] + " بعد " + left);
            tvNext.setTextColor(C_MUTED);
        }
    }

    private GradientDrawable pill() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_GOLD_BG);
        g.setCornerRadius(px(0.7f));
        g.setStroke(Math.max(1, px(0.08f)), C_GOLD);
        return g;
    }
}
