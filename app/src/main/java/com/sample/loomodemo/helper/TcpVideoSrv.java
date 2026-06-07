package com.sample.loomodemo.helper;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class TcpVideoSrv {
    public interface Listener {
        void onClientConnected();
        void onClientDisconnected();
    }

    private static final String TAG = "TcpVideoSrv";
    // Simple binary frame protocol magic. The phone checks this before decoding
    // a frame so it can reject corrupt or misaligned input early.
    private static final int FRAME_MAGIC = 0x54565031; // TVP1

    private final int mPort;
    private final Listener mListener;
    private volatile boolean mRun = false;
    private ServerSocket mServerSocket;
    private Socket mClientSocket;
    private DataOutputStream mOutput;

    public TcpVideoSrv(int port, Listener listener) {
        mPort = port;
        mListener = listener;
    }

    public synchronized void start() throws IOException {
        if (mRun) return;
        mRun = true;
        mServerSocket = new ServerSocket(mPort);
        // Video accept loop is separated from control TCP so large JPEG frames do
        // not block joystick/status traffic on the main control socket.
        new Thread(this::acceptLoop, "tcp-video-accept").start();
    }

    public synchronized void stop() {
        mRun = false;
        closeClientLocked();
        try {
            if (mServerSocket != null) mServerSocket.close();
        } catch (IOException ignored) {
        }
        mServerSocket = null;
    }

    public synchronized boolean hasClient() {
        return mClientSocket != null && mOutput != null && mClientSocket.isConnected() && !mClientSocket.isClosed();
    }

    public void sendFrame(byte[] jpegData, int width, int height, long timestampMs) {
        if (jpegData == null || jpegData.length == 0) return;
        DataOutputStream output;
        synchronized (this) {
            output = mOutput;
        }
        if (output == null) return;
        synchronized (output) {
            try {
                // Frame layout:
                // [magic][width][height][timestamp][jpeg length][jpeg bytes]
                // The timestamp lets the phone drop stale frames instead of
                // showing a perfectly valid but already too-old image.
                output.writeInt(FRAME_MAGIC);
                output.writeInt(width);
                output.writeInt(height);
                output.writeLong(timestampMs);
                output.writeInt(jpegData.length);
                output.write(jpegData);
                output.flush();
            } catch (IOException e) {
                Log.w(TAG, "sendFrame failed", e);
                synchronized (this) {
                    closeClientLocked();
                }
            }
        }
    }

    private void acceptLoop() {
        while (mRun) {
            try {
                Socket accepted = mServerSocket.accept();
                setupClient(accepted);
            } catch (IOException e) {
                if (mRun) {
                    Log.e(TAG, "acceptLoop error", e);
                }
                return;
            }
        }
    }

    private synchronized void setupClient(Socket accepted) throws IOException {
        closeClientLocked();
        // Small control/video packets should leave immediately rather than wait
        // in Nagle's algorithm buffers, so FPV latency stays lower.
        accepted.setTcpNoDelay(true);
        accepted.setSoTimeout(1000);
        mClientSocket = accepted;
        mOutput = new DataOutputStream(new BufferedOutputStream(mClientSocket.getOutputStream()));
        if (mListener != null) mListener.onClientConnected();
        new Thread(() -> watchClient(accepted), "tcp-video-watch").start();
    }

    private void watchClient(Socket socket) {
        try {
            InputStream input = socket.getInputStream();
            while (mRun && socket == mClientSocket && !socket.isClosed()) {
                try {
                    // The phone normally sends no video-side payload. We keep a
                    // blocking read here only to learn quickly when the peer has
                    // disappeared, which is more reliable than polling isClosed().
                    int value = input.read();
                    if (value == -1) {
                        break;
                    }
                } catch (SocketTimeoutException ignored) {
                    // Keep waiting; a timeout just means the client has not sent data.
                }
            }
        } catch (IOException ignored) {
        } finally {
            synchronized (this) {
                if (socket == mClientSocket) {
                    closeClientLocked();
                }
            }
        }
    }

    private void closeClientLocked() {
        try {
            if (mOutput != null) mOutput.close();
        } catch (IOException ignored) {
        }
        try {
            if (mClientSocket != null) mClientSocket.close();
        } catch (IOException ignored) {
        }
        boolean hadClient = mClientSocket != null || mOutput != null;
        mOutput = null;
        mClientSocket = null;
        if (hadClient && mListener != null) {
            mListener.onClientDisconnected();
        }
    }
}
