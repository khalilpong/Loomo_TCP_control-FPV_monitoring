package com.example.loomoremotepro;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Phone-side FPV controller for TcpPro.
 *
 * Control path:
 *   JoystickView -> normalized X/Y -> currentForward/currentTurn -> periodic JOY TCP command
 *
 * Video path:
 *   Loom camera JPEG frames -> video socket -> decode -> ImageView
 *
 * Both paths are intentionally separated so large video frames cannot block
 * high-priority driving commands.
 */
public class MainActivity extends Activity {
    private static final int FRAME_MAGIC = 0x54565031; // TVP1
    // JOY is sent at a fixed cadence so Loom receives a steady command stream
    // instead of irregular bursts tied to UI frame rate.
    private static final long JOY_SEND_MS = 60L;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int MAX_LOG_LINES = 120;
    private static final long PING_INTERVAL_MS = 3000L;
    // Frames older than this are considered stale and are dropped instead of
    // displayed. Lower effective FPS is preferable to showing very old video.
    private static final long MAX_VIDEO_FRAME_AGE_MS = 400L;
    private static final float DEFAULT_DRIVE_SCALE = 0.70f;
    private static final float DEFAULT_TURN_SCALE = 0.95f;
    // Same pivot-assist idea as Loom side: if the user is mostly asking to turn,
    // we suppress tiny accidental forward input to help pure in-place rotation.
    private static final float PIVOT_ASSIST_MIN_TURN = 0.45f;
    private static final float PIVOT_ASSIST_MAX_FORWARD = 0.18f;

    private static final int COL_STATUS_CONNECTED = 0xFF3FB950;
    private static final int COL_STATUS_CONNECTING = 0xFFD29922;
    private static final int COL_STATUS_DISCONNECTED = 0xFFDA3633;
    private static final int COL_STATUS_ESTOP = 0xFFF85149;

    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService joyExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object controlWriterLock = new Object();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    private EditText hostInput;
    private EditText controlPortInput;
    private EditText videoPortInput;
    private TextView statusText;
    private TextView telemetryText;
    private TextView currentJoyText;
    private TextView driveScaleText;
    private TextView turnScaleText;
    private TextView logText;
    private TextView rttText;
    private TextView videoStatusText;
    private TextView videoHintText;
    private View statusDot;
    private ImageView videoView;
    private JoystickView joystickView;
    private SeekBar driveScaleSeekBar;
    private SeekBar turnScaleSeekBar;

    private Socket controlSocket;
    private PrintWriter controlWriter;
    private BufferedReader controlReader;
    private Socket videoSocket;
    private DataInputStream videoReader;
    private Bitmap currentVideoBitmap;
    private int videoFramesThisWindow = 0;
    private long videoFpsWindowStart = 0L;
    private int videoFps = 0;

    private volatile boolean controlConnected = false;
    private volatile boolean videoConnected = false;
    private volatile float currentForward = 0f;
    private volatile float currentTurn = 0f;
    private volatile boolean joystickActive = false;
    private volatile boolean zeroStopSent = false;
    private volatile long lastPingSentAt = 0L;
    private boolean suppressJoystickCallback = false;
    private ScheduledFuture<?> joyLoopTask;
    private Runnable pingRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        hostInput = findViewById(R.id.hostInput);
        controlPortInput = findViewById(R.id.controlPortInput);
        videoPortInput = findViewById(R.id.videoPortInput);
        statusText = findViewById(R.id.statusText);
        telemetryText = findViewById(R.id.telemetryText);
        currentJoyText = findViewById(R.id.currentJoyText);
        driveScaleText = findViewById(R.id.driveScaleText);
        turnScaleText = findViewById(R.id.turnScaleText);
        logText = findViewById(R.id.logText);
        rttText = findViewById(R.id.rttText);
        videoStatusText = findViewById(R.id.videoStatusText);
        videoHintText = findViewById(R.id.videoHintText);
        statusDot = findViewById(R.id.statusDot);
        videoView = findViewById(R.id.videoView);
        joystickView = findViewById(R.id.joystickView);
        driveScaleSeekBar = findViewById(R.id.driveScaleSeekBar);
        turnScaleSeekBar = findViewById(R.id.turnScaleSeekBar);
        logText.setMovementMethod(new ScrollingMovementMethod());

        Button connectButton = findViewById(R.id.connectButton);
        Button disconnectButton = findViewById(R.id.disconnectButton);
        Button clearLogButton = findViewById(R.id.clearLogButton);

