package com.danials.cameragate.core;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Owns the legacy {@link android.hardware.Camera} instance and exposes it to
 * the HTTP layer.
 *
 * Android 4.x only has the deprecated Camera API (Camera2 requires API 21+),
 * so this class deliberately targets that API with its classic lifecycle:
 * open -> setPreviewSize -> setPreviewCallbackWithBuffer -> startPreview.
 *
 * Recording uses MediaRecorder, which cooperates with the camera through
 * unlock/lock. While MediaRecorder owns the camera, preview callbacks are
 * paused (the platform stops delivering them anyway), so snapshots and the
 * live stream return a 503 "recording in progress" response.
 */
public final class CameraSource {

    private static final String TAG = "CameraGate";

    public static final int STATE_IDLE = 0;
    public static final int STATE_STREAMING = 1;
    public static final int STATE_RECORDING = 2;

    private final JpegFrames frames;

    private Camera camera;
    private int cameraId = -1;
    private int previewWidth;
    private int previewHeight;
    private int displayOrientation;
    private int facing = -1;
    private int state = STATE_IDLE;

    private MediaRecorder recorder;
    private File recordFile;
    private SurfaceTexture previewTexture;
    private boolean audioAvailable = true;

    private final Object lock = new Object();

    public CameraSource(JpegFrames frames) {
        this.frames = frames;
    }

    public int getState() {
        synchronized (lock) {
            return state;
        }
    }

    public int getPreviewWidth() {
        return previewWidth;
    }

    public int getPreviewHeight() {
        return previewHeight;
    }

    public int getDisplayOrientation() {
        return displayOrientation;
    }

    public int getFacing() {
        return facing;
    }

    public File getRecordFile() {
        return recordFile;
    }

    /** Opens the camera and starts delivering frames to [JpegFrames]. */
    public void open(int id, int maxWidth, int reqW, int reqH) {
        synchronized (lock) {
            closeLocked();
            try {
                camera = Camera.open(id);
                cameraId = id;
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(id, info);
                facing = info.facing;
                displayOrientation = computeDisplayOrientation(info);
                camera.setDisplayOrientation(displayOrientation);

                Camera.Size size = pickPreviewSize(
                        camera.getParameters(), maxWidth, reqW, reqH);
                previewWidth = size.width;
                previewHeight = size.height;
                Camera.Parameters params = camera.getParameters();
                params.setPreviewSize(previewWidth, previewHeight);
                params.setPreviewFormat(android.graphics.ImageFormat.NV21);
                camera.setParameters(params);
                frames.start(previewWidth, previewHeight);
                attachPreviewTarget();
                startPreviewLocked();
                state = STATE_STREAMING;
            } catch (Exception e) {
                // Never let a camera HAL error (unsupported size/format,
                // busy hardware, driver quirks on modern devices) crash the
                // process: leave the source closed and let the caller
                // report a graceful "server start failed".
                Log.e(TAG, "camera open/preview failed (id=" + id + ")", e);
                try {
                    closeLocked();
                } catch (RuntimeException ignored) {
                }
                cameraId = -1;
                facing = -1;
            }
        }
    }

    /**
     * The Xperia (and many other Android 4.x drivers) refuse to deliver
     * preview frames unless a preview surface is attached, even in
     * buffer-callback mode. SurfaceTexture (API 11+) gives the camera a real
     * output target without needing a visible SurfaceView, so frames flow
     * even when the server runs headless or after the UI activity is gone.
     */
    private void attachPreviewTarget() {
        try {
            previewTexture = new SurfaceTexture(10);
            camera.setPreviewTexture(previewTexture);
        } catch (Exception e) {
            Log.w(TAG, "setPreviewTexture failed, retrying with null display", e);
            previewTexture = null;
            try {
                camera.setPreviewDisplay(null);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Starts recording an MP4 into the CameraGate folder on the external
     * storage. Returns the output file, or null on failure.
     */
    public File startRecording() {
        synchronized (lock) {
            if (camera == null || state != STATE_STREAMING) {
                return null;
            }
            File dir = new File(
                    Environment.getExternalStorageDirectory(), "CameraGate");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss",
                    Locale.US).format(new Date());
            File out = new File(dir, "cameragate_" + stamp + ".mp4");

            frames.stop();
            camera.stopPreview();
            camera.unlock();
            try {
                recorder = new MediaRecorder();
                boolean ok = configureRecorder(recorder, out);
                if (!ok) {
                    throw new RuntimeException("no usable recorder profile");
                }
                recorder.prepare();
                recorder.start();
                recordFile = out;
                state = STATE_RECORDING;
                Log.i(TAG, "Recording started: " + out.getAbsolutePath());
                return out;
            } catch (Exception e) {
                Log.e(TAG, "record start failed", e);
                releaseRecorderLocked();
                lockAndResumePreviewLocked();
                return null;
            }
        }
    }

    /** Stops recording; returns the output file, or null if nothing was recorded. */
    public File stopRecording() {
        synchronized (lock) {
            if (state != STATE_RECORDING) {
                return null;
            }
            File out = recordFile;
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "recorder.stop() failed (short clip?)", e);
                out = null;
            }
            releaseRecorderLocked();
            lockAndResumePreviewLocked();
            return out;
        }
    }

