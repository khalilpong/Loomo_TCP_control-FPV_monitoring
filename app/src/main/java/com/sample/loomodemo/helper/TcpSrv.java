package com.sample.loomodemo.helper;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared control/status TCP server for Loom.
 *
 * Port 6666 is used by both TcpRemote and TcpPro. Motion commands arrive from the
 * phone here, and status/log lines are written back over the same socket.
 */
public class TcpSrv {
    public interface TcpMessageListener {
        void onMessage(String message);
    }

    private final String TAG = "TcpSrv";
    ServerSocket serverSocket;
    Socket client;
    BufferedReader reader;
    BufferedWriter writer;
    private final List<TcpMessageListener> listeners = new CopyOnWriteArrayList<>();

    {
        try {
            serverSocket = new ServerSocket(6666);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Create a socket address object
    InetSocketAddress endPoint = new InetSocketAddress("0.0.0.0", 6666);

    private TcpSrv() {
        new Thread(this::acceptLoop).start();
    }

    private final static TcpSrv inst = new TcpSrv();
    static public TcpSrv getInstance() {
        return inst;
    }

    public void addMessageListener(TcpMessageListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeMessageListener(TcpMessageListener listener) {
        listeners.remove(listener);
    }

    public void sendMsg(String msg) {
        if (writer == null) return;
        synchronized (writer) {
            try {
                // Every message is line-delimited so the phone can read simple text
                // commands/status with BufferedReader.readLine().
                writer.write(msg + "\n");
                writer.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void acceptLoop() {
        while (true) {
            try {
                Socket accepted = serverSocket.accept();
                setupClient(accepted);
            } catch (IOException e) {
                Log.e(TAG, "acceptLoop error", e);
                return;
            }
        }
    }

    private synchronized void setupClient(Socket accepted) throws IOException {
        closeClientLocked();
        client = accepted;
        reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
        writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
        // Simple handshake for the phone UI so it knows a fresh connection exists.
        sendMsg("hello");
        new Thread(() -> readLoop(accepted)).start();
    }

    private void readLoop(Socket socket) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                dispatchMessage(trimmed);
            }
        } catch (IOException e) {
            Log.w(TAG, "readLoop disconnected", e);
        } finally {
            synchronized (this) {
                if (socket == client) {
                    closeClientLocked();
                }
            }
        }
    }

    private void dispatchMessage(String msg) {
        // Pilots register/unregister listeners depending on which route is active.
        // TcpSrv itself stays generic and does not understand JOY/STOP semantics.
        for (TcpMessageListener listener : listeners) {
            listener.onMessage(msg);
        }
    }

    private void closeClientLocked() {
        try {
            if (reader != null) reader.close();
        } catch (IOException ignored) {}
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {}
        try {
            if (client != null) client.close();
        } catch (IOException ignored) {}
        reader = null;
        writer = null;
        client = null;
    }
}
