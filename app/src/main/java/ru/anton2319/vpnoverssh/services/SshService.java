package ru.anton2319.vpnoverssh.services;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.DynamicPortForwarder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;

import ru.anton2319.vpnoverssh.data.singleton.PortForward;

public class SshService extends Service {

    private static final String TAG = "SshService";
    private Thread sshThread;
    private Connection conn;
    private DynamicPortForwarder forwarder;
    private SharedPreferences sharedPreferences;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // Stop any existing SSH thread
        Thread existingThread = PortForward.getInstance().getSshThread();
        if (existingThread != null && existingThread.isAlive()) {
            existingThread.interrupt();
            try {
                existingThread.join(1000); // Wait for thread to finish
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for thread to stop");
            }
        }

        sshThread = newSshThread(intent);
        PortForward.getInstance().setSshThread(sshThread);
        sshThread.start();

        return START_STICKY;
    }

    private void initiateSSH(Intent intent) throws IOException, RuntimeException {
        Log.d(TAG, "Starting trilead.ssh2 SSH service");

        String user = intent.getStringExtra("user");
        String host = intent.getStringExtra("hostname");
        String password = intent.getStringExtra("password");
        String portStr = intent.getStringExtra("port");
        String privateKey = intent.getStringExtra("privateKey");

        int port = 22;
        try {
            port = Integer.parseInt(Optional.ofNullable(portStr).orElse("22"));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid port, defaulting to 22");
        }

        conn = new Connection(host, port);
        conn.connect(null, 30000, 30000); // Added timeout parameters (30 seconds)
        PortForward.getInstance().setConn(conn);

        // Authentication
        boolean isAuthenticated = false;
        if (privateKey != null && !privateKey.isEmpty()) {
            Log.d(TAG, "Attempting public key authentication");
            isAuthenticated = conn.authenticateWithPublicKey(user, privateKey.toCharArray(), "");
        } else if (password != null && !password.isEmpty()) {
            Log.d(TAG, "Attempting password authentication");
            isAuthenticated = conn.authenticateWithPassword(user, password);
        } else {
            Log.d(TAG, "Attempting none authentication");
            isAuthenticated = conn.authenticateWithNone(user);
        }

        if (!isAuthenticated) {
            throw new RuntimeException("Authentication failed: check username, password, or key");
        }

        Log.d(TAG, "Authentication successful. Creating SOCKS proxy...");

        int forwardPort = 1080;
        try {
            forwardPort = Integer.parseInt(sharedPreferences.getString("forwarder_port", "1080"));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid forwarder port, defaulting to 1080");
        }

        // Updated port forwarding implementation
        try {
            forwarder = conn.createDynamicPortForwarder(new InetSocketAddress("127.0.0.1", forwardPort));
            PortForward.getInstance().setForwarder(forwarder);
            Log.d(TAG, "SOCKS proxy successfully started on 127.0.0.1:" + forwardPort);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create SOCKS proxy", e);
            throw new RuntimeException("Failed to create SOCKS proxy: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "SSH service stopping...");
        if (sshThread != null && sshThread.isAlive()) {
            sshThread.interrupt();
            try {
                sshThread.join(1000); // Wait for thread to finish
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for thread to stop");
            }
        }
        cleanupResources();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Thread newSshThread(Intent intent) {
        return new Thread(() -> {
            try {
                System.setProperty("user.home", getFilesDir().getAbsolutePath());
                initiateSSH(intent);

                // Keep thread alive until interrupted
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(1000);
                        // Verify connection is still alive by checking authentication status
                        if (!conn.isAuthenticationComplete()) {
                            Log.w(TAG, "SSH connection lost - authentication no longer complete");
                            break;
                        }
                    } catch (InterruptedException ie) {
                        Log.d(TAG, "SSH thread interrupted");
                        break;
                    }
                }

            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "SSH Error: ", e);
            } finally {
                cleanupResources();
                stopSelf();
            }
        }, "SSH-Connection-Thread");
    }

    private void cleanupResources() {
        try {
            if (forwarder != null) {
                forwarder.close();
                Log.d(TAG, "SOCKS proxy closed");
                forwarder = null;
            }
            if (conn != null) {
                // Connection doesn't have isConnected() method, so we'll just close it
                conn.close();
                Log.d(TAG, "SSH connection closed");
                conn = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing SSH resources", e);
        }
        // Instead of cleanup(), reset the PortForward instance
        PortForward.getInstance().setConn(null);
        PortForward.getInstance().setForwarder(null);
        PortForward.getInstance().setSshThread(null);
    }
}