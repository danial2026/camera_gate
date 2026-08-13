package com.danials.cameragate;

import android.app.Application;

import com.danials.cameragate.core.CameraGate;
import com.danials.cameragate.core.LocaleHelper;

/**
 * Process-wide holder for the single {@link CameraGate} instance.
 *
 * The camera server must survive Activity recreation (rotation, backgrounding),
 * so it lives at the Application scope rather than inside a screen.
 */
public class CameraGateApp extends Application {

    private static CameraGate gate;

    public static CameraGate gate() {
        return gate;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Make the persisted language the process default from the start so
        // services, notifications and the server resolve localized strings.
        LocaleHelper.apply(this, LocaleHelper.current(this));
        gate = new CameraGate(this);
    }
}