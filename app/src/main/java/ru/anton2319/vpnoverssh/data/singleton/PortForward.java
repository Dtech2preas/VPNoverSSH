package ru.anton2319.vpnoverssh.data.singleton;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.DynamicPortForwarder;

public class PortForward {
    private static PortForward instance = null;
    private Connection conn;
    private DynamicPortForwarder forwarder;
    private Thread sshThread;
    private boolean proxyReady = false;

    private PortForward() {}

    public static synchronized PortForward getInstance() {
        if (instance == null) {
            instance = new PortForward();
        }
        return instance;
    }

    // Add the required methods
    public synchronized boolean isProxyReady() {
        return proxyReady;
    }

    public synchronized void setProxyReady(boolean ready) {
        proxyReady = ready;
    }

    // Existing methods below...
    public Connection getConn() {
        return conn;
    }

    public void setConn(Connection conn) {
        this.conn = conn;
    }

    public DynamicPortForwarder getForwarder() {
        return forwarder;
    }

    public void setForwarder(DynamicPortForwarder forwarder) {
        this.forwarder = forwarder;
    }

    public Thread getSshThread() {
        return sshThread;
    }

    public void setSshThread(Thread sshThread) {
        this.sshThread = sshThread;
    }

    public void cleanup() {
        this.conn = null;
        this.forwarder = null;
        this.sshThread = null;
        this.proxyReady = false;
    }
}