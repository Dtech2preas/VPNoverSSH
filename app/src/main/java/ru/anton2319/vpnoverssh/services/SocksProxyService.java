package ru.anton2319.vpnoverssh.services;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ru.anton2319.vpnoverssh.data.singleton.PortForward;
import ru.anton2319.vpnoverssh.data.singleton.SocksPersistent;
import ru.anton2319.vpnoverssh.data.singleton.StatusInfo;

public class SocksProxyService extends VpnService {

    private static final String TAG = "SocksProxyService";
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private Thread vpnThread;
    private SharedPreferences sharedPreferences;
    private Future<String> getDnsIpFuture;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
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
        Log.d(TAG, "Shutting down VPN service gracefully");
        AsyncTask.execute(() -> {
            Intent sshIntent = StatusInfo.getInstance().getSshIntent();
            stopService(sshIntent);
        });

        try {
            ParcelFileDescriptor pfd = SocksPersistent.getInstance().getVpnInterface();
            if (pfd != null) {
                pfd.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing VPN interface", e);
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private void startVpn() throws IOException {
        try {
            ParcelFileDescriptor vpnInterface;
            Builder builder = new VpnService.Builder();

            String dnsIp = getDnsIpFuture.get(); // Default: "1.1.1.1"
            int socksPort = Integer.parseInt(Optional.ofNullable(sharedPreferences.getString("forwarder_port", "1080")).orElse("1080"));

            builder.setMtu(1500)
                   .addAddress("26.26.26.1", 24)
                   .addDnsServer(dnsIp)
                   .addRoute("0.0.0.0", 0);  // Route all traffic through VPN

            // Disallow the app itself if you want to prevent loops
            builder.addDisallowedApplication("ru.anton2319.vpnoverssh");

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface");
                stopSelf();
                return;
            }

            SocksPersistent.getInstance().setVpnInterface(vpnInterface);

            // Optional: Validate that the SOCKS proxy is up
            try (Socket test = new Socket()) {
                test.connect(new InetSocketAddress("127.0.0.1", socksPort), 1000);
                Log.d(TAG, "SOCKS proxy is reachable at 127.0.0.1:" + socksPort);
            } catch (Exception e) {
                Log.e(TAG, "SOCKS proxy unreachable. Traffic may fail.", e);
            }

            // Configure the VPN Engine
            engine.Key key = new engine.Key();
            key.setMark(0);
            key.setMTU(1500);
            key.setDevice("fd://" + vpnInterface.getFd());
            key.setInterface("");
            key.setLogLevel("info");
            key.setProxy("socks5://127.0.0.1:" + socksPort);
            key.setRestAPI("");
            key.setTCPSendBufferSize("");
            key.setTCPReceiveBufferSize("");
            key.setTCPModerateReceiveBuffer(false);

            engine.Engine.insert(key);
            engine.Engine.start();

            Log.d(TAG, "VPN and proxy engine started");

            // Keep VPN thread alive
            while (!Thread.interrupted()) {
                Thread.sleep(1000);
            }

        } catch (InterruptedException e) {
            Log.d(TAG, "VPN thread interrupted, stopping service...");
            onDestroy();
        } catch (Exception e) {
            Log.e(TAG, "Error starting VPN", e);
            stopSelf();
        }
    }

    private Thread newVpnThread() {
        return new Thread(() -> {
            try {
                startVpn();
            } catch (IOException e) {
                Log.e(TAG, "VPN init failed", e);
            } finally {
                stopSelf();
            }
        });
    }

    private Future<String> getDnsIp(SharedPreferences sharedPreferences) {
        return executor.submit(() -> sharedPreferences.getString("dns_resolver_ip", "1.1.1.1"));
    }
}