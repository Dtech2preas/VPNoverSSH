package ru.anton2319.vpnoverssh;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import java.util.List;
import ru.anton2319.vpnoverssh.data.SSHConnectionProfile;
import ru.anton2319.vpnoverssh.data.singleton.SocksPersistent;
import ru.anton2319.vpnoverssh.data.singleton.StatusInfo;
import ru.anton2319.vpnoverssh.data.utils.SSHConnectionProfileAdapter;
import ru.anton2319.vpnoverssh.data.utils.SSHConnectionProfileManager;
import ru.anton2319.vpnoverssh.services.SocksProxyService;
import ru.anton2319.vpnoverssh.services.SshService;

public class MainActivity extends AppCompatActivity {
    List<SSHConnectionProfile> sshConnectionProfileList;
    SSHConnectionProfile selectedProfile;
    private static final String TAG = "MainActivity";
    private String privateKey;
    Intent vpnIntent;
    Intent sshIntent;
    EditText sniInput;
    TextView tvStatus;
    TextView tvLogs;
    NestedScrollView scrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(StatusInfo.getInstance().getVpnIntent() == null) {
            StatusInfo.getInstance().setVpnIntent(new Intent(this, SocksProxyService.class));
        }
        if(StatusInfo.getInstance().getSshIntent() == null) {
            StatusInfo.getInstance().setSshIntent(new Intent(this, SshService.class));
        }

        setContentView(R.layout.activity_main);

        // Initialize UI components
        sniInput = findViewById(R.id.sni_input);
        tvStatus = findViewById(R.id.tvStatus);
        tvLogs = findViewById(R.id.tvLogs);
        scrollView = findViewById(R.id.scrollView);

        vpnIntent = StatusInfo.getInstance().getVpnIntent();
        sshIntent = StatusInfo.getInstance().getSshIntent();

        Context context = this;

        SSHConnectionProfileManager sshConnectionProfileManager = new SSHConnectionProfileManager(this);
        sshConnectionProfileList = sshConnectionProfileManager.loadProfiles();
        SSHConnectionProfileAdapter adapter = new SSHConnectionProfileAdapter(this, sshConnectionProfileList);

        Spinner spinner = findViewById(R.id.spinner);
        spinner.setAdapter(adapter);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedProfile = SSHConnectionProfile.fromLinkedTreeMap(parent.getItemAtPosition(position));
                appendLog("Selected profile: " + selectedProfile.getName());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        ImageButton editButton = findViewById(R.id.editProfileButton);
        editButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(selectedProfile != null && selectedProfile.uuid != null) {
                    Intent intent = new Intent(context, NewConnectionActivity.class);
                    intent.putExtra("uuid", selectedProfile.uuid.toString());
                    startActivity(intent);
                    appendLog("Editing profile: " + selectedProfile.getName());
                }
            }
        });

        ImageButton addButton = findViewById(R.id.addProfileButton);
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(context, NewConnectionActivity.class);
                startActivity(intent);
                appendLog("Creating new profile");
            }
        });

        Button connectButton = findViewById(R.id.ssh_connect_button);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(selectedProfile != null) {
                    String username = selectedProfile.getUsername();
                    String password = selectedProfile.getPassword();
                    privateKey = selectedProfile.getPrivateKey();
                    String hostname = selectedProfile.getServerIP();
                    int port = selectedProfile.getServerPort();
                    String sniHost = sniInput.getText().toString().trim();

                    appendLog("Starting connection to: " + hostname);
                    startVpn(username, password, privateKey, hostname, port, sniHost);
                }
                else {
                    appendLog("No profile selected! Creating new one");
                    Intent intent = new Intent(context, NewConnectionActivity.class);
                    startActivity(intent);
                }
            }
        });
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            tvLogs.append(">> " + message + "\n");
            // Auto-scroll to bottom
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        SSHConnectionProfileManager sshConnectionProfileManager = new SSHConnectionProfileManager(this);
        sshConnectionProfileList = sshConnectionProfileManager.loadProfiles();
        SSHConnectionProfileAdapter adapter = new SSHConnectionProfileAdapter(this, sshConnectionProfileList);
        Spinner spinner = findViewById(R.id.spinner);
        spinner.setAdapter(adapter);

        if (StatusInfo.getInstance().isActive()) {
            tvStatus.setText("Status: Connected");
        } else {
            tvStatus.setText("Status: Disconnected");
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            appendLog("Opening settings");
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);
    }

    private void startVpn(String username, String password, String privateKey, String hostname, int port, String sniHost) {
        if(!StatusInfo.getInstance().isActive()) {
            tvStatus.setText("Status: Connecting...");
            appendLog("Preparing VPN service");

            Intent intentPrepare = VpnService.prepare(this);
            if (intentPrepare != null) {
                StatusInfo.getInstance().setActive(false);
                appendLog("Toggled off due to missing VPN permission");
                startActivityForResult(intentPrepare, 0);
                return;
            }

            appendLog("Toggled on");
            StatusInfo.getInstance().setActive(true);
            tvStatus.setText("Status: Connecting...");

            try {
                appendLog("Setting up port forward");
                sshIntent.putExtra("user", username);
                sshIntent.putExtra("password", password);
                if (privateKey != null && !privateKey.isEmpty()) {
                    sshIntent.putExtra("privateKey", privateKey);
                    appendLog("Using private key authentication");
                } else {
                    appendLog("Using password authentication");
                }
                sshIntent.putExtra("hostname", hostname);

                if(port > 0 && port < 65536) {
                    sshIntent.putExtra("port", String.valueOf(port));
                    appendLog("Using custom port: " + port);
                } else {
                    sshIntent.putExtra("port", String.valueOf(22));
                    appendLog("Using default SSH port: 22");
                }

                if (!sniHost.isEmpty()) {
                    sshIntent.putExtra("sni", sniHost);
                    appendLog("Using SNI: " + sniHost);
                }

                startService(sshIntent);
                appendLog("SSH service started");

                vpnIntent.putExtra("socksPort", 1080);
                startService(vpnIntent);
                appendLog("VPN service started");
                StatusInfo.getInstance().setActive(true);
                tvStatus.setText("Status: Connected");
            } catch (Exception e) {
                StatusInfo.getInstance().setActive(false);
                tvStatus.setText("Status: Error - " + e.getMessage());
                appendLog("Error: " + e.getMessage());
            }
        } else {
            appendLog("Toggled off");
            StatusInfo.getInstance().setActive(false);
            tvStatus.setText("Status: Disconnecting...");

            Thread vpnThread = SocksPersistent.getInstance().getVpnThread();
            if(vpnThread != null) {
                vpnThread.interrupt();
                appendLog("VPN thread interrupted");
            }

            sshIntent = StatusInfo.getInstance().getSshIntent();
            stopService(sshIntent);
            appendLog("Services stopped");
            tvStatus.setText("Status: Disconnected");
        }
    }
}