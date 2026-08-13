package com.danials.cameragate;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.danials.cameragate.core.CameraGate;
import com.danials.cameragate.core.JpegFrames;
import com.danials.cameragate.core.LocaleHelper;

import java.util.List;
import java.util.Locale;

/**
 * Main screen, styled after UI_DESIGN_GUIDE.md: black background, card
 * surfaces, uppercase labels, monospace raw data.
 *
 * - Server STOPPED: white CTA "START SERVER", camera preview is live here.
 * - Server RUNNING: red CTA "STOP SERVER", the preview card shows the live
 *   stream from the server (the camera itself is owned by the server).
 */
public class MainActivity extends Activity implements CameraGate.Listener {

    private static final String TAG = "CameraGate";
    private static final int REQ_CAMERA = 1;
    private static final int REQ_BATTERY = 2;

    private CameraGate gate;

    private TextView statusView;
    private TextView cameraView;
    private TextView recordView;
    private TextView urlView;
    private ImageView qrView;
    private LinearLayout connectCard;
    private SurfaceView previewView;
    private ImageView streamView;
    private TextView previewHint;
    private Bitmap streamBmp;
    private long lastStreamAt;
    private Button serverButton;
    private Button recordButton;

    private PreviewCamera preview;
    private String lastQrUrl = "";
    private boolean resumed = false;
    private String attachedLang;

