package com.taklite.client.tak;

import com.taklite.util.AppLog;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

public class TakClient extends Thread {
    private static final String TAG = "TakClient";
    private static final long RECONNECT_DELAY_MS = 5000;

    // Hard cap on the pending (not-yet-complete) receive buffer. A single CoT <event> is a few KB;
    // 4 MB is orders of magnitude beyond any legitimate one. If this much data arrives with no
    // closing </event>, the peer is malformed or hostile — drop the connection rather than let the
    // buffer grow without bound and OOM this memory-constrained controller. See security review #4.
    private static final int MAX_RECV_BUFFER_BYTES = 4 * 1024 * 1024;

    private final String serverAddress;
    private final int port;
    private final String trustStorePath;
    private final String trustStorePassword;
    private final String clientCertPath;
    private final String clientCertPassword;
    private final TakClientListener listener;

    private volatile boolean mRun;
    private PrintWriter mBufferOut;
    private InputStream mInputStream;
    private SSLSocket sslSocket;
    private Socket socket;

    public interface TakClientListener {
        void onConnected();
        void onDisconnected();
        void onCotReceived(String xml);
    }

    public TakClient(String serverAddress, int port, String trustStorePath, String trustStorePassword,
                     String clientCertPath, String clientCertPassword, TakClientListener listener) {
        super("TakClient-Thread");
        this.serverAddress = serverAddress;
        this.port = port;
        this.trustStorePath = trustStorePath;
        this.trustStorePassword = trustStorePassword;
        this.clientCertPath = clientCertPath;
        this.clientCertPassword = clientCertPassword;
        this.listener = listener;
        setDaemon(true);
    }

    /**
     * Writes one CoT to the server. Fire and forget, on its own thread.
     *
     * ⚠ THE FAILURES HERE USED TO BE COMPLETELY SILENT, which is why a marker that never left
     * the socket and a marker the server rejected looked identical from the log (2026-08-15).
     * Two things hid them:
     *
     *  1. A null stream returned with no trace at all.
     *  2. mBufferOut is a PrintWriter, and PrintWriter NEVER THROWS ON WRITE. It swallows the
     *     IOException and sets an internal flag, so the catch below cannot see a broken pipe,
     *     a half-open socket or a failed flush — it only ever caught something like an NPE.
     *     checkError() is the ONLY way to see that failure, and it also clears nothing, so it
     *     is safe to call on every message.
     *
     * The signature stays void on purpose. The write happens on a new thread after this method
     * has already returned, thus any status handed back to the caller would be a lie.
     */
    public void sendMessage(final String message) {
        if (mBufferOut == null) {
            AppLog.w(TAG, "CoT DROPPED — the output stream is not open (message discarded)");
            return;
        }
        new Thread(() -> {
            try {
                if (mBufferOut != null) {
                    mBufferOut.println(message);
                    mBufferOut.flush();
                    // The real failure detector for this stream — see the note above.
                    if (mBufferOut.checkError()) {
                        AppLog.e(TAG, "CoT WRITE FAILED — the socket reported an error and the "
                                + "message did not go out (" + message.length() + " chars)");
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Error sending message", e);
            }
        }, "TakClient-Send").start();
    }

    public void stopClient() {
        mRun = false;
        closeConnection();
    }

    public boolean isConnected() {
        if (sslSocket != null) {
            return sslSocket.isConnected() && !sslSocket.isClosed();
        }
        if (socket != null) {
            return socket.isConnected() && !socket.isClosed();
        }
        return false;
    }

    @Override
    public void run() {
        mRun = true;
        while (mRun) {
            try {
                connect();
                if (listener != null) listener.onConnected();

                byte[] buf = new byte[8192];
                StringBuilder recvBuffer = new StringBuilder();

                while (mRun) {
                    int bytesRead;
                    try {
                        bytesRead = mInputStream.read(buf);
                    } catch (SocketTimeoutException e) {
                        continue;
                    } catch (SocketException e) {
                        AppLog.e(TAG, "Socket error: " + e.getMessage());
                        break;
                    }
                    if (bytesRead == -1) {
                        AppLog.w(TAG, "Server closed connection (EOF)");
                        break;
                    }
                    String chunk = new String(buf, 0, bytesRead, "UTF-8");
                    recvBuffer.append(chunk);
                    AppLog.d(TAG, "Raw data received (" + bytesRead + " bytes)");

                    int endIdx;
                    while ((endIdx = recvBuffer.indexOf("</event>")) != -1) {
                        int end = endIdx + "</event>".length();
                        String message = recvBuffer.substring(0, end).trim();
                        recvBuffer.delete(0, end);
                        if (!message.isEmpty() && listener != null) {
                            AppLog.d(TAG, "CoT message complete (" + message.length() + " chars)");
                            listener.onCotReceived(message);
                        }
                    }

                    // After draining every complete event, whatever remains is one incomplete
                    // message. If that tail alone exceeds the cap, no </event> is coming — bail
                    // to the reconnect path instead of buffering unboundedly.
                    if (recvBuffer.length() > MAX_RECV_BUFFER_BYTES) {
                        AppLog.e(TAG, "Receive buffer exceeded " + MAX_RECV_BUFFER_BYTES
                                + " bytes with no complete event — dropping connection");
                        break;
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Connection error: " + e.getMessage());
            }

            closeConnection();
            if (listener != null) listener.onDisconnected();

            if (mRun) {
                AppLog.d(TAG, "Reconnecting in " + RECONNECT_DELAY_MS + "ms...");
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void connect() throws Exception {
        InetAddress addr = InetAddress.getByName(serverAddress);
        boolean useTls = trustStorePath != null && !trustStorePath.isEmpty()
                && clientCertPath != null && !clientCertPath.isEmpty();

        if (!useTls) {
            AppLog.e(TAG, "Refusing to connect: no client certificate / trust store present. Plaintext connections are not permitted.");
            throw new SocketException("Not enrolled: TLS certificates required");
        }

        AppLog.d(TAG, "Connecting via TLS to " + serverAddress + ":" + port);
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        FileInputStream trustIs = new FileInputStream(trustStorePath);
        trustStore.load(trustIs, trustStorePassword.toCharArray());
        trustIs.close();

        KeyStore clientStore = KeyStore.getInstance("PKCS12");
        FileInputStream clientIs = new FileInputStream(clientCertPath);
        clientStore.load(clientIs, clientCertPassword.toCharArray());
        clientIs.close();

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientStore, clientCertPassword.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        SSLSocketFactory factory = sslContext.getSocketFactory();
        sslSocket = (SSLSocket) factory.createSocket(addr, port);
        sslSocket.setSoTimeout(1000);

        mBufferOut = new PrintWriter(new BufferedWriter(new OutputStreamWriter(sslSocket.getOutputStream())), true);
        mInputStream = sslSocket.getInputStream();
        AppLog.d(TAG, "Connected to " + serverAddress + ":" + port);
    }

    private void closeConnection() {
        try { if (mBufferOut != null) { mBufferOut.flush(); mBufferOut.close(); } } catch (Exception e) {}
        try { if (sslSocket != null) sslSocket.close(); } catch (IOException e) {}
        try { if (socket != null) socket.close(); } catch (IOException e) {}
        try { if (mInputStream != null) mInputStream.close(); } catch (IOException e) {}
        mBufferOut = null;
        mInputStream = null;
        sslSocket = null;
        socket = null;
    }
}
