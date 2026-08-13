package com.danials.cameragate.core;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.view.View;

import java.util.Locale;

/**
 * Runtime language switching for the whole app.
 *
 * The classic Camera API requires plain Activities on Android 4.x, so we
 * cannot lean on AppCompat's per-activity locale wrapping: this helper
 * applies the chosen locale to any context the app hands it.
 *
 * <ul>
 *   <li>API 24+: {@link Context#createConfigurationContext(Configuration)}
 *       wraps a context with the requested locale (activities/notifications).</li>
 *   <li>API 16-23: {@link Resources#updateConfiguration(Configuration,
 *       android.util.DisplayMetrics)} retargets the process resources.</li>
 * </ul>
 *
 * Digits inside formatted numbers stay Latin intentionally (the product
 * rule is "numbers in English"): every String.format / SimpleDateFormat in
 * the app formats with {@link Locale#US}.
 */
public final class LocaleHelper {

    public static final String LANG_EN = "en";
    public static final String LANG_FA = "fa";

    private LocaleHelper() {
    }

    public static Locale locale(String lang) {
        return LANG_FA.equals(lang) ? new Locale(LANG_FA) : Locale.ENGLISH;
    }

    /**
     * Returns a context whose resources resolve to the given language, and
     * makes it the process default so services, the server and resources
     * created later pick it up too.
     */
    public static Context apply(Context base, String lang) {
        Locale loc = locale(lang);
        Locale.setDefault(loc);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(loc);
        } else {
            config.locale = loc; // field still public before API 24
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return base.createConfigurationContext(config);
        }
        Resources res = base.getResources();
        res.updateConfiguration(config, res.getDisplayMetrics());
        return base;
    }

    /** Reads the persisted language choice from prefs. */
    public static String current(Context base) {
        return new Settings(base).getLanguage();
    }

    /**
     * Re-assert the selected language on an activity resume.
     *
     * Activities are inflated in exactly one language; if that differs from
     * the current selection (e.g. the user changed it in settings, or Android
     * < 7.x silently reverted the process resources to the system locale),
     * the screen would otherwise mix languages. Recreate it so every view is
     * re-inflated in the selected language.
     *
     * @param attachedLang the language the activity was inflated in
     *                     (returned by {@link #attach(Context, String)}).
     * @return true if the activity was recreated.
     */
    public static boolean reassert(Activity activity, String attachedLang) {
        String lang = current(activity);
        apply(activity, lang);
        if (attachedLang == null || !attachedLang.equals(lang)) {
            activity.recreate();
            return true;
        }
        return false;
    }

    /**
     * Wraps a base context with the persisted language and returns the
     * context to attach (the wrapper on API 24+, the same context below).
     * The embedded activity stores {@link #current(Context)} itself if it
     * needs to know which language it was inflated in.
     */
    public static Context attach(Context base) {
        return apply(base, current(base));
    }

    /**
     * Forces layout direction: RTL for Persian, LTR for English (API 17+,
     * where the attribute exists; Android 4.2-4.3 devices are supported).
     * Call after inflating an activity's content view.
     */
    public static void applyLayoutDirection(Context context, View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            boolean rtl = LANG_FA.equals(current(context));
            root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL
                    : View.LAYOUT_DIRECTION_LTR);
        }
    }
}