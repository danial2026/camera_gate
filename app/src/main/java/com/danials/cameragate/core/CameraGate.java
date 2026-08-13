package com.danials.cameragate.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.danials.cameragate.BuildConfig;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Application-level facade: owns the camera, the frame pipeline, the HTTP
 * server and the user settings, and exposes the API surface used by the
 * HTTP routes and the Activity UI.
 */
public final class CameraGate {

    private static final String TAG = "CameraGate";

    public interface Listener {
        void onStateChanged();
    }

    private final Context context;
    private final Settings settings;
    private final JpegFrames frames;
    private HttpServer http;
    private CameraSource camera;

    private volatile boolean running = false;

    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<Listener>();
    private final Handler main = new Handler(Looper.getMainLooper());

    public CameraGate(Context context) {
        this.context = context.getApplicationContext();
        this.settings = new Settings(context);
        this.frames = new JpegFrames(context);
    }

    public synchronized boolean start() {
        if (running) {
            return true;
        }
        int port = settings.getPort();
        int[] size = settings.getPreviewSize();
        camera = new CameraSource(frames);
        camera.open(settings.getCameraId(), settings.getMaxPreviewWidth(),
                size == null ? 0 : size[0], size == null ? 0 : size[1]);
        if (!cameraOpen()) {
            camera.release();
            camera = null;
            notifyChanged();
            return false;
        }
        frames.setOsdEnabled(settings.getOsdEnabled());
        frames.setFaceDetection(settings.getFaceDetectEnabled());
        frames.applyFaceSettings(settings.getFaceMaxFaces(),
                settings.getFaceFinestDiv(), settings.getFaceScanMs(),
                settings.getFaceContrast(), settings.getFaceDeepScan());
        frames.setMaxFps(settings.getFps());
        List<String> ips = ipAddresses();
        frames.setOsdLabel(ips.isEmpty() ? null
                : ips.get(0) + ":" + settings.getPort());
        http = new HttpServer(this);
        if (!http.start(settings.getListenAddress(), port)) {
            camera.release();
            camera = null;
            notifyChanged();
            return false;
        }
        running = true;
        Log.i(TAG, "CameraGate server running at "
                + primaryBaseUrl() + "/");
        notifyChanged();
        return true;
    }

    public synchronized void stop() {
        if (http != null) {
            http.stop();
            http = null;
        }
        if (camera != null) {
            camera.release();
            camera = null;
        }
        running = false;
        notifyChanged();
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        return settings.getPort();
    }

    public int cameraId() {
        return settings.getCameraId();
    }

    public String listenAddress() {
        return settings.getListenAddress();
    }

    /** Requested resolution as "WxH", or null for automatic. */
    public String previewSizeSetting() {
        int[] s = settings.getPreviewSize();
        return s == null ? null : s[0] + "x" + s[1];
    }

    public boolean isOsdEnabled() {
        return settings.getOsdEnabled();
    }

    public int cameraWidth() {
        return camera == null ? 0 : camera.getPreviewWidth();
    }

    public int cameraHeight() {
        return camera == null ? 0 : camera.getPreviewHeight();
    }

