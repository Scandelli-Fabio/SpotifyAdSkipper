package com.fabio.spotifyskipper;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

public class SpotifyAdDetectorService extends NotificationListenerService {

    private static final String TAG = "SpotifyAdDetector";
    private static final String SPOTIFY_PACKAGE = "com.spotify.music";

    private MediaSessionManager mediaSessionManager;
    private MediaController spotifyController;
    private AudioManager audioManager;
    private ComponentName componentName;

    private boolean isAdCurrentlyDetected = false;
    private boolean isMutedByFallback = false;
    private volatile boolean waitingToResumePlayback = false;

    private final ExecutorService shellExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener =
            this::attachToSpotifyController;

    private final MediaController.Callback mediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            if (metadata != null) {
                analyzeMetadata(metadata);
            }
        }

        @Override
        public void onSessionDestroyed() {
            super.onSessionDestroyed();
            Log.d(TAG, "Sessione Media di Spotify distrutta.");
            detachSpotifyController();
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "Servizio connesso al sistema.");

        componentName = new ComponentName(this, SpotifyAdDetectorService.class);
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        if (mediaSessionManager != null) {
            try {
                mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName);
                List<MediaController> activeSessions = mediaSessionManager.getActiveSessions(componentName);
                attachToSpotifyController(activeSessions);
            } catch (SecurityException e) {
                Log.e(TAG, "Permesso Notification Listener mancante.", e);
            }
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (mediaSessionManager != null) {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
        }
        detachSpotifyController();
    }

    private void attachToSpotifyController(List<MediaController> controllers) {
        if (controllers == null) return;

        for (MediaController controller : controllers) {
            if (SPOTIFY_PACKAGE.equals(controller.getPackageName())) {
                if (spotifyController != null &&
                    spotifyController.getSessionToken().equals(controller.getSessionToken())) {
                    return;
                }

                detachSpotifyController();

                spotifyController = controller;
                spotifyController.registerCallback(mediaControllerCallback);
                Log.d(TAG, "Agganciato al MediaController di Spotify.");

                // Se Spotify si è appena riaperto dopo il nostro force-stop, avvia la riproduzione
                if (waitingToResumePlayback) {
                    waitingToResumePlayback = false;
                    Log.i(TAG, "Nuova sessione rilevata post-kill: invio comando Play...");
                    resumePlaybackWithRetry(spotifyController, 0);
                } else {
                    MediaMetadata currentMetadata = spotifyController.getMetadata();
                    if (currentMetadata != null) {
                        analyzeMetadata(currentMetadata);
                    }
                }
                return;
            }
        }
    }

    private void detachSpotifyController() {
        if (spotifyController != null) {
            try {
                spotifyController.unregisterCallback(mediaControllerCallback);
            } catch (Exception ignored) {}
            spotifyController = null;
        }
    }

    private boolean isSkipperEnabled() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(MainActivity.KEY_ENABLED, true);
    }

    private void analyzeMetadata(MediaMetadata metadata) {
        if (!isSkipperEnabled()) return;

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);

        boolean isAd = checkIfAd(title, artist, album, mediaId, duration);

        if (isAd) {
            if (!isAdCurrentlyDetected) {
                isAdCurrentlyDetected = true;
                Log.w(TAG, "PUBBLICITÀ RILEVATA! [Titolo: " + title + " | Durata: " + duration + "ms]");
                onAdStarted();
            }
        } else {
            if (isAdCurrentlyDetected) {
                Log.i(TAG, "Musica ripresa: " + title + " - " + artist);
                onMusicResumed();
            }
            isAdCurrentlyDetected = false;
        }
    }

    private boolean checkIfAd(String title, String artist, String album, String mediaId, long duration) {
        if (mediaId != null && mediaId.startsWith("spotify:ad:")) {
            return true;
        }

        if (title != null) {
            String t = title.trim().toLowerCase();
            if (t.equals("advertisement") || t.equals("pubblicità") || t.equals("spotify")) {
                return true;
            }
        }

        boolean missingArtistOrAlbum = (artist == null || artist.trim().isEmpty()) ||
                                       (album == null || album.trim().isEmpty());

        return missingArtistOrAlbum && duration > 0 && duration <= 35000;
    }

    private void onAdStarted() {
        // Controlla se Shizuku è attivo e autorizzato
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            killAndRestartWithShizuku();
        } else {
            // Fallback automatico: silenzia lo stream musicale se Shizuku non è attivo
            Log.w(TAG, "Shizuku non disponibile: attivo fallback MUTE.");
            if (audioManager != null) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                isMutedByFallback = true;
            }
        }
    }

    private void onMusicResumed() {
        if (isMutedByFallback && audioManager != null) {
            Log.i(TAG, "Disattivo MUTE fallback: ripristino volume musicale.");
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            isMutedByFallback = false;
        }
    }

    private void killAndRestartWithShizuku() {
        waitingToResumePlayback = true;

        shellExecutor.execute(() -> {
            try {
                // 1. force-stop chiude il Foreground Service di Spotify
                // 2. sleep 0.4 attende il rilascio dei buffer audio
                // 3. am start rilancia Spotify aggirando il blocco Background Activity Launch grazie a UID 2000
                String cmd = "am force-stop " + SPOTIFY_PACKAGE +
                             " && sleep 0.4" +
                             " && am start -n " + SPOTIFY_PACKAGE + "/.MainActivity --activity-no-animation";

                runShizukuCommand(cmd);
                Log.d(TAG, "Comando Shizuku completato. In attesa della nuova MediaSession...");
            } catch (Exception e) {
                waitingToResumePlayback = false;
                Log.e(TAG, "Errore durante l'esecuzione di Shizuku", e);
            }
        });
    }

    private void runShizukuCommand(String command) throws Exception {
        Method newProcess = Shizuku.class.getDeclaredMethod(
                "newProcess", String[].class, String[].class, String.class);
        newProcess.setAccessible(true);

        ShizukuRemoteProcess process = (ShizukuRemoteProcess) newProcess.invoke(
                null, new String[]{"sh", "-c", command}, null, null);

        if (process != null) {
            process.waitFor();
            process.destroy();
        }
    }

    private void resumePlaybackWithRetry(final MediaController controller, final int attempt) {
        if (attempt >= 6 || controller == null) {
            Log.w(TAG, "Tentativi di Play terminati.");
            return;
        }

        mainHandler.postDelayed(() -> {
            try {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    Log.i(TAG, "Riproduzione ripresa con successo al tentativo " + attempt);
                    isAdCurrentlyDetected = false;
                    return;
                }

                Log.d(TAG, "Invio comando PLAY (Tentativo " + (attempt + 1) + ")...");
                controller.getTransportControls().play();
                controller.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
                controller.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));

                resumePlaybackWithRetry(controller, attempt + 1);
            } catch (Exception e) {
                Log.e(TAG, "Errore durante resumePlaybackWithRetry", e);
            }
        }, 400);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        shellExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }
}