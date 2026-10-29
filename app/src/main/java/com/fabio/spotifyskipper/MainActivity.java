package com.fabio.spotifyskipper;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "SpotifySkipperPrefs";
    public static final String KEY_ENABLED = "service_enabled";
    private static final int SHIZUKU_CODE = 1001;

    private TextView tvNotificationStatus;
    private TextView tvShizukuStatus;
    private Button btnGrantNotification;
    private Button btnGrantShizuku;
    private Switch switchEnableService;

    // Callback invocato quando l'utente risponde al popup di autorizzazione Shizuku
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_CODE) {
                    updateUI();
                }
            };

    // Listener che aggiornano la UI in tempo reale se Shizuku viene avviato o spento
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::updateUI;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::updateUI;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvNotificationStatus = findViewById(R.id.tvNotificationStatus);
        tvShizukuStatus = findViewById(R.id.tvShizukuStatus);
        btnGrantNotification = findViewById(R.id.btnGrantNotification);
        btnGrantShizuku = findViewById(R.id.btnGrantShizuku);
        switchEnableService = findViewById(R.id.switchEnableService);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isEnabled = prefs.getBoolean(KEY_ENABLED, true);
        switchEnableService.setChecked(isEnabled);

        switchEnableService.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_ENABLED, isChecked).apply();
            String msg = isChecked ? "Skipper attivato" : "Skipper in pausa";
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        btnGrantNotification.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });

        btnGrantShizuku.setOnClickListener(v -> requestShizukuPermission());

        // Registrazione dei listener del ciclo di vita di Shizuku
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();

        // Se il permesso notifiche è attivo, forziamo Android a collegare subito il servizio
        if (isNotificationServiceEnabled()) {
            NotificationListenerService.requestRebind(
                    new ComponentName(this, SpotifyAdDetectorService.class));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
    }

    private void updateUI() {
        runOnUiThread(() -> {
            // 1. Verifica stato NotificationListener
            if (isNotificationServiceEnabled()) {
                tvNotificationStatus.setText("ATTIVO - In ascolto sulla MediaSession");
                tvNotificationStatus.setTextColor(Color.parseColor("#1DB954"));
                btnGrantNotification.setEnabled(false);
                btnGrantNotification.setText("Permesso Concesso");
            } else {
                tvNotificationStatus.setText("NON ATTIVO - Clicca sotto per abilitarlo");
                tvNotificationStatus.setTextColor(Color.parseColor("#FF5252"));
                btnGrantNotification.setEnabled(true);
                btnGrantNotification.setText("Apri Impostazioni Notifiche");
            }

            // 2. Verifica stato Shizuku
            if (!Shizuku.pingBinder()) {
                tvShizukuStatus.setText("DEMONE SPENTO - Avvia l'app Shizuku (Fallback Mute attivo)");
                tvShizukuStatus.setTextColor(Color.parseColor("#FFB300"));
                btnGrantShizuku.setEnabled(true);
                btnGrantShizuku.setText("Riprova connessione Shizuku");
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                tvShizukuStatus.setText("ATTIVO E AUTORIZZATO (Modalità Kill + Riavvio pronta)");
                tvShizukuStatus.setTextColor(Color.parseColor("#1DB954"));
                btnGrantShizuku.setEnabled(false);
                btnGrantShizuku.setText("Shizuku Autorizzato");
            } else {
                tvShizukuStatus.setText("IN ESECUZIONE MA NON AUTORIZZATO");
                tvShizukuStatus.setTextColor(Color.parseColor("#FF5252"));
                btnGrantShizuku.setEnabled(true);
                btnGrantShizuku.setText("Autorizza Shizuku");
            }
        });
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(pkgName);
    }

    private void requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Il servizio Shizuku non è attivo sul telefono!", Toast.LENGTH_LONG).show();
            updateUI();
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_CODE);
        } else {
            updateUI();
        }
    }
}