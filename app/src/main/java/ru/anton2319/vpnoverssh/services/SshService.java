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
import java.util.Optional;

import ru.anton2319.vpnoverssh.data.singleton.PortForward;

public class SshService extends Service {

    private static final String TAG = "SshService";
    Thread sshThread;
    Connection conn;
    DynamicPortForwarder forwarder;
    SharedPreferences sharedPreferences;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // Stop any existing SSH thread
        sshThread = PortForward.getInstance().getSshThread();
        if (sshThread != null) {
            sshThread.interrupt();
        }

        sshThread = newSshThread(intent);
        PortForward.getInstance().setSshThread(sshThread);
        sshThread.start();

        return START_STICKY;
    }

    public void initiateSSH(Intent intent) throws IOException, RuntimeException {
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
        conn.connect();
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

        // Bind SOCKS proxy to 127.0.0.1 explicitly
        forwarder = conn.createDynamicPortForwarder("127.0.0.1", forwardPort);
        PortForward.getInstance().setForwarder(forwarder);

        Log.d(TAG, "SOCKS proxy running on 127.0.0.1:" + forwardPort);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "SSH service stopping...");
        sshThread = PortForward.getInstance().getSshThread();
        if (sshThread != null) {
            sshThread.interrupt();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public Thread newSshThread(Intent intent) {
        return new Thread(() -> {
            try {
                System.setProperty("user.home", getFilesDir().getAbsolutePath());
                initiateSSH(intent);

                // Keep thread alive until interrupted
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }

            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "SSH Error: ", e);
            } finally {
                try {
                    if (forwarder != null) {
                        forwarder.close();
                        Log.d(TAG, "SOCKS proxy closed");
                    }
                    if (conn != null) {
                        conn.close();
                        Log.d(TAG, "SSH connection closed");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error closing SSH resources", e);
                }
                stopSelf();
            }
        });
    }
}