package ru.anton2319.vpnoverssh;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.textview.MaterialTextView;

import java.util.List;

import ru.anton2319.vpnoverssh.R;
import ru.anton2319.vpnoverssh.data.model.SSHAccount;
import ru.anton2319.vpnoverssh.data.singleton.SocksPersistent;
import ru.anton2319.vpnoverssh.data.singleton.StatusInfo;
import ru.anton2319.vpnoverssh.data.utils.SSHAccountAdapter;
import ru.anton2319.vpnoverssh.data.utils.SSHAccountManager;
import ru.anton2319.vpnoverssh.services.SocksProxyService;
import ru.anton2319.vpnoverssh.services.SshService;
import ru.anton2319.vpnoverssh.ui.settings.SettingsActivity;

public class MainActivity extends AppCompatActivity {
    private List<SSHAccount> sshAccountList;
    private SSHAccount selectedAccount;
    private static final String TAG = "MainActivity";
    private Intent vpnIntent;
    private Intent sshIntent;
    private EditText sniInput;
    private MaterialTextView tvStatus;
    private MaterialTextView tvLogs;
    private NestedScrollView scrollView;
    private MaterialButton connectButton;
    private LottieAnimationView animationView;
    private MaterialCardView accountCard;
    private MaterialCardView sniCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivitiesIfAvailable(getApplication());
        setContentView(R.layout.activity_main);
        
        if (StatusInfo.getInstance().getVpnIntent() == null) {
            StatusInfo.getInstance().setVpnIntent(new Intent(this, SocksProxyService.class));
        }
        if (StatusInfo.getInstance().getSshIntent() == null) {
            StatusInfo.getInstance().setSshIntent(new Intent(this, SshService.class));
        }

        vpnIntent = StatusInfo.getInstance().getVpnIntent();
        sshIntent = StatusInfo.getInstance().getSshIntent();

        // Initialize UI components
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        
        sniInput = findViewById(R.id.sni_input);
        tvStatus = findViewById(R.id.tvStatus);
        tvLogs = findViewById(R.id.tvLogs);
        scrollView = findViewById(R.id.scrollView);
        connectButton = findViewById(R.id.ssh_connect_button);
        animationView = findViewById(R.id.animation_view);
        accountCard = findViewById(R.id.account_card);
        sniCard = findViewById(R.id.sni_card);

        // Set up animation
        animationView.setAnimation(R.raw.disconnected);
        animationView.loop(false);
        
        // Load accounts
        SSHAccountManager accountManager = new SSHAccountManager(this);
        sshAccountList = accountManager.loadAccounts();
        SSHAccountAdapter adapter = new SSHAccountAdapter(this, sshAccountList);