    @Override
    protected void attachBaseContext(Context newBase) {
        attachedLang = LocaleHelper.current(newBase);
        super.attachBaseContext(LocaleHelper.apply(newBase, attachedLang));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        View content = findViewById(android.R.id.content);
        LocaleHelper.applyLayoutDirection(this, content);
        UiInsets.apply(content);

        gate = CameraGateApp.gate();
        gate.addListener(this);
        gate.frames().setListener(new JpegFrames.Listener() {
            @Override
            public void onNewFrame() {
                showStreamFrame();
            }
        });

        statusView = (TextView) findViewById(R.id.status);
        cameraView = (TextView) findViewById(R.id.camera_value);
        recordView = (TextView) findViewById(R.id.record_value);
        urlView = (TextView) findViewById(R.id.urls);
        qrView = (ImageView) findViewById(R.id.qr);
        connectCard = (LinearLayout) findViewById(R.id.card_connect);
        previewView = (SurfaceView) findViewById(R.id.preview);
        streamView = (ImageView) findViewById(R.id.stream_view);
        previewHint = (TextView) findViewById(R.id.preview_hint);
        serverButton = (Button) findViewById(R.id.btn_server);
        recordButton = (Button) findViewById(R.id.btn_record);

        preview = new PreviewCamera(previewView.getHolder());

        serverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleServer();
            }
        });
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRecord();
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(v.getContext(),
                                SettingsActivity.class));
                    }
                });
        findViewById(R.id.btn_refresh).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        refreshUi();
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (LocaleHelper.reassert(this, attachedLang)) {
            return; // recreated with the now-selected language
        }
        resumed = true;
        requestCameraPermissionIfNeeded();
        requestBatteryExemptionIfNeeded();
        refreshUi();
        syncPreview();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (preview != null) {
            preview.want(false);
        }
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.exit_title)
                .setMessage(gate.isRunning()
                        ? R.string.exit_running_msg : R.string.exit_idle_msg)
                .setPositiveButton(R.string.btn_exit,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                gate.stop();
                                finish();
                            }
                        })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        gate.removeListener(this);
        super.onDestroy();
    }

    private void requestCameraPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    /**
     * Optional: on Android 6+ the user can exempt the app from battery
     * optimization so the camera server is not throttled or killed by
     * OEM power managers (no-op on the Android 4.x targets).
     */
    private void requestBatteryExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        Intent intent = new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQ_BATTERY);
    }

    private void toggleServer() {
        if (gate.isRunning()) {
            gate.stop();
            CameraGateService.stop(this);
        } else {
            preview.want(false);
            if (!gate.start()) {
                Toast.makeText(this, R.string.server_start_failed,
                        Toast.LENGTH_LONG).show();
                preview.want(true);
            } else {
                CameraGateService.start(this);
            }
        }
        refreshUi();
        syncPreview();
    }

    private void toggleRecord() {
        if (!gate.isRunning() || !gate.cameraOpen()) {
            Toast.makeText(this, R.string.record_need_server,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (gate.recording()) {
            java.io.File f = gate.stopRecording();
            Toast.makeText(this, f == null
                            ? getString(R.string.record_stopped_nothing)
                            : getString(R.string.record_saved, f.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        } else {
            java.io.File f = gate.startRecording();
            Toast.makeText(this, f == null
                            ? getString(R.string.record_start_failed)
                            : getString(R.string.record_started, f.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        }
        refreshUi();
    }

    /** Routes the camera to the preview (server stopped) or the server (running). */
    private void syncPreview() {
        if (gate == null) {
            return;
        }
        preview.want(!gate.isRunning() && resumed);
    }

    @Override
    public void onStateChanged() {
        refreshUi();
        syncPreview();
    }

    private void refreshUi() {
        if (gate == null) {
            return;
        }
        boolean running = gate.isRunning();

        // ---- server status card ----
        if (running) {
            statusView.setText(getString(R.string.status_running,
                    gate.primaryBaseUrl()));
            statusView.setTextColor(getResources().getColor(R.color.success));
        } else {
            statusView.setText(R.string.status_stopped);
            statusView.setTextColor(getResources().getColor(R.color.danger));
        }

        // ---- camera card ----
        String cam = String.format(Locale.US, "%d · %s",
                gate.cameraId(), gate.cameraFacing());
        if (gate.cameraOpen()) {
            cam += String.format(Locale.US, " · %dx%d · OPEN",
                    gate.cameraWidth(), gate.cameraHeight());
            cameraView.setTextColor(getResources().getColor(R.color.success));
        } else {
            cam += " · CLOSED";
            cameraView.setTextColor(getResources().getColor(R.color.danger));
        }
        cameraView.setText(cam);

        // ---- record card ----
        if (gate.recording()) {
            recordView.setText(getString(R.string.recording_on,
                    gate.recordingFile()));
            recordView.setTextColor(getResources().getColor(R.color.warning));
        } else {
            recordView.setText(R.string.recording_off);
            recordView.setTextColor(getResources().getColor(R.color.text_secondary));
        }

        // ---- addresses card ----
        // Always reveal the detected LAN address(es), even when stopped, so
        // the user isn't confused by seeing none while the phone is on wifi.
        List<String> ips = gate.ipAddresses();
        if (ips.isEmpty()) {
            urlView.setText(R.string.no_address);
            urlView.setTextColor(getResources().getColor(R.color.text_secondary));
        } else {
            StringBuilder urls = new StringBuilder();
            for (int i = 0; i < ips.size(); i++) {
                if (i > 0) {
                    urls.append('\n');
                }
                urls.append("http://").append(ips.get(i)).append(':')
                        .append(gate.port());
            }
            urlView.setText(urls.toString());
            urlView.setTextColor(running
                    ? getResources().getColor(R.color.success)
                    : getResources().getColor(R.color.text_primary));
        }
        if (running) {
            connectCard.setVisibility(View.VISIBLE);
            refreshQr();
        } else {
            connectCard.setVisibility(View.GONE);
        }

        // ---- action buttons ----
        if (running) {
            serverButton.setText(R.string.stop_server);
            serverButton.setBackgroundResource(R.drawable.btn_danger);
            recordButton.setEnabled(true);
            recordButton.setText(gate.recording()
                    ? R.string.stop_recording : R.string.start_recording);
            recordButton.setBackgroundResource(gate.recording()
                    ? R.drawable.btn_danger : R.drawable.btn_outline);
        } else {
            serverButton.setText(R.string.start_server);
            serverButton.setBackgroundResource(R.drawable.btn_primary);
            // Always tappable: it shows a hint instead of silently no-op'ing
            // when the server/camera is not available.
            recordButton.setText(R.string.start_recording);
            recordButton.setEnabled(true);
            recordButton.setBackgroundResource(R.drawable.btn_outline);
        }

        // ---- preview ----
        boolean showPreview = !running;
        previewView.setVisibility(showPreview ? View.VISIBLE : View.GONE);
        streamView.setVisibility(running ? View.VISIBLE : View.GONE);
        previewHint.setVisibility(showPreview ? View.GONE : View.VISIBLE);
    }

    private void refreshQr() {
        String url = gate.primaryBaseUrl();
        if (!url.equals(lastQrUrl)) {
            lastQrUrl = url;
            byte[] png = gate.qrPng();
            if (png != null) {
                Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (bmp != null) {
                    qrView.setImageBitmap(bmp);
                }
            }
        }
    }

    /**
     * Shows the server's live frame in the preview card while it runs
     * (the camera itself is owned by the server then). One decode per
     * frame on the main thread is too heavy, so the JPEG is scaled down
     * to the view width and updates are throttled.
     */
    private void showStreamFrame() {
        if (streamView == null || !resumed || !gate.isRunning()
                || streamView.getVisibility() != View.VISIBLE) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastStreamAt < 150) {
            return;
        }
        lastStreamAt = now;
        byte[] jpeg = gate.frames().latest();
        if (jpeg == null) {
            return;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        int sample = 1;
        int viewW = streamView.getWidth();
        while (viewW > 0 && bounds.outWidth / (sample * 2) >= viewW) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        if (bmp == null) {
            return;
        }
        streamView.setImageBitmap(bmp);
        if (streamBmp != null) {
            streamBmp.recycle();
        }
        streamBmp = bmp;
    }

    // -------------------------------------------------------------- preview

    /**
     * Tiny wrapper around the legacy Camera API driving the preview
     * SurfaceView. Only active while the HTTP server is stopped.
     */
    private final class PreviewCamera implements SurfaceHolder.Callback {
        private android.hardware.Camera cam;
        private boolean wanted = false;
        private SurfaceHolder holder;
        private int lastW;
        private int lastH;

        PreviewCamera(SurfaceHolder h) {
            holder = h;
            holder.addCallback(this);
        }

        synchronized void want(boolean w) {
            wanted = w;
            if (wanted && lastW > 0 && lastH > 0) {
                openLocked();
            } else if (!wanted) {
                closeLocked();
            }
        }

        @Override
        public void surfaceCreated(SurfaceHolder h) {
            holder = h;
        }

        @Override
        public void surfaceChanged(SurfaceHolder h, int format, int w, int height) {
            lastW = w;
            lastH = height;
            if (wanted && w > 0 && height > 0) {
                openLocked();
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder h) {
            closeLocked();
            lastW = 0;
            lastH = 0;
        }

        private void openLocked() {
            if (cam != null) {
                return;
            }
            try {
                cam = android.hardware.Camera.open(gate.cameraId());
                cam.setDisplayOrientation(90);
                android.hardware.Camera.Parameters p = cam.getParameters();
                android.hardware.Camera.Size size = pickSize(
                        p.getSupportedPreviewSizes(), holder.getSurfaceFrame().width(),
                        holder.getSurfaceFrame().height());
                p.setPreviewSize(size.width, size.height);
                cam.setParameters(p);
                cam.setPreviewDisplay(holder);
                cam.startPreview();
                Log.i(TAG, "activity preview started " + size.width + "x" + size.height);
            } catch (Exception e) {
                Log.e(TAG, "preview failed", e);
                closeLocked();
            }
        }

        private void closeLocked() {
            if (cam == null) {
                return;
            }
            try {
                cam.setPreviewCallback(null);
                cam.stopPreview();
            } catch (RuntimeException ignored) {
            }
            cam.release();
            cam = null;
        }

        private android.hardware.Camera.Size pickSize(
                List<android.hardware.Camera.Size> sizes, int w, int h) {
            android.hardware.Camera.Size best = sizes.get(0);
            long want = (long) w * h;
            for (android.hardware.Camera.Size s : sizes) {
                long area = (long) s.width * s.height;
                if (Math.abs(area - want) < Math.abs(
                        (long) best.width * best.height - want)) {
                    best = s;
                }
            }
            return best;
        }
    }
}