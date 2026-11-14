package app.tvinfo;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Setup screen: start service, tune position/size, test adhan. Fully D-pad navigable. */
public class MainActivity extends Activity {
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1); } catch (Throwable ignored) { }
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B1F24"));
        root.setPadding(80, 50, 80, 30);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(20);
        root.addView(status);

        Button first = add(root, "تشغيل الخدمة", v -> {
            startForegroundService(new Intent(this, InfoService.class));
            refresh();
        });
        row(root, "يسار", v -> move(-1, 0), "يمين", v -> move(1, 0));
        row(root, "أعلى", v -> move(0, -1), "أسفل", v -> move(0, 1));
        row(root, "تكبير", v -> size(10), "تصغير", v -> size(-10));
        row(root, "تجربة الأذان", v -> startService(new Intent(this, InfoService.class).setAction(InfoService.ACTION_TEST)),
                "إيقاف الأذان", v -> startService(new Intent(this, InfoService.class).setAction(InfoService.ACTION_STOP)));
        add(root, "إيقاف الخدمة", v -> { stopService(new Intent(this, InfoService.class)); refresh(); });
        add(root, "فتح إذن العرض فوق التطبيقات", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) { status.append("\nلا توجد شاشة لهذا الإذن، استعمل أمر adb."); }
        });
        setContentView(root);
        first.requestFocus();
        startForegroundService(new Intent(this, InfoService.class));
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void move(int dx, int dy) {
        // dx>0 moves card to the right (smaller right margin)
        Cfg.set(this, Cfg.mx(this) - dx, Cfg.my(this) + dy, Cfg.scale(this));
        refresh();
    }

    private void size(int d) { Cfg.set(this, Cfg.mx(this), Cfg.my(this), Cfg.scale(this) + d); refresh(); }

    private void refresh() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        boolean usage = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                == AppOpsManager.MODE_ALLOWED;
        status.setText("الإذن بالعرض فوق التطبيقات: " + (Settings.canDrawOverlays(this) ? "نعم" : "لا")
                + "\nإذن معرفة التطبيق الحالي: " + (usage ? "نعم" : "لا")
                + "\nالخدمة: " + (InfoService.running ? "تعمل" : "متوقفة")
                + "\nالهامش من اليمين " + Cfg.mx(this) + "٪ — من الأعلى " + Cfg.my(this) + "٪ — الحجم " + Cfg.scale(this) + "٪");
    }

    private interface Click { void on(View v); }

    private Button add(LinearLayout parent, String text, Click c) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(c::on);
        parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    private void row(LinearLayout parent, String t1, Click c1, String t2, Click c2) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        for (int i = 0; i < 2; i++) {
            Button b = new Button(this);
            b.setText(i == 0 ? t1 : t2);
            final Click c = i == 0 ? c1 : c2;
            b.setOnClickListener(c::on);
            r.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        parent.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }
}