    public void release() {
        synchronized (lock) {
            closeLocked();
        }
    }

    private boolean configureRecorder(MediaRecorder r, File out) throws IOException {
        // Sony / Xperia 4.3 firmware is picky about both ordering and a
        // preview surface. Configure explicitly in the documented order and
        // never rely on setProfile() (it throws IllegalStateException here).
        int videoCodec = MediaRecorder.VideoEncoder.H264;
        int audioCodec = MediaRecorder.AudioEncoder.AAC;
        try {
            CamcorderProfile p = CamcorderProfile.get(
                    cameraId, CamcorderProfile.QUALITY_LOW);
            videoCodec = p.videoCodec;
            audioCodec = p.audioCodec;
        } catch (RuntimeException ignored) {
            // fall back to the well-known-working values above
        }

        r.setCamera(camera);
        if (audioAvailable) {
            r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        }
        r.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        r.setVideoSize(previewWidth, previewHeight);
        r.setVideoFrameRate(15);
        r.setVideoEncodingBitRate(1200000);
        if (audioAvailable) {
            r.setAudioEncodingBitRate(96000);
            r.setAudioSamplingRate(44100);
        }
        r.setVideoEncoder(videoCodec);
        if (audioAvailable) {
            r.setAudioEncoder(audioCodec);
        }

        r.setOutputFile(out.getAbsolutePath());
        if (previewTexture != null) {
            r.setPreviewDisplay(new Surface(previewTexture));
        }
        return true;
    }

    public boolean isRecording() {
        synchronized (lock) {
            return state == STATE_RECORDING;
        }
    }

    public List<int[]> listPreviewSizes() {
        synchronized (lock) {
            List<int[]> out = new ArrayList<int[]>();
            if (camera == null) {
                return out;
            }
            for (Camera.Size s : camera.getParameters().getSupportedPreviewSizes()) {
                out.add(new int[]{s.width, s.height});
            }
            return out;
        }
    }

    private void startPreviewLocked() {
        camera.setPreviewCallbackWithBuffer(null);
        camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera cam) {
                if (data == null) {
                    return;
                }
                frames.post(data);
                cam.addCallbackBuffer(frames.acquireBuffer());
            }
        });
        int frameBytes = previewWidth * previewHeight * 3 / 2;
        for (int i = 0; i < 4; i++) {
            camera.addCallbackBuffer(new byte[frameBytes]);
        }
        camera.startPreview();
    }

    private void stopPreviewLocked() {
        if (camera == null) {
            return;
        }
        camera.setPreviewCallbackWithBuffer(null);
        camera.stopPreview();
    }

    private void lockAndResumePreviewLocked() {
        if (camera == null) {
            return;
        }
        camera.lock();
        frames.start(previewWidth, previewHeight);
        startPreviewLocked();
        state = STATE_STREAMING;
    }

    private void releaseRecorderLocked() {
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
            }
            recorder.reset();
            recorder.release();
            recorder = null;
        }
    }

    private void closeLocked() {
        if (state == STATE_RECORDING) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
            }
            releaseRecorderLocked();
            recordFile = null;
        }
        if (camera != null) {
            stopPreviewLocked();
            camera.release();
            camera = null;
        }
        frames.stop();
        state = STATE_IDLE;
    }

    /** Standard Android rotation math so streamed frames are upright. */
    private int computeDisplayOrientation(Camera.CameraInfo info) {
        int result = 90;
        int rotation = 0; // we never rotate the Activity's window explicitly
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + rotation) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - rotation + 360) % 360;
        }
        return result;
    }

    private static Camera.Size pickPreviewSize(
            Camera.Parameters params, int maxWidth, int reqW, int reqH) {
        List<Camera.Size> sizes = params.getSupportedPreviewSizes();
        List<Camera.Size> pool = new ArrayList<Camera.Size>(sizes);
        Collections.sort(pool, new Comparator<Camera.Size>() {
            @Override
            public int compare(Camera.Size a, Camera.Size b) {
                long areaA = (long) a.width * a.height;
                long areaB = (long) b.width * b.height;
                return Long.valueOf(areaB).compareTo(areaA);
            }
        });
        if (reqW > 0) {
            // Exact match first, then the largest size no wider than requested.
            for (Camera.Size s : pool) {
                if (s.width == reqW && s.height == reqH) {
                    return s;
                }
            }
            for (Camera.Size s : pool) {
                if (s.width <= reqW) {
                    return s;
                }
            }
        }
        for (Camera.Size s : pool) {
            if (s.width <= maxWidth) {
                return s;
            }
        }
        return pool.get(0);
    }
}