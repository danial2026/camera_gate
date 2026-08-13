package com.danials.cameragate.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tiny persistence layer for user-configurable server settings.
 *
 * Backed by SharedPreferences, which exists since Android 1 and is the
 * safest storage choice for an app that must run on Android 4.x.
 */
public final class Settings {

    private static final String PREFS = "cameragate_settings";

    public static final int DEFAULT_PORT = 8080;

    private final Context context;

    public Settings(Context context) {
        this.context = context.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int getPort() {
        int port = prefs().getInt("port", DEFAULT_PORT);
        if (port < 1 || port > 65535) {
            port = DEFAULT_PORT;
        }
        return port;
    }

    public void setPort(int port) {
        prefs().edit().putInt("port", port).apply();
    }

    /** Empty string means no authentication is required. */
    public String getToken() {
        return prefs().getString("token", "");
    }

    public void setToken(String token) {
        prefs().edit().putString("token", token == null ? "" : token.trim()).apply();
    }

    /** Interface the HTTP server binds to; 0.0.0.0 = all interfaces. */
    public static final String LISTEN_ALL = "0.0.0.0";

    public String getListenAddress() {
        String a = prefs().getString("listenAddress", LISTEN_ALL);
        return (a == null || a.trim().isEmpty()) ? LISTEN_ALL : a.trim();
    }

    public void setListenAddress(String address) {
        prefs().edit().putString("listenAddress",
                address == null ? LISTEN_ALL : address.trim()).apply();
    }

    /**
     * Requested preview/stream resolution as {width, height}, or null for
     * automatic (largest size the maxPreviewWidth cap allows).
     */
    public int[] getPreviewSize() {
        String s = prefs().getString("previewSize", "");
        if (s == null || s.isEmpty()) {
            return null;
        }
        String[] p = s.split("x");
        try {
            int w = Integer.parseInt(p[0].trim());
            int h = Integer.parseInt(p[1].trim());
            if (w > 0 && h > 0) {
                return new int[]{w, h};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public void setPreviewSize(String size) {
        prefs().edit().putString("previewSize",
                size == null ? "" : size.trim()).apply();
    }

    /** Green status overlay (time / fps / battery) drawn on every frame. */
    public boolean getOsdEnabled() {
        return prefs().getBoolean("osd", true);
    }

    public void setOsdEnabled(boolean enabled) {
        prefs().edit().putBoolean("osd", enabled).apply();
    }

    /** Stream frame-rate cap in fps (0 = uncapped). */
    public int getFps() {
        int fps = prefs().getInt("fps", 15);
        if (fps < 0 || fps > 30) {
            fps = 15;
        }
        return fps;
    }

    public void setFps(int fps) {
        prefs().edit().putInt("fps", fps < 0 ? 0 : Math.min(fps, 30)).apply();
    }

    /** UI language: {@link LocaleHelper#LANG_EN} (default) or LANG_FA. */
    public String getLanguage() {
        String lang = prefs().getString("language", LocaleHelper.LANG_EN);
        return LocaleHelper.LANG_FA.equals(lang) ? LocaleHelper.LANG_FA
                : LocaleHelper.LANG_EN;
    }

    public void setLanguage(String lang) {
        prefs().edit().putString("language",
                LocaleHelper.LANG_FA.equals(lang) ? LocaleHelper.LANG_FA
                        : LocaleHelper.LANG_EN).apply();
    }

    public int getCameraId() {
        int id = prefs().getInt("cameraId", 0);
        int max = android.hardware.Camera.getNumberOfCameras() - 1;
        if (id < 0 || id > max) {
            id = 0;
        }
        return id;
    }

    public void setCameraId(int id) {
        prefs().edit().putInt("cameraId", id).apply();
    }

    /** Max thumbnail-less stream width we request from the camera. */
    public int getMaxPreviewWidth() {
        return prefs().getInt("maxPreviewWidth", 1280);
    }
}