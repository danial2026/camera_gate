package com.danials.cameragate.core;

import android.util.Log;

import com.danials.cameragate.BuildConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal hand-rolled HTTP server.
 *
 * Android has no built-in HttpServer (com.sun.net.httpserver is absent from
 * the platform), so this class implements just enough HTTP/1.1 on top of a
 * ServerSocket: request-line + headers parsing, query-string handling, and
 * per-connection handling threads. Long-lived connections (MJPEG, WebSocket)
 * are served in longer-running worker threads that share one camera frame
 * stream. Everything else is Connection: close.
 */
public final class HttpServer {

    private static final String TAG = "CameraGate";
    private static final byte[] NOT_FOUND = "{\"error\":\"not found\"}"
            .getBytes(java.nio.charset.Charset.forName("UTF-8"));
    // per-client cap for stream writers: always the newest frame, never the
    // backlog, or a slow consumer falls minutes behind over hours
    private static final int STREAM_MIN_INTERVAL_MS = 1000 / 12;
    // a frame that takes this long to push means the client is dead or
    // hopelessly slow: cut it loose so a zombie cannot park a pool thread
    private static final int STREAM_STALL_MS = 3000;

    private final CameraGate gate;

    private ServerSocket serverSocket;
    private final ExecutorService pool =
            Executors.newFixedThreadPool(6, new java.util.concurrent.ThreadFactory() {
                private int n = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "cameragate-http-" + (++n));
                    t.setDaemon(true);
                    return t;
                }
            });
    private volatile boolean running = false;

    public HttpServer(CameraGate gate) {
        this.gate = gate;
    }

    public synchronized boolean start(String address, int port) {
        if (running) {
            return true;
        }
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(address, port));
        } catch (IOException e) {
            Log.e(TAG, "bind failed on " + address + ":" + port, e);
            return false;
        }
        running = true;
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "cameragate-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        Log.i(TAG, "HTTP server listening on " + address + ":" + port);
        return true;
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleConnection(socket);
                    }
                });
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept failed", e);
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            long t0 = System.currentTimeMillis();
            Request req = Request.parse(socket);
            if (req == null) {
                socket.close();
                return;
            }
            Log.i(TAG, req.method + " " + req.path + " from "
                    + socket.getRemoteSocketAddress());
            route(socket, req);
            long ms = System.currentTimeMillis() - t0;
            if (ms > 2000) {
                Log.i(TAG, "long request took " + ms + "ms: " + req.path);
            }
        } catch (IOException e) {
            if (running) {
                Log.w(TAG, "connection error", e);
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- routes

    private void route(Socket socket, Request req) throws IOException {
        final String p = req.path;
        if (p.equals("/")) {
            respondHtml(socket, 200, gate.dashboardHtml());
            return;
        }
        if (p.equals("/health")) {
            respondJson(socket, 200, "{\"ok\":true,\"app\":\"CameraGate\"}");
            return;
        }
        if (!gate.authorized(req.headers.get("authorization"),
                req.query.get("token"))) {
            respondJson(socket, 401, "{\"error\":\"unauthorized\"," +
                    "\"hint\":\"pass the token via ?token= or 'Authorization: Bearer <token>'\"}");
            return;
        }

        if (p.equals("/camera")) {
            respondJson(socket, 200, gate.cameraInfoJson());
        } else if (p.equals("/snapshot") && req.method.equals("GET")) {
            handleSnapshot(socket);
        } else if (p.equals("/stream") && req.method.equals("GET")) {
            handleMjpeg(socket, req);
        } else if (p.equals("/ws") && req.method.equals("GET")) {
            handleWebSocket(socket, req);
        } else if (p.equals("/record/start") && req.method.equals("POST")) {
            handleRecordStart(socket);
        } else if (p.equals("/record/stop") && req.method.equals("POST")) {
            handleRecordStop(socket);
        } else if (p.equals("/qr") && req.method.equals("GET")) {
            handleQr(socket);
        } else if (p.equals("/swagger")) {
            respondJson(socket, 200, gate.swaggerJson());
        } else if (p.equals("/favicon.ico")) {
            socket.close();
        } else {
            respondJson(socket, 404, NOT_FOUND);
        }
    }

    private void handleSnapshot(Socket socket) throws IOException {
        if (gate.recording()) {
            respondJson(socket, 503, "{\"error\":\"recording in progress\"}");
            return;
        }
        byte[] jpeg = gate.snapshotJpeg();
        if (jpeg == null) {
            respondJson(socket, 503, "{\"error\":\"no frame available yet\"}");
            return;
        }
        respond(socket, 200, "image/jpeg", jpeg,
                "Cache-Control: no-store");
    }

    private void handleMjpeg(Socket socket, Request req) throws IOException {
        if (gate.recording()) {
            respondJson(socket, 503, "{\"error\":\"recording in progress\"}");
            return;
        }
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
        String boundary = "----cameragateframe";
        respondHeaders(socket, 200,
                "multipart/x-mixed-replace; boundary=" + boundary, -1,
                "Cache-Control: no-store", "Connection: close");
        out.flush();

        long seq = gate.frames().latestSeq();
        byte[] last = null;
        long lastWriteAt = 0;
        long idle = 0;
        while (running) {
            byte[] jpeg = gate.frames().latest();
            long now = System.currentTimeMillis();
            if (jpeg != null && jpeg != last
                    && now - lastWriteAt >= STREAM_MIN_INTERVAL_MS) {
                last = jpeg;
                lastWriteAt = now;
                byte[] header = ("\r\n--" + boundary + "\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + jpeg.length + "\r\n\r\n")
                        .getBytes(java.nio.charset.Charset.forName("US-ASCII"));
                out.write(header);
                out.write(jpeg);
                out.flush();
                if (System.currentTimeMillis() - lastWriteAt > STREAM_STALL_MS) {
                    Log.w(TAG, "dropping stalled /stream client");
                    break;
                }
            }
            seq = gate.frames().awaitNewer(seq, 200);
            if (++idle % 50 == 0 && socket.isClosed()) {
                break;
            }
        }
        out.write(("\r\n--" + boundary + "--\r\n")
                .getBytes(java.nio.charset.Charset.forName("US-ASCII")));
        out.flush();
    }

    private void handleWebSocket(Socket socket, Request req) throws IOException {
        if (gate.recording()) {
            respondJson(socket, 503, "{\"error\":\"recording in progress\"}");
            return;
        }
        String key = req.headers.get("sec-websocket-key");
        if (key == null) {
            respondJson(socket, 400, "{\"error\":\"not a websocket handshake\"}");
            return;
        }
        String accept;
        try {
            accept = websocketAccept(key);
        } catch (NoSuchAlgorithmException e) {
            respondJson(socket, 500, "{\"error\":\"sha1 unavailable\"}");
            return;
        }
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
        respondHeaders(socket, 101,
                "text/plain", -1, // content type is ignored on 101
                "Connection: Upgrade", "Upgrade: websocket",
                "Sec-WebSocket-Accept: " + accept);
        out.flush();

        socket.setSoTimeout(1000);
        InputStream in = socket.getInputStream();
        Log.i(TAG, "ws client connected: " + socket.getRemoteSocketAddress());

        long seq = gate.frames().latestSeq();
        long lastWriteAt = 0;
        while (running) {
            byte[] jpeg = gate.frames().latest();
            long now = System.currentTimeMillis();
            if (jpeg != null && gate.frames().latestSeq() != seq
                    && now - lastWriteAt >= STREAM_MIN_INTERVAL_MS) {
                seq = gate.frames().latestSeq();
                lastWriteAt = now;
                Log.i(TAG, "ws send frame size=" + jpeg.length);
                writeWsFrame(out, jpeg);
                out.flush();
                if (System.currentTimeMillis() - lastWriteAt > STREAM_STALL_MS) {
                    Log.w(TAG, "dropping stalled /ws client");
                    break;
                }
            }
            try {
                int b = in.read();
                if (b == -1) {
                    break; // client closed
                }
                if ((b & 0x0F) == 0x8) {
                    break; // client close frame
                }
            } catch (SocketTimeoutException e) {
                // polling timeout: keep streaming
            } catch (IOException e) {
                break;
            }
        }
    }

    private static void writeWsFrame(OutputStream out, byte[] payload) throws IOException {
        out.write(0x82); // FIN + binary opcode
        int n = payload.length;
        if (n < 126) {
            out.write(n);
            out.write(payload);
        } else if (n < 65536) {
            out.write(126);                  // 2-byte extended length
            out.write(n >> 8);
            out.write(n);
            out.write(payload);
        } else {
            out.write(127);                  // 8-byte extended length
            for (int i = 7; i >= 0; i--) {
                out.write(n >>> (8 * i));
            }
            out.write(0x41);                 // MARKER A
            out.write(0x42);                 // MARKER B
            out.write(payload);
        }
    }

    private static String websocketAccept(String key) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                        .getBytes(java.nio.charset.Charset.forName("US-ASCII")));
        return android.util.Base64.encodeToString(hash,
                android.util.Base64.NO_WRAP);
    }

    private void handleRecordStart(Socket socket) throws IOException {
        java.io.File f = gate.startRecording();
        if (f == null) {
            respondJson(socket, 500, "{\"error\":\"failed to start recording\"}");
            return;
        }
        respondJson(socket, 200, "{\"status\":\"recording\"," +
                "\"file\":\"" + f.getAbsolutePath() + "\"}");
    }

    private void handleRecordStop(Socket socket) throws IOException {
        java.io.File f = gate.stopRecording();
        if (f == null) {
            respondJson(socket, 200, "{\"status\":\"idle\"}");
            return;
        }
        respondJson(socket, 200, "{\"status\":\"saved\"," +
                "\"file\":\"" + f.getAbsolutePath() + "\"}");
    }

    private void handleQr(Socket socket) throws IOException {
        byte[] png = gate.qrPng();
        if (png == null) {
            respondJson(socket, 500, "{\"error\":\"qr generation failed\"}");
            return;
        }
        respond(socket, 200, "image/png", png, "Cache-Control: no-store");
    }

    // ------------------------------------------------------------- response

    private void respondJson(Socket socket, int status, String body) throws IOException {
        respond(socket, status, "application/json; charset=utf-8",
                body.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                "Cache-Control: no-store");
    }

    private void respondJson(Socket socket, int status, byte[] body) throws IOException {
        respond(socket, status, "application/json; charset=utf-8", body,
                "Cache-Control: no-store");
    }

    private void respondHtml(Socket socket, int status, String body) throws IOException {
        respond(socket, status, "text/html; charset=utf-8",
                body.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                "Cache-Control: no-store");
    }

    private void respond(Socket socket, int status, String contentType,
                         byte[] body, String... extra) throws IOException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        respondHeaders(socket, status, contentType, body.length, extra);
        out.write(body);
        out.flush();
    }

    private void respondHeaders(Socket socket, int status, String contentType,
                                int contentLength, String... extra) throws IOException {
        OutputStream out = socket.getOutputStream();
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status))
                .append("\r\n")
                .append("Server: CameraGate/").append(BuildConfig.VERSION_NAME).append("\r\n")
                .append("Date: ").append(httpDate()).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n");
        for (String e : extra) {
            sb.append(e).append("\r\n");
        }
        if (contentLength >= 0) {
            sb.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        if (status != 101) {
            // everything but the WebSocket upgrade is one-shot
            sb.append("Connection: close\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(java.nio.charset.Charset.forName("US-ASCII")));
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 101: return "Switching Protocols";
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "";
        }
    }

    private static String httpDate() {
        return new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                Locale.US).format(new Date());
    }

    // -------------------------------------------------------------- request

    /** A single parsed HTTP request (request line + headers + query params). */
    private static final class Request {
        final String method;
        final String path;
        final Map<String, String> query = new HashMap<String, String>();
        final Map<String, String> headers = new HashMap<String, String>();

        Request(String method, String path) {
            this.method = method;
            this.path = path;
        }

        /** Reads request line + headers; returns null on malformed input. */
        static Request parse(Socket socket) throws IOException {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            int total = 0;
            List<String> headLines = new ArrayList<String>();
            boolean done = false;
            while (!done) {
                int b = in.read();
                if (b == -1) {
                    return null;
                }
                total++;
                if (total > 16 * 1024) {
                    return null;
                }
                if (b == '\n') {
                    String l = line.toString("UTF-8").replace("\r", "");
                    if (l.length() == 0) {
                        done = true;
                    } else {
                        headLines.add(l);
                    }
                    line.reset();
                } else {
                    line.write(b);
                }
            }
            if (headLines.isEmpty()) {
                return null;
            }
            String[] first = headLines.get(0).split(" ");
            if (first.length < 2) {
                return null;
            }
            String rawPath = first[1];
            int q = rawPath.indexOf('?');
            String pathPart = q >= 0 ? rawPath.substring(0, q) : rawPath;
            Request req = new Request(first[0], pathPart);
            if (q >= 0) {
                for (String pair : rawPath.substring(q + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        req.query.put(urlDecode(pair.substring(0, eq)),
                                urlDecode(pair.substring(eq + 1)));
                    }
                }
            }
            for (int i = 1; i < headLines.size(); i++) {
                int c = headLines.get(i).indexOf(':');
                if (c > 0) {
                    req.headers.put(headLines.get(i).substring(0, c).trim().toLowerCase(Locale.US),
                            headLines.get(i).substring(c + 1).trim());
                }
            }
            return req;
        }

        static String urlDecode(String s) {
            try {
                return java.net.URLDecoder.decode(s, "UTF-8");
            } catch (Exception e) {
                return s;
            }
        }
    }

    // ------------------------------------------------------------ public API
}