    public String cameraFacing() {
        if (camera != null) {
            return cameraFacingName();
        }
        try {
            android.hardware.Camera.CameraInfo info =
                    new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(settings.getCameraId(), info);
            return info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT
                    ? "front" : "back";
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }

    public String recordingFile() {
        if (camera == null || camera.getRecordFile() == null) {
            return "";
        }
        return camera.getRecordFile().getName();
    }

    public boolean cameraOpen() {
        return camera != null && camera.getState() != CameraSource.STATE_IDLE;
    }

    public boolean recording() {
        return camera != null && camera.isRecording();
    }

    public File startRecording() {
        return camera != null ? camera.startRecording() : null;
    }

    public File stopRecording() {
        return camera != null ? camera.stopRecording() : null;
    }

    public boolean authorized(String authorizationHeader, String queryToken) {
        String token = settings.getToken();
        if (token.length() == 0) {
            return true;
        }
        if (queryToken != null && constantTimeEquals(token, queryToken)) {
            return true;
        }
        if (authorizationHeader != null) {
            String bearer = authorizationHeader.startsWith("Bearer ")
                    ? authorizationHeader.substring(7) : null;
            if (bearer != null && constantTimeEquals(token, bearer)) {
                return true;
            }
        }
        return false;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    // ------------------------------------------------------------- consumers

    public JpegFrames frames() {
        return frames;
    }

    public byte[] snapshotJpeg() {
        return frames.latest();
    }

    public byte[] qrPng() {
        String url = primaryBaseUrl();
        try {
            int size = 320;
            BitMatrix matrix = new QRCodeWriter()
                    .encode(url, BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            bmp.recycle();
            return out.toByteArray();
        } catch (WriterException e) {
            Log.e(TAG, "qr failed", e);
            return null;
        }
    }

    // -------------------------------------------------------------- listeners

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    l.onStateChanged();
                }
            }
        });
    }

    // ---------------------------------------------------------------- network

    /** All non-loopback IPv4 addresses, most likely LAN interface first. */
    public List<String> ipAddresses() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ifaces =
                    NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return out;
            }
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        out.add(addr.getHostAddress());
                    }
                }
            }
            if (out.isEmpty()) {
                // Android 4.x sometimes does not surface wlan0 through
                // NetworkInterface enumeration in a fresh process, even
                // though the wifi is connected. Ask WifiManager as a fallback.
                String wifiIp = wifiIpAddress();
                if (wifiIp != null) {
                    out.add(wifiIp);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ip enumeration failed", e);
        }
        return out;
    }

    /** WiFi IPv4 via WifiManager (dotted-quad), or null. */
    private String wifiIpAddress() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    context.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return null;
            }
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip == 0) {
                return null;
            }
            return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "."
                    + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
        } catch (Exception e) {
            return null;
        }
    }

    public String primaryBaseUrl() {
        List<String> ips = ipAddresses();
        String ip = ips.isEmpty() ? "127.0.0.1" : ips.get(0);
        return "http://" + ip + ":" + settings.getPort();
    }

    // ------------------------------------------------------------------- JSON

    public String cameraInfoJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{")
          .append("\"app\":\"CameraGate\",")
          .append("\"version\":\"").append(BuildConfig.VERSION_NAME).append("\",")
          .append("\"camera\":{")
            .append("\"id\":").append(settings.getCameraId()).append(',')
            .append("\"open\":").append(cameraOpen()).append(',');
        if (cameraOpen()) {
            sb.append("\"size\":\"")
              .append(camera.getPreviewWidth()).append('x')
              .append(camera.getPreviewHeight()).append("\",")
              .append("\"orientation\":").append(cameraDisplayOrientation()).append(',')
              .append("\"facing\":\"").append(cameraFacingName()).append("\",");
        }
        sb.append("\"state\":\"").append(cameraStateName()).append("\"")
          .append("},")
          .append("\"server\":{")
            .append("\"running\":").append(running).append(',')
            .append("\"port\":").append(settings.getPort()).append(',')
            .append("\"listen\":\"").append(settings.getListenAddress()).append("\",")
            .append("\"fps\":").append(settings.getFps()).append(',')
            .append("\"ips\":").append(ipsJson())
          .append("},")
          .append("\"osd\":").append(settings.getOsdEnabled()).append(',')
          .append("\"faceDetect\":{")
            .append("\"enabled\":").append(settings.getFaceDetectEnabled()).append(',')
            .append("\"faces\":").append(frames.faceCount()).append(',')
            .append("\"maxFaces\":").append(settings.getFaceMaxFaces()).append(',')
            .append("\"finestDiv\":").append(settings.getFaceFinestDiv()).append(',')
            .append("\"scanMs\":").append(settings.getFaceScanMs()).append(',')
            .append("\"contrast\":").append(settings.getFaceContrast()).append(',')
            .append("\"deepScan\":").append(settings.getFaceDeepScan())
          .append("},")
          .append("\"record\":{")
            .append("\"recording\":").append(recording()).append(',')
            .append("\"file\":");
        File rec = camera == null ? null : camera.getRecordFile();
        sb.append(rec == null ? "null" : "\"" + escape(rec.getName()) + "\"");
        sb.append("},")
          .append("\"stream\":{\"mjpeg\":\"/stream\",\"websocket\":\"/ws\"},")
          .append("\"usage\":{\"info\":\"GET /camera\",\"snapshot\":\"GET /snapshot\",")
          .append("\"record\":{\"start\":\"POST /record/start\",\"stop\":\"POST /record/stop\"}}")
          .append("}");
        return sb.toString();
    }

    private String cameraStateName() {
        if (camera == null) {
            return "none";
        }
        switch (camera.getState()) {
            case CameraSource.STATE_RECORDING: return "recording";
            case CameraSource.STATE_STREAMING: return "streaming";
            default: return "none";
        }
    }

    private int cameraDisplayOrientation() {
        return camera == null ? 0 : camera.getDisplayOrientation();
    }

    private String cameraFacingName() {
        return camera == null ? "unknown"
                : (camera.getFacing() == 0 ? "back" : "front");
    }

    private String ipsJson() {
        List<String> ips = ipAddresses();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ips.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(ips.get(i)).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------------- HTML

    public String dashboardHtml() {
        List<String> ips = ipAddresses();
        StringBuilder urls = new StringBuilder();
        for (String ip : ips) {
            urls.append("<tr><td class=\"m\">").append(ip)
                .append("</td><td><a href=\"http://").append(ip).append(":")
                .append(settings.getPort()).append("/\">http://").append(ip)
                .append(":").append(settings.getPort()).append("/</a></td></tr>");
        }
        if (ips.isEmpty()) {
            urls.append("<tr><td>no LAN address</td></tr>");
        }
        String recState = recording()
                ? "<span class=\"on\">recording</span>"
                : "<span class=\"off\">idle</span>";
        String serverState = running
                ? "<span class=\"on\">running</span>"
                : "<span class=\"off\">stopped</span>";
        boolean secured = settings.getToken().length() > 0;
        String authHint = secured
                ? "<p class=\"note\">Token authentication is enabled. " +
                  "Pass <code>?token=YOUR_TOKEN</code> or " +
                  "<code>Authorization: Bearer YOUR_TOKEN</code> on every API call.</p>"
                : "<p class=\"note\">No token configured - anyone on this network " +
                  "can use the API. Set a token in the app settings.</p>";
        String streamLink = secured ? "/stream?token=YOUR_TOKEN" : "/stream";
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width\">" +
                "<meta http-equiv=\"refresh\" content=\"10\">" +
                "<title>CameraGate</title><style>" +
                "body{background:#0d1b2a;color:#e5e7eb;font-family:monospace;margin:0;padding:24px}" +
                "h1{color:#4dd0e1;font-size:22px;margin:0 0 4px}" +
                "h2{color:#94a3b8;font-size:14px;font-weight:normal;margin:0 0 20px}" +
                "table{border-collapse:collapse;width:100%;max-width:720px}" +
                "td{border-bottom:1px solid #1f3a5f;padding:8px 6px;font-size:14px}" +
                "a{color:#4dd0e1;text-decoration:none}" +
                "code{background:#1f3a5f;padding:2px 6px;border-radius:3px}" +
                ".on{color:#4caf50}.off{color:#f36}.m{color:#94a3b8}" +
                ".note{margin:24px 0 0;max-width:720px;color:#94a3b8}" +
                ".sec{margin-top:24px;max-width:720px}" +
                "</style></head><body>" +
                "<h1>CameraGate</h1><h2>self-hosted camera server on your phone</h2>" +
                "<table>" +
                "<tr><td class=\"m\">server</td><td>" + serverState + "</td></tr>" +
                "<tr><td class=\"m\">record</td><td>" + recState + "</td></tr>" +
                urls + "</table>" + authHint +
                "<div class=\"sec\"><h2>links</h2><table>" +
                "<tr><td class=\"m\">info</td><td><a href=\"/camera\">/camera</a></td></tr>" +
                "<tr><td class=\"m\">snapshot</td><td><a href=\"/snapshot\">/snapshot</a> (jpeg)</td></tr>" +
                "<tr><td class=\"m\">mjpeg stream</td><td><a href=\"" + streamLink + "\">/stream</a></td></tr>" +
                "<tr><td class=\"m\">websocket</td><td><code>/ws</code> (binary jpeg frames)</td></tr>" +
                "<tr><td class=\"m\">qr</td><td><a href=\"/qr\">/qr</a> (png)</td></tr>" +
                "<tr><td class=\"m\">openapi</td><td><a href=\"/swagger\">/swagger</a></td></tr>" +
                "<tr><td class=\"m\">health</td><td><a href=\"/health\">/health</a></td></tr>" +
                "</table></div></body></html>";
    }

    // ----------------------------------------------------------------- swagger

    public String swaggerJson() {
        return "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"CameraGate\"," +
                "\"version\":\"" + BuildConfig.VERSION_NAME + "\",\"description\":\"Self-hosted camera " +
                "server. Runs on the phone and exposes the camera over LAN.\"}," +
                "\"servers\":[{\"url\":\"/\"}]," +
                "\"security\":[{\"token\":[]}]," +
                "\"components\":{\"securitySchemes\":{\"token\":{" +
                "\"type\":\"apiKey\",\"in\":\"header\",\"name\":\"X-CameraGate-Token\"}}}," +
                "\"paths\":{" +
                "\"/camera\":{\"get\":{\"summary\":\"Camera and server state\",\"responses\":{200:{\"description\":\"ok\"}}}}," +
                "\"/snapshot\":{\"get\":{\"summary\":\"Latest JPEG frame\",\"responses\":{200:{\"description\":\"image/jpeg\"}}}}," +
                "\"/stream\":{\"get\":{\"summary\":\"MJPEG live stream (multipart/x-mixed-replace)\",\"responses\":{200:{\"description\":\"multipart stream\"}}}}," +
                "\"/ws\":{\"get\":{\"summary\":\"WebSocket, binary JPEG frames\",\"responses\":{101:{\"description\":\"upgraded\"}}}}," +
                "\"/record/start\":{\"post\":{\"summary\":\"Start MP4 recording\",\"responses\":{200:{\"description\":\"ok\"}}}}," +
                "\"/record/stop\":{\"post\":{\"summary\":\"Stop recording\",\"responses\":{200:{\"description\":\"ok\"}}}}," +
                "\"/qr\":{\"get\":{\"summary\":\"QR code PNG of the server URL\",\"responses\":{200:{\"description\":\"image/png\"}}}}," +
                "\"/health\":{\"get\":{\"summary\":\"Liveness probe\",\"responses\":{200:{\"description\":\"ok\"}}}}" +
                "}}";
    }
}