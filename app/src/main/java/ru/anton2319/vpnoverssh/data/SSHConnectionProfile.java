package ru.anton2319.vpnoverssh.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.UUID;

public class SSHConnectionProfile {
    private String serverIP;
    private int serverPort;
    private String username;
    private AuthenticationType authenticationType;
    private String password;
    private String privateKey;
    private String profileName;  // New field for profile name

    public enum AuthenticationType {
        PASSWORD,
        PRIVATE_KEY
    }

    public UUID uuid = UUID.randomUUID();

    public SSHConnectionProfile() {}

    public SSHConnectionProfile(String serverIP, int serverPort, String username, AuthenticationType authenticationType) {
        this.serverIP = serverIP;
        this.serverPort = serverPort;
        this.username = username;
        this.authenticationType = authenticationType;
        // Default profile name combines username and server
        this.profileName = username + "@" + serverIP;
    }

    public static SSHConnectionProfile fromLinkedTreeMap(Object linkedTreeMap) {
        Gson gson = new GsonBuilder().create();
        String json = gson.toJson(linkedTreeMap);
        SSHConnectionProfile profile = gson.fromJson(json, SSHConnectionProfile.class);
        
        // Ensure profileName is set (backward compatibility)
        if (profile.profileName == null || profile.profileName.isEmpty()) {
            profile.profileName = profile.username + "@" + profile.serverIP;
        }
        return profile;
    }

    // New method for getting display name
    public String getName() {
        return profileName;
    }

    public void setName(String name) {
        this.profileName = name;
    }

    public String getServerIP() {
        return serverIP;
    }

    public void setServerIP(String serverIP) {
        this.serverIP = serverIP;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public AuthenticationType getAuthenticationType() {
        return authenticationType;
    }

    public void setAuthenticationType(AuthenticationType authenticationType) {
        this.authenticationType = authenticationType;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    // Helper method to get display string
    public String getDisplayString() {
        return profileName + " (" + serverIP + ":" + serverPort + ")";
    }
}