package app.tvinfo;

import android.content.Context;
import android.content.SharedPreferences;

/** Small settings store. Position is in percent of the screen so it does not depend on density. */
final class Cfg {
    private static SharedPreferences p(Context c) { return c.getSharedPreferences("cfg", Context.MODE_PRIVATE); }

    static int mx(Context c) { return p(c).getInt("mx", 4); }      // distance from right edge, % of width
    static int my(Context c) { return p(c).getInt("my", 12); }     // distance from top, % of height
    static int scale(Context c) { return p(c).getInt("scale", 100); }
    static String lastFired(Context c) { return p(c).getString("fired", ""); }

    static void set(Context c, int mx, int my, int scale) {
        p(c).edit().putInt("mx", clamp(mx, 0, 60)).putInt("my", clamp(my, 0, 80))
                .putInt("scale", clamp(scale, 50, 200)).apply();
    }
    static void setLastFired(Context c, String v) { p(c).edit().putString("fired", v).apply(); }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