        Spinner spinner = findViewById(R.id.spinner);
        spinner.setAdapter(adapter);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedAccount = (SSHAccount) parent.getItemAtPosition(position);
                appendLog("Selected: " + selectedAccount.getNickname());
                updateAccountCard();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedAccount = null;
                accountCard.setVisibility(View.GONE);
            }
        });

        ImageButton editButton = findViewById(R.id.editProfileButton);
        editButton.setOnClickListener(v -> {
            if (selectedAccount != null && selectedAccount.getId() != null) {
                Intent intent = new Intent(this, AccountEditorActivity.class);
                intent.putExtra("account_id", selectedAccount.getId());
                startActivity(intent);
                appendLog("Editing: " + selectedAccount.getNickname());
            }
        });

        ImageButton addButton = findViewById(R.id.addProfileButton);
        addButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, AccountEditorActivity.class);
            startActivity(intent);
            appendLog("Creating new account");
        });

        connectButton.setOnClickListener(view -> {
            if (selectedAccount != null) {
                String sniHost = sniInput.getText().toString().trim();
                if (!sniHost.isEmpty()) {
                    selectedAccount.setSniHost(sniHost);
                }
                
                if (StatusInfo.getInstance().isActive()) {
                    disconnect();
                } else {
                    connect();
                }
            } else {
                Toast.makeText(this, "Please select an account", Toast.LENGTH_SHORT).show();
            }
        });

        updateConnectionState();
    }

    private void updateAccountCard() {
        if (selectedAccount != null) {
            accountCard.setVisibility(View.VISIBLE);
            TextView accountName = findViewById(R.id.account_name);
            TextView accountDetails = findViewById(R.id.account_details);
            
            accountName.setText(selectedAccount.getNickname());
            String details = String.format("%s@%s:%d", 
                selectedAccount.getUsername(),
                selectedAccount.getServer(),
                selectedAccount.getPort());
            accountDetails.setText(details);
            
            if (selectedAccount.getSniHost() != null && !selectedAccount.getSniHost().isEmpty()) {
                sniInput.setText(selectedAccount.getSniHost());
                sniCard.setVisibility(View.VISIBLE);
            } else {
                sniCard.setVisibility(View.GONE);
            }
        }
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            tvLogs.append(">> " + message + "\n");
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        SSHAccountManager accountManager = new SSHAccountManager(this);
        sshAccountList = accountManager.loadAccounts();
        SSHAccountAdapter adapter = new SSHAccountAdapter(this, sshAccountList);
        Spinner spinner = findViewById(R.id.spinner);
        spinner.setAdapter(adapter);
        
        updateConnectionState();
    }

    private void updateConnectionState() {
        if (StatusInfo.getInstance().isActive()) {
            connectButton.setText("DISCONNECT");
            connectButton.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_disconnect));
            tvStatus.setText("Status: Connected");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.connected_green));
            animationView.setAnimation(R.raw.connected);
            animationView.playAnimation();
        } else {
            connectButton.setText("CONNECT");
            connectButton.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_connect));
            tvStatus.setText("Status: Disconnected");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.disconnected_red));
            animationView.setAnimation(R.raw.disconnected);
            animationView.playAnimation();
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
            startActivity(new Intent(this, SettingsActivity.class));
            appendLog("Opening settings");
        } else if (id == R.id.action_logs) {
            startActivity(new Intent(this, LogViewerActivity.class));
        }
        return super.onOptionsItemSelected(item);
    }

    private void connect() {
        tvStatus.setText("Status: Connecting...");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.connecting_orange));
        animationView.setAnimation(R.raw.connecting);
        animationView.playAnimation();
        appendLog("Starting connection to: " + selectedAccount.getServer());

        Intent intentPrepare = VpnService.prepare(this);
        if (intentPrepare != null) {
            startActivityForResult(intentPrepare, 0);
            return;
        }

        try {
            sshIntent.putExtra("ssh_account", selectedAccount);
            startService(sshIntent);
            appendLog("SSH service started");

            vpnIntent.putExtra("socksPort", 1080);
            startService(vpnIntent);
            appendLog("VPN service started");
            
            StatusInfo.getInstance().setActive(true);
            updateConnectionState();
        } catch (Exception e) {
            appendLog("Error: " + e.getMessage());
            e.printStackTrace();
            disconnect();
        }
    }

    private void disconnect() {
        tvStatus.setText("Status: Disconnecting...");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.disconnecting_orange));
        animationView.setAnimation(R.raw.disconnecting);
        animationView.playAnimation();
        appendLog("Stopping services");

        StatusInfo.getInstance().setActive(false);
        
        Thread vpnThread = SocksPersistent.getInstance().getVpnThread();
        if (vpnThread != null) {
            vpnThread.interrupt();
            appendLog("VPN thread interrupted");
        }

        stopService(sshIntent);
        stopService(vpnIntent);
        appendLog("Services stopped");
        
        updateConnectionState();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 0 && resultCode == RESULT_OK) {
            connect();
        } else {
            appendLog("VPN permission denied");
            updateConnectionState();
        }
    }
}