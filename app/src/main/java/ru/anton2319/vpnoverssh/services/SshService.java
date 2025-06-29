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

import ru.anton2319.vpnoverssh.data.model.SSHAccount;
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
                existingThread.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for thread to stop");
            }
        }

        sshThread = newSshThread(intent);
        PortForward.getInstance().setSshThread(sshThread);
        sshThread.start();

        return START_STICKY;
    }

    private void initiateSSH(SSHAccount account) throws IOException, RuntimeException {
        Log.d(TAG, "Starting SSH service for account: " + account.getNickname());

        String username = account.getUsername();
        String host = account.getServer();
        String password = account.getPassword();
        String privateKey = account.getPrivateKey();
        int port = account.getPort();
        String sniHost = account.getSniHost();

        conn = new Connection(host, port);
        
        // Apply SNI if configured
        if (sniHost != null && !sniHost.isEmpty()) {
            Log.d(TAG, "Applying SNI: " + sniHost);
            conn.setSocketFactory(new SNISocketFactory(sniHost));
        }
        
        conn.connect(null, 30000, 30000);
        PortForward.getInstance().setConn(conn);

        // Authentication
        boolean isAuthenticated = false;
        if (privateKey != null && !privateKey.isEmpty()) {
            Log.d(TAG, "Attempting public key authentication");
            isAuthenticated = conn.authenticateWithPublicKey(username, privateKey.toCharArray(), "");
        } else if (password != null && !password.isEmpty()) {
            Log.d(TAG, "Attempting password authentication");
            isAuthenticated = conn.authenticateWithPassword(username, password);
        } else {
            Log.d(TAG, "Attempting none authentication");
            isAuthenticated = conn.authenticateWithNone(username);
        }

        if (!isAuthenticated) {
            throw new RuntimeException("Authentication failed: check credentials");
        }

        Log.d(TAG, "Authentication successful. Creating SOCKS proxy...");

        int forwardPort = 1080;
        try {
            forwardPort = Integer.parseInt(sharedPreferences.getString("forwarder_port", "1080"));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid forwarder port, defaulting to 1080");
        }

        try {
            forwarder = conn.createDynamicPortForwarder(new InetSocketAddress("127.0.0.1", forwardPort));
            PortForward.getInstance().setForwarder(forwarder);
            PortForward.getInstance().setProxyReady(true);
            Log.d(TAG, "SOCKS proxy started on 127.0.0.1:" + forwardPort);
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
                sshThread.join(1000);
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
                SSHAccount account = (SSHAccount) intent.getSerializableExtra("ssh_account");
                initiateSSH(account);

                // Keep thread alive until interrupted
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(1000);
                        if (!conn.isAuthenticationComplete()) {
                            Log.w(TAG, "SSH connection lost");
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
                conn.close();
                Log.d(TAG, "SSH connection closed");
                conn = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing SSH resources", e);
        }
        PortForward.getInstance().cleanup();
    }
    
    // Custom Socket Factory for SNI
    private static class SNISocketFactory extends com.trilead.ssh2.SocketFactory {
        private final String sniHost;

        public SNISocketFactory(String sniHost) {
            this.sniHost = sniHost;
        }

        @Override
        public java.net.Socket createSocket() throws IOException {
            return new javax.net.ssl.SSLSocketFactory().createSocket() {
                @Override
                public void connect(java.net.SocketAddress endpoint, int timeout) throws IOException {
                    super.connect(endpoint, timeout);
                    applySNI();
                }

                @Override
                public void connect(java.net.SocketAddress endpoint) throws IOException {
                    super.connect(endpoint);
                    applySNI();
                }

                private void applySNI() {
                    try {
                        javax.net.ssl.SSLParameters params = new javax.net.ssl.SSLParameters();
                        params.setServerNames(java.util.Collections.singletonList(
                            new javax.net.ssl.SNIHostName(sniHost)
                        ));
                        ((javax.net.ssl.SSLSocket) this).setSSLParameters(params);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to apply SNI", e);
                    }
                }
            };
        }
    }
}