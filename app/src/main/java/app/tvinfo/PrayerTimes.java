package app.tvinfo;

import java.time.LocalDate;

/** Prayer times for Mouzaia (Blida, Algeria). Fajr 18 deg, Isha 17 deg, standard Asr. Hours are local (UTC+1). */
final class PrayerTimes {
    static final double LAT = 36.4667, LON = 2.6833;
    static final int TZ = 1;
    // Indexes: 0 fajr, 1 sunrise, 2 dhuhr, 3 asr, 4 maghrib, 5 isha
    // Per-prayer manual adjustment in minutes (to match the official calendar if it differs by a minute or two).
    static final int[] ADJ_MIN = {0, 0, 0, 0, 0, 0};

    private static final double RAD = Math.PI / 180, DEG = 180 / Math.PI;

    private static double sin(double x) { return Math.sin(x * RAD); }
    private static double cos(double x) { return Math.cos(x * RAD); }
    private static double tan(double x) { return Math.tan(x * RAD); }
    private static double acos(double x) { return Math.acos(Math.max(-1, Math.min(1, x))) * DEG; }
    private static double fix(double x, double a) { return x - a * Math.floor(x / a); }

    /** Returns minutes since local midnight for the 6 times. */
    static int[] minutes(LocalDate date) {
        double jd = date.toEpochDay() + 0.5 + 2440587.5;
        double d = jd - 2451545.0;
        double g = fix(357.529 + 0.98560028 * d, 360);
        double q = fix(280.459 + 0.98564736 * d, 360);
        double l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360);
        double e = 23.439 - 0.00000036 * d;
        double ra = fix(Math.atan2(cos(e) * sin(l), cos(l)) * DEG / 15, 24);
        double eqt = q / 15 - ra;
        if (eqt > 12) eqt -= 24;
        if (eqt < -12) eqt += 24;
        double decl = Math.asin(sin(e) * sin(l)) * DEG;
        double noon = 12 + TZ - LON / 15 - eqt;

        double fajr = noon - hourAngle(18, decl);
        double sunrise = noon - hourAngle(0.833, decl);
        double dhuhr = noon + 1.0 / 60;
        double asrAlt = Math.atan(1 / (1 + tan(Math.abs(LAT - decl)))) * DEG;
        double asr = noon + acos((sin(asrAlt) - sin(decl) * sin(LAT)) / (cos(decl) * cos(LAT))) / 15;
        double maghrib = noon + hourAngle(0.833, decl) + 1.0 / 60;
        double isha = noon + hourAngle(17, decl);

        double[] h = {fajr, sunrise, dhuhr, asr, maghrib, isha};
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) out[i] = (int) Math.round(fix(h[i], 24) * 60) + ADJ_MIN[i];
        return out;
    }

    private static double hourAngle(double angle, double decl) {
        return acos((-sin(angle) - sin(decl) * sin(LAT)) / (cos(decl) * cos(LAT))) / 15;
    }

    static String fmt(int minutes) {
        int m = ((minutes % 1440) + 1440) % 1440;
        return String.format("%02d:%02d", m / 60, m % 60);
    }
}
