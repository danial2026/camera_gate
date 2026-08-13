package com.danials.cameragate;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Edge-to-edge support for Android 15+ (targetSdk 35/36).
 *
 * Android 16 (API 36) forces every app into edge-to-edge and disables the
 * {@code windowOptOutEdgeToEdgeEnforcement} escape hatch, so the content
 * draws behind the status and navigation bars. This helper keeps the
 * interactive content inside the bars by padding the content root with the
 * system-bar insets, while the (pure black) window background keeps filling
 * the whole screen behind them.
 *
 * No-op below API 30: windows there always fit the system bars, so the
 * delivered insets are zero and nothing changes.
 */
public final class UiInsets {

    private UiInsets() {
    }

    /** Applies the system-bar insets as padding on the content root. */
    public static void apply(View content) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        content.setOnApplyWindowInsetsListener(
                new View.OnApplyWindowInsetsListener() {
                    @Override
                    public WindowInsets onApplyWindowInsets(View v,
                                                            WindowInsets windowInsets) {
                        Insets bars = windowInsets.getInsets(
                                WindowInsets.Type.systemBars()
                                        | WindowInsets.Type.displayCutout());
                        v.setPadding(bars.left, bars.top,
                                bars.right, bars.bottom);
                        return WindowInsets.CONSUMED;
                    }
                });
    }
}