        connectButton.setOnClickListener(v -> connect());
        disconnectButton.setOnClickListener(v -> disconnect());
        clearLogButton.setOnClickListener(v -> clearLog());

        updateCurrentJoyText();
        initScaleControls();
        updateTelemetryDisplay("", "", "0.000", "0.000", "0.000", "0.0", "0");
        postStatus("Disconnected");
        setStatusDotColor(COL_STATUS_DISCONNECTED);
        setJoystickEnabled(false);
        setVideoStatus("VIDEO OFF", true);

        joystickView.setOnJoystickListener((normalizedX, normalizedY, active) -> {
            if (suppressJoystickCallback) return;

            joystickActive = active;
            // Android touch Y grows downward, but for driving "push up" should be
            // positive forward, so we invert Y here.
            float forwardNorm = -normalizedY;
            float turnNorm = normalizedX;
            if (Math.abs(turnNorm) >= PIVOT_ASSIST_MIN_TURN
                    && Math.abs(forwardNorm) <= PIVOT_ASSIST_MAX_FORWARD) {
                forwardNorm = 0f;
            }
            // Drive/turn sliders scale the normalized stick before the result is
            // serialized into JOY commands. Range remains clamped to [-1, 1].
            currentForward = clamp(forwardNorm * getDriveScale(), -1f, 1f);
            currentTurn = clamp(turnNorm * getTurnScale(), -1f, 1f);
            updateCurrentJoyText();
            if (active) {
                ensureJoyLoop();
            } else {
                stopMotion(true);
            }
        });
    }

    private void initScaleControls() {
        driveScaleSeekBar.setMax(100);
        turnScaleSeekBar.setMax(100);
        driveScaleSeekBar.setProgress(Math.round(DEFAULT_DRIVE_SCALE * 100f));
        turnScaleSeekBar.setProgress(Math.round(DEFAULT_TURN_SCALE * 100f));
        updateScaleLabels();
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateScaleLabels();
                updateCurrentJoyText();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        driveScaleSeekBar.setOnSeekBarChangeListener(listener);
        turnScaleSeekBar.setOnSeekBarChangeListener(listener);
    }

    private void connect() {
        if (controlConnected) return;
        final String host = hostInput.getText().toString().trim();
        final String controlPortText = controlPortInput.getText().toString().trim();
        final String videoPortText = videoPortInput.getText().toString().trim();
        if (host.isEmpty() || controlPortText.isEmpty() || videoPortText.isEmpty()) {
            appendLog("Host or port is empty");
            return;
        }

        final int controlPort;
        final int videoPort;
        try {
            controlPort = Integer.parseInt(controlPortText);
            videoPort = Integer.parseInt(videoPortText);
        } catch (NumberFormatException ex) {
            appendLog("Invalid control/video port");
            return;
        }

        postStatus("Connecting...");
        setStatusDotColor(COL_STATUS_CONNECTING);
        setVideoStatus("VIDEO CONNECTING", true);
        appendLog("Connecting control " + host + ":" + controlPort + " and video :" + videoPort + " ...");

        ioExecutor.execute(() -> {
            if (!connectControl(host, controlPort)) {
                return;
            }
            connectVideo(host, videoPort);
        });
    }

    private boolean connectControl(String host, int port) {
        try {
            controlSocket = new Socket();
            // Disable Nagle so tiny control packets leave immediately.
            controlSocket.setTcpNoDelay(true);
            controlSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            controlWriter = new PrintWriter(controlSocket.getOutputStream(), true);
            controlReader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
            controlConnected = true;
            postStatus("Connected / CTRL");
            setStatusDotColor(COL_STATUS_CONNECTED);
            appendLog("Control connected to " + host + ":" + port);
            mainHandler.post(() -> setJoystickEnabled(true));
            startControlReaderLoop();
            sendControlCommand("PING", true);
            sendControlCommand("STATUS", false);
            startPingLoop();
            return true;
        } catch (IOException e) {
            appendLog("Control connect failed: " + e.getMessage());
            postStatus("Disconnected");
            setStatusDotColor(COL_STATUS_DISCONNECTED);
            disconnectInternal();
            return false;
        }
    }

    private void connectVideo(String host, int port) {
        ioExecutor.execute(() -> {
            try {
                videoSocket = new Socket();
                // Video also uses TCP_NODELAY to keep latency lower for JPEG frames.
                videoSocket.setTcpNoDelay(true);
                videoSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                videoReader = new DataInputStream(videoSocket.getInputStream());
                videoConnected = true;
                videoFramesThisWindow = 0;
                videoFpsWindowStart = 0L;
                videoFps = 0;
                setVideoStatus("VIDEO CONNECTED", true);
                appendLog("Video connected to " + host + ":" + port);
                startVideoReaderLoop();
            } catch (IOException e) {
                appendLog("Video connect failed: " + e.getMessage());
                setVideoStatus("VIDEO OFF", true);
                closeVideoInternal();
            }
        });
    }

    private void startControlReaderLoop() {
        ioExecutor.execute(() -> {
            try {
                String line;
                while (controlConnected && controlReader != null && (line = controlReader.readLine()) != null) {
                    handleControlLine(line);
                }
            } catch (IOException e) {
                if (controlConnected) appendLog("Control read error: " + e.getMessage());
            } finally {
                disconnectInternal();
            }
        });
    }

    private void startVideoReaderLoop() {
        ioExecutor.execute(() -> {
            try {
                while (controlConnected && videoConnected && videoReader != null) {
                    // Frame layout must match Loom's TcpVideoSrv:
                    // [magic][width][height][timestamp][jpeg length][jpeg bytes]
                    int magic = videoReader.readInt();
                    if (magic != FRAME_MAGIC) {
                        throw new IOException("Invalid frame magic: " + magic);
                    }
                    int width = videoReader.readInt();
                    int height = videoReader.readInt();
                    long timestampMs = videoReader.readLong();
                    int length = videoReader.readInt();
                    if (length <= 0 || length > 2_000_000) {
                        throw new IOException("Invalid frame length: " + length);
                    }
                    byte[] bytes = new byte[length];
                    videoReader.readFully(bytes);
                    long ageMs = Math.max(0L, System.currentTimeMillis() - timestampMs);
                    if (ageMs > MAX_VIDEO_FRAME_AGE_MS) {
                        // Showing a badly delayed frame makes FPV harder to drive.
                        // Drop it and wait for a newer frame instead.
                        continue;
                    }
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, length);
                    if (bitmap != null) {
                        displayVideoFrame(bitmap, width, height, timestampMs);
                    }
                }
            } catch (IOException e) {
                if (videoConnected) appendLog("Video read error: " + e.getMessage());
            } finally {
                closeVideoInternal();
            }
        });
    }

    private void handleControlLine(String line) {
        appendLog(line);
        if (line.startsWith("[tcp-pro] status:")) {
            String payload = line.substring("[tcp-pro] status:".length()).trim();
            parseAndDisplayTelemetry(payload);
            String mode = extractField(payload, "mode");
            String estop = extractField(payload, "estop");
            if ("1".equals(estop)) {
                postStatus("E-STOP ACTIVE");
                setStatusDotColor(COL_STATUS_ESTOP);
            } else if (!mode.isEmpty()) {
                postStatus(videoConnected ? "Connected / CTRL+VIDEO / " + mode : "Connected / CTRL / " + mode);
                setStatusDotColor(COL_STATUS_CONNECTED);
            }
        } else if (line.startsWith("[tcp-pro] PONG")) {
            if (lastPingSentAt > 0L) {
                long rtt = System.currentTimeMillis() - lastPingSentAt;
                mainHandler.post(() -> rttText.setText(String.format(Locale.US, "RTT %dms", rtt)));
            }
            postStatus(videoConnected ? "Connected / CTRL+VIDEO" : "Connected / CTRL");
            setStatusDotColor(COL_STATUS_CONNECTED);
        } else if (line.startsWith("[tcp-pro] E-STOP")) {
            postStatus("E-STOP ACTIVE");
            setStatusDotColor(COL_STATUS_ESTOP);
        } else if (line.startsWith("[tcp-pro] RESET")) {
            postStatus(videoConnected ? "Connected / CTRL+VIDEO / IDLE" : "Connected / CTRL / IDLE");
            setStatusDotColor(COL_STATUS_CONNECTED);
        }
    }

    private void disconnect() {
        ioExecutor.execute(this::disconnectInternal);
    }

    private synchronized void disconnectInternal() {
        controlConnected = false;
        joystickActive = false;
        zeroStopSent = false;
        stopJoyLoop();
        stopPingLoop();
        closeVideoInternal();
        try {
            if (controlReader != null) controlReader.close();
        } catch (IOException ignored) {
        }
        if (controlWriter != null) controlWriter.close();
        try {
            if (controlSocket != null) controlSocket.close();
        } catch (IOException ignored) {
        }
        controlReader = null;
        controlWriter = null;
        controlSocket = null;
        postStatus("Disconnected");
        setStatusDotColor(COL_STATUS_DISCONNECTED);
        mainHandler.post(() -> {
            setJoystickEnabled(false);
            rttText.setText("");
            updateTelemetryDisplay("", "", "0.000", "0.000", "0.000", "0.0", "0");
        });
    }

    private synchronized void closeVideoInternal() {
        videoConnected = false;
        try {
            if (videoReader != null) videoReader.close();
        } catch (IOException ignored) {
        }
        try {
            if (videoSocket != null) videoSocket.close();
        } catch (IOException ignored) {
        }
        videoReader = null;
        videoSocket = null;
        videoFramesThisWindow = 0;
        videoFpsWindowStart = 0L;
        videoFps = 0;
        mainHandler.post(() -> {
            videoView.setImageDrawable(null);
            if (currentVideoBitmap != null) {
                currentVideoBitmap.recycle();
                currentVideoBitmap = null;
            }
            setVideoStatus("VIDEO OFF", true);
        });
    }

    private synchronized void ensureJoyLoop() {
        if (joyLoopTask != null && !joyLoopTask.isDone() && !joyLoopTask.isCancelled()) return;
        joyLoopTask = joyExecutor.scheduleAtFixedRate(() -> {
            if (!controlConnected || !joystickActive) {
                return;
            }
            float forward = currentForward;
            float turn = currentTurn;
            // When the stick is effectively centered, send STOP once instead of
            // spamming JOY 0 0 forever. That keeps logs cleaner and stop behavior
            // semantically explicit on the Loom side.
            if (Math.abs(forward) < 0.0005f && Math.abs(turn) < 0.0005f) {
                if (!zeroStopSent) {
                    sendControlCommandDirect("STOP", false);
                    zeroStopSent = true;
                }
            } else {
                zeroStopSent = false;
                sendControlCommandDirect(String.format(Locale.US, "JOY %.3f %.3f", forward, turn), false);
            }
        }, 0L, JOY_SEND_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopJoyLoop() {
        if (joyLoopTask != null) {
            joyLoopTask.cancel(true);
            joyLoopTask = null;
        }
    }

    private void startPingLoop() {
        stopPingLoop();
        pingRunnable = new Runnable() {
            @Override
            public void run() {
                if (!controlConnected) return;
                // Ping/Pong is not needed for motion itself; it exists so the app
                // can show RTT and prove the control socket is still alive.
                lastPingSentAt = System.currentTimeMillis();
                sendControlCommand("PING", false);
                mainHandler.postDelayed(this, PING_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(pingRunnable, PING_INTERVAL_MS);
    }

    private void stopPingLoop() {
        if (pingRunnable != null) {
            mainHandler.removeCallbacks(pingRunnable);
            pingRunnable = null;
        }
    }

    private void stopMotion(boolean resetJoystick) {
        joystickActive = false;
        currentForward = 0f;
        currentTurn = 0f;
        zeroStopSent = true;
        updateCurrentJoyText();
        stopJoyLoop();
        if (resetJoystick) {
            suppressJoystickCallback = true;
            joystickView.resetStick();
            suppressJoystickCallback = false;
        }
        // Explicit STOP is still sent even after local state is zeroed so the Loom
        // side does not wait for its watchdog timeout.
        sendControlCommand("STOP", false);
    }

    private void sendControlCommand(String command, boolean echoOutgoing) {
        // Use a dedicated single-thread executor so TCP writes stay ordered even
        // when UI events, joystick updates, and button clicks overlap.
        commandExecutor.execute(() -> sendControlCommandDirect(command, echoOutgoing));
    }

    private void sendControlCommandDirect(String command, boolean echoOutgoing) {
        if (!controlConnected || controlWriter == null) {
            if (echoOutgoing) appendLog("Control not connected");
            return;
        }
        synchronized (controlWriterLock) {
            controlWriter.println(command);
            controlWriter.flush();
        }
        if (echoOutgoing) {
            appendLog(">> " + command);
        }
    }

    private void parseAndDisplayTelemetry(String payload) {
        String mode = extractField(payload, "mode");
        String estop = extractField(payload, "estop");
        String v = extractField(payload, "v");
        String w = extractField(payload, "w");
        String dist = extractField(payload, "dist");
        String heading = extractField(payload, "heading");
        String video = extractField(payload, "video");
        updateTelemetryDisplay(mode, estop, v, w, dist, heading, video);
    }

    private void updateTelemetryDisplay(String mode, String estop, String v, String w, String dist, String heading, String video) {
        mainHandler.post(() -> {
            StringBuilder sb = new StringBuilder();
            if (!mode.isEmpty()) {
                sb.append("MODE ").append(mode);
                if ("1".equals(estop)) sb.append(" [E-STOP]");
                sb.append("  VIDEO=").append(safe(video)).append('\n');
            }
            sb.append(String.format(Locale.US, "v=%-8s  w=%-8s\n", safe(v), safe(w)));
            sb.append(String.format(Locale.US, "dist=%-8s  hdg=%s", safe(dist), safe(heading)));
            telemetryText.setText(sb.toString());
        });
    }

    private void displayVideoFrame(Bitmap bitmap, int width, int height, long timestampMs) {
        long lagMs = Math.max(0L, System.currentTimeMillis() - timestampMs);
        updateVideoFpsCounter();
        mainHandler.post(() -> {
            Bitmap old = currentVideoBitmap;
            currentVideoBitmap = bitmap;
            videoView.setImageBitmap(bitmap);
            // Recycle the previous frame so repeated FPV updates do not leak memory.
            if (old != null && old != bitmap && !old.isRecycled()) {
                old.recycle();
            }
            setVideoStatus("VIDEO " + width + "x" + height + "  " + lagMs + "ms  " + videoFps + "fps", false);
        });
    }

    private void updateVideoFpsCounter() {
        long now = System.currentTimeMillis();
        if (videoFpsWindowStart == 0L) {
            videoFpsWindowStart = now;
            videoFramesThisWindow = 0;
        }
        videoFramesThisWindow++;
        long span = now - videoFpsWindowStart;
        if (span >= 1000L) {
            videoFps = Math.round(videoFramesThisWindow * 1000f / span);
            videoFramesThisWindow = 0;
            videoFpsWindowStart = now;
        }
    }

    private void updateCurrentJoyText() {
        mainHandler.post(() -> currentJoyText.setText(
                String.format(Locale.US, "%.2f / %.2f", currentForward, currentTurn)
        ));
    }

    private void updateScaleLabels() {
        mainHandler.post(() -> {
            driveScaleText.setText(driveScaleSeekBar.getProgress() + "%");
            turnScaleText.setText(turnScaleSeekBar.getProgress() + "%");
        });
    }

    private void setStatusDotColor(final int color) {
        mainHandler.post(() -> {
            if (statusDot != null && statusDot.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) statusDot.getBackground()).setColor(color);
            }
        });
    }

    private void setJoystickEnabled(boolean enabled) {
        joystickView.setEnabled(enabled);
        joystickView.setAlpha(enabled ? 1.0f : 0.45f);
    }

    private void setVideoStatus(String text, boolean showHint) {
        mainHandler.post(() -> {
            videoStatusText.setText(text);
            videoHintText.setVisibility(showHint ? View.VISIBLE : View.GONE);
        });
    }

    private void appendLog(String message) {
        mainHandler.post(() -> {
            logLines.addLast(message);
            while (logLines.size() > MAX_LOG_LINES) {
                logLines.removeFirst();
            }
            StringBuilder sb = new StringBuilder();
            for (String line : logLines) {
                sb.append(line).append('\n');
            }
            logText.setText(sb.toString());
            if (logText.getLayout() != null) {
                int scrollAmount = logText.getLayout().getLineTop(logText.getLineCount()) - logText.getHeight();
                logText.scrollTo(0, Math.max(0, scrollAmount));
            }
        });
    }

    private void clearLog() {
        mainHandler.post(() -> {
            logLines.clear();
            logText.setText("");
        });
    }

    private void postStatus(String status) {
        mainHandler.post(() -> statusText.setText(status));
    }

    private String extractField(String payload, String key) {
        String[] parts = payload.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            String prefix = key + "=";
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null || value.isEmpty() ? "---" : value;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float getDriveScale() {
        return driveScaleSeekBar.getProgress() / 100f;
    }

    private float getTurnScale() {
        return turnScaleSeekBar.getProgress() / 100f;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopJoyLoop();
        stopPingLoop();
        disconnect();
        ioExecutor.shutdownNow();
        commandExecutor.shutdownNow();
        joyExecutor.shutdownNow();
        if (currentVideoBitmap != null && !currentVideoBitmap.isRecycled()) {
            currentVideoBitmap.recycle();
            currentVideoBitmap = null;
        }
    }
}
