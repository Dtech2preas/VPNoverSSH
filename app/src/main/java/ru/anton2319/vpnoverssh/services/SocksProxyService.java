package ru.anton2319.vpnoverssh.services;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import ru.anton2319.vpnoverssh.data.singleton.PortForward;
import ru.anton2319.vpnoverssh.data.singleton.SocksPersistent;
import ru.anton2319.vpnoverssh.data.singleton.StatusInfo;

public class SocksProxyService extends VpnService {

    private static final String TAG = "SocksProxyService";
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Thread vpnThread;
    private SharedPreferences sharedPreferences;
    private Future<Set<String>> getSelectedAppsFuture;
    private Future<String> getDnsIpFuture;

    public Future<String> getDnsIp(SharedPreferences sharedPreferences) {
        return executor.submit(() -> sharedPreferences.getString("dns_resolver_ip", "1.1.1.1"));
    }

    private Future<Set<String>> getSelectedApps(SharedPreferences sharedPreferences) {
        return executor.submit(() -> sharedPreferences.getStringSet("included_apps", new HashSet<>()));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        getSelectedAppsFuture = getSelectedApps(sharedPreferences);
        getDnsIpFuture = getDnsIp(sharedPreferences);

        vpnThread = newVpnThread();
        SocksPersistent.getInstance().setVpnThread(vpnThread);
        vpnThread.start();

        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        StatusInfo.getInstance().setActive(false);
        Thread sshThread = PortForward.getInstance().getSshThread();
        if (sshThread != null) {
            sshThread.interrupt();
        }
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Shutting down gracefully");
        AsyncTask.execute(() -> {
            Intent sshIntent = StatusInfo.getInstance().getSshIntent();
            if (sshIntent != null) {
                stopService(sshIntent);
            }
        });
        try {
            ParcelFileDescriptor pfd = SocksPersistent.getInstance().getVpnInterface();
            if (pfd != null) {
                pfd.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Cannot handle graceful shutdown", e);
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private void startVpn() {
        try {
            Log.d(TAG, "Waiting for SOCKS proxy to be ready...");
            int waitCount = 0;
            while (!PortForward.getInstance().isProxyReady() && waitCount < 30) {
                TimeUnit.SECONDS.sleep(1);
                waitCount++;
                Log.d(TAG, "Waited " + waitCount + " seconds for proxy...");
            }

            if (!PortForward.getInstance().isProxyReady()) {
                Log.e(TAG, "SOCKS proxy not ready after 30 seconds! Aborting VPN start.");
                stopSelf();
                return;
            }

            Builder builder = new Builder();
            builder.setMtu(1500)
                   .addAddress("26.26.26.1", 24)
                   .addRoute("0.0.0.0", 0)
                   .addDnsServer(getDnsIpFuture.get())
                   .addDisallowedApplication("ru.anton2319.vpnoverssh");

            if (!getSelectedAppsFuture.get().isEmpty()) {
                for (String packageName : getSelectedAppsFuture.get()) {
                    builder.addAllowedApplication(packageName);
                }
            }

            ParcelFileDescriptor vpnInterface = builder.establish();
            SocksPersistent.getInstance().setVpnInterface(vpnInterface);

            String socksHostname = "127.0.0.1";
            int socksPort = Integer.parseInt(Optional.of(sharedPreferences.getString("forwarder_port", "1080")).orElse("1080"));

            testSocksProxy(socksHostname, socksPort);

            engine.Key key = new engine.Key();
            key.setMark(0);
            key.setMTU(1500);
            key.setDevice("fd://" + vpnInterface.getFd());
            key.setInterface("");
            key.setLogLevel("info");
            key.setProxy("socks5://" + socksHostname + ":" + socksPort);
            key.setRestAPI("");
            key.setTCPSendBufferSize("1m");
            key.setTCPReceiveBufferSize("1m");
            key.setTCPModerateReceiveBuffer(true);

            engine.Engine.insert(key);
            engine.Engine.start();
            Log.d(TAG, "VPN engine started");

            testDnsResolution();

            while (!Thread.interrupted()) {
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Interruption signal received");
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "VPN error: ", e);
            Log.e(TAG, "VPN stopped due to error");
            stopSelf();
        }
    }

    private void testSocksProxy(String host, int port) {
        try (Socket test = new Socket()) {
            test.connect(new InetSocketAddress(host, port), 3000);
            Log.d(TAG, "SOCKS proxy connection verified at " + host + ":" + port);
            
            try {
                Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port));
                URL url = new URL("http://example.com");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.connect();
                
                int status = conn.getResponseCode();
                if (status == 200) {
                    Log.d(TAG, "SOCKS proxy functionality verified");
                } else {
                    Log.e(TAG, "SOCKS proxy test failed. HTTP status: " + status);
                }
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "SOCKS proxy functionality test failed", e);
            }
        } catch (Exception e) {
            Log.e(TAG, "SOCKS proxy connection test failed", e);
        }
    }

    private void testDnsResolution() {
        try {
            InetAddress[] addresses = InetAddress.getAllByName("google.com");
            Log.d(TAG, "DNS resolution successful: " + Arrays.toString(addresses));
        } catch (Exception e) {
            Log.e(TAG, "DNS resolution test failed!", e);
        }
    }

    private Thread newVpnThread() {
        return new Thread(() -> {
            try {
                startVpn();
            } catch (Exception e) {
                Log.e(TAG, "VPN thread error: ", e);
            } finally {
                stopSelf();
            }
        }, "VPN-Thread");
    }
}