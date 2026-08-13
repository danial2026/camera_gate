package com.danials.cameragate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import com.danials.cameragate.core.CameraGate;
import com.danials.cameragate.core.LocaleHelper;
import com.danials.cameragate.core.Settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Settings form: port, auth token, camera id, listen address (dropdown),
 * stream resolution (dropdown), status overlay toggle.
 * Values apply on the next server start.
 */
public class SettingsActivity extends Activity {

    private EditText portInput;
    private EditText tokenInput;
    private EditText cameraInput;
    private Spinner addressSpinner;
    private Spinner sizeSpinner;
    private Spinner fpsSpinner;
    private Spinner languageSpinner;
    private CheckBox osdCheck;

    private final List<String> addressValues = new ArrayList<String>();
    private final List<String> sizeValues = new ArrayList<String>();
    private final List<Integer> fpsValues = new ArrayList<Integer>();
    private final List<String> languageValues = new ArrayList<String>();
    private String attachedLang;

    @Override
    protected void attachBaseContext(Context newBase) {
        attachedLang = LocaleHelper.current(newBase);
        super.attachBaseContext(LocaleHelper.apply(newBase, attachedLang));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Recreate whenever the screen was inflated in a different language
        // than currently selected (locale changed in this screen, or the
        // process resources were silently reverted by a config change).
        LocaleHelper.reassert(this, attachedLang);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        LocaleHelper.applyLayoutDirection(this,
                findViewById(android.R.id.content));

        final Settings settings = new Settings(this);
        final CameraGate gate = CameraGateApp.gate();

        portInput = (EditText) findViewById(R.id.input_port);
        tokenInput = (EditText) findViewById(R.id.input_token);
        cameraInput = (EditText) findViewById(R.id.input_camera);
        addressSpinner = (Spinner) findViewById(R.id.spinner_address);
        sizeSpinner = (Spinner) findViewById(R.id.spinner_size);
        fpsSpinner = (Spinner) findViewById(R.id.spinner_fps);
        languageSpinner = (Spinner) findViewById(R.id.spinner_language);
        osdCheck = (CheckBox) findViewById(R.id.check_osd);

        portInput.setText(String.valueOf(settings.getPort()));
        tokenInput.setText(settings.getToken());
        cameraInput.setText(String.valueOf(settings.getCameraId()));
        osdCheck.setChecked(settings.getOsdEnabled());

        setupAddressSpinner(settings);
        setupSizeSpinner(settings, gate);
        setupFpsSpinner(settings);
        setupLanguageSpinner(settings);

        Button save = (Button) findViewById(R.id.btn_save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(settings, gate);
            }
        });

        findViewById(R.id.btn_website).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://danials.org")));
                    }
                });
        findViewById(R.id.btn_source).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                                "https://github.com/danial2026/camera_gate")));
                    }
                });
    }

    private void setupAddressSpinner(Settings settings) {
        List<String> labels = new ArrayList<String>();
        addressValues.clear();

        String saved = settings.getListenAddress();
        boolean hasSaved = false;

        addressValues.add(Settings.LISTEN_ALL);
        labels.add(getString(R.string.addr_all));
        hasSaved |= Settings.LISTEN_ALL.equals(saved);

        addressValues.add("127.0.0.1");
        labels.add(getString(R.string.addr_local));
        hasSaved |= "127.0.0.1".equals(saved);

        List<String> ips = CameraGateApp.gate().ipAddresses();
        for (String ip : ips) {
            if ("127.0.0.1".equals(ip) || Settings.LISTEN_ALL.equals(ip)) {
                continue;
            }
            addressValues.add(ip);
            labels.add(ip);
            hasSaved |= ip.equals(saved);
        }
        if (!hasSaved) {
            addressValues.add(saved);
            labels.add(saved);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(
                R.layout.spinner_dropdown_item);
        addressSpinner.setAdapter(adapter);
        int idx = addressValues.indexOf(saved);
        if (idx >= 0) {
            addressSpinner.setSelection(idx);
        }
    }

    private void setupSizeSpinner(Settings settings, CameraGate gate) {
        List<String> labels = new ArrayList<String>();
        sizeValues.clear();

        sizeValues.add("");
        labels.add(getString(R.string.size_auto));

        String saved = settings.getPreviewSize() == null ? null
                : settings.getPreviewSize()[0] + "x"
                        + settings.getPreviewSize()[1];
        int savedIdx = 0;

        if (gate.isRunning()) {
            // camera is in use by the server; report only the live size
            String live = gate.cameraWidth() + "x" + gate.cameraHeight();
            if (!live.startsWith("0x")) {
                sizeValues.add(live);
                labels.add(live);
                savedIdx = sizeValues.size() - 1;
            }
        } else {
            try {
                Camera cam = Camera.open(settings.getCameraId());
                try {
                    List<Camera.Size> sizes =
                            cam.getParameters().getSupportedPreviewSizes();
                    final long[] cmp = new long[1];
                    java.util.Collections.sort(sizes,
                            new Comparator<Camera.Size>() {
                                @Override
                                public int compare(Camera.Size a, Camera.Size b) {
                                    cmp[0] = (long) b.width * b.height
                                            - (long) a.width * a.height;
                                    return Long.signum(cmp[0]);
                                }
                            });
                    for (Camera.Size s : sizes) {
                        String v = s.width + "x" + s.height;
                        if (sizeValues.contains(v)) {
                            continue;
                        }
                        sizeValues.add(v);
                        labels.add(v);
                        if (v.equals(saved)) {
                            savedIdx = sizeValues.size() - 1;
                        }
                    }
                } finally {
                    cam.release();
                }
            } catch (RuntimeException e) {
                // camera busy or unavailable - only AUTO remains selectable
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(
                R.layout.spinner_dropdown_item);
        sizeSpinner.setAdapter(adapter);
        sizeSpinner.setSelection(savedIdx);
    }

    private void setupFpsSpinner(Settings settings) {
        List<String> labels = new ArrayList<String>();
        fpsValues.clear();

        fpsValues.add(0);
        labels.add(getString(R.string.fps_auto));
        int saved = settings.getFps();
        int savedIdx = 0;
        for (int f : new int[]{5, 10, 15, 20, 30}) {
            fpsValues.add(f);
            labels.add(f + " FPS");
            if (saved == f) {
                savedIdx = fpsValues.size() - 1;
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(
                R.layout.spinner_dropdown_item);
        fpsSpinner.setAdapter(adapter);
        fpsSpinner.setSelection(savedIdx);
    }

    private void setupLanguageSpinner(final Settings settings) {
        List<String> labels = new ArrayList<String>();
        languageValues.clear();

        languageValues.add(LocaleHelper.LANG_EN);
        labels.add(getString(R.string.lang_en));
        languageValues.add(LocaleHelper.LANG_FA);
        labels.add(getString(R.string.lang_fa));

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                R.layout.spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);

        int savedIdx = languageValues.indexOf(settings.getLanguage());
        languageSpinner.setSelection(Math.max(0, savedIdx));

        // The UI language applies immediately: rebuild the screen with the
        // new locale (attachBaseContext re-applies the persisted choice).
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                String picked = languageValues.get(position);
                if (picked != null && !picked.equals(settings.getLanguage())) {
                    settings.setLanguage(picked);
                    // Full task restart: an in-place recreate can leave
                    // stale mixed-language views around on Android 4.x-6.x
                    // (process-wide resources get reverted by config
                    // changes). A fresh task renders everything in the
                    // selected language, like reopening the app.
                    Intent reload = new Intent(SettingsActivity.this,
                            MainActivity.class);
                    reload.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(reload);
                    finish();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void save(Settings settings, CameraGate gate) {
        try {
            int port = Integer.parseInt(portInput.getText().toString().trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            int cameraId = Integer.parseInt(
                    cameraInput.getText().toString().trim());
            int max = Camera.getNumberOfCameras() - 1;
            if (cameraId < 0 || cameraId > max) {
                throw new NumberFormatException();
            }
            settings.setPort(port);
            settings.setToken(tokenInput.getText().toString().trim());
            settings.setCameraId(cameraId);
            int aIdx = addressSpinner.getSelectedItemPosition();
            settings.setListenAddress(addressValues.get(
                    aIdx < 0 ? 0 : aIdx));
            int sIdx = sizeSpinner.getSelectedItemPosition();
            settings.setPreviewSize(sizeValues.get(
                    sIdx < 0 ? 0 : sIdx));
            int fIdx = fpsSpinner.getSelectedItemPosition();
            settings.setFps(fpsValues.get(fIdx < 0 ? 0 : fIdx));
            settings.setOsdEnabled(osdCheck.isChecked());

            if (gate.isRunning()) {
                Toast.makeText(this, R.string.settings_restart_needed,
                        Toast.LENGTH_LONG).show();
            }
            finish();
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.settings_invalid, Toast.LENGTH_LONG)
                    .show();
        }
    }
}