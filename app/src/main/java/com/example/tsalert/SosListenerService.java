package com.example.tsalert;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;

/**
 * Foreground service d'écoute vocale hors ligne (Vosk).
 *
 * Charge le modèle Vosk français depuis les assets, démarre une écoute continue
 * du micro et déclenche le flux d'alerte SOS lorsque le mot « sos » est reconnu.
 *
 * IMPORTANT — anti faux positif : la détection n'envoie JAMAIS un SMS
 * directement. Elle ouvre MainActivity via une notification full-screen qui
 * lance un compte à rebours d'annulation de 5 s avant l'envoi réel (voir
 * {@link MainActivity}).
 *
 * Démarrage : uniquement depuis l'app au premier plan (toggle dans
 * MainActivity). Ne JAMAIS tenter de démarrer ce service depuis le background
 * (restrictions des foreground services de type micro). Voir NOTES.md.
 */
public class SosListenerService extends Service implements RecognitionListener {

    private static final String TAG = "SosListenerService";

    /**
     * Nom du dossier d'assets contenant le modèle Vosk français small.
     * Les fichiers du modèle (am/, conf/, graph/, ivector/, ...) doivent être
     * placés DIRECTEMENT sous app/src/main/assets/model-fr/.
     */
    public static final String MODEL_ASSET = "model-fr";

    /** Canal de la notification persistante d'écoute (importance basse). */
    private static final String CHANNEL_LISTENING = "sos_listening";
    /** Canal de l'alerte full-screen (importance haute, pour réveiller l'écran). */
    private static final String CHANNEL_ALERT = "sos_alert";

    private static final int NOTIF_LISTENING_ID = 1001;
    private static final int NOTIF_ALERT_ID = 1002;

    /** Fréquence d'échantillonnage attendue par les modèles Vosk. */
    private static final float SAMPLE_RATE = 16000.0f;

    /**
     * Mot déclencheur. Facile à changer : ex. "secours" ou "alerte".
     * Doit être en minuscules et présent dans le vocabulaire du modèle Vosk.
     * La grammaire et la comparaison de détection en découlent automatiquement.
     */
    public static final String WAKE_WORD = "sos";

    /** Mot de confirmation vocale (« oui » → envoi immédiat). */
    public static final String CONFIRM_WORD = "oui";
    /** Mot d'annulation vocale (« non » → annulation immédiate). */
    public static final String CANCEL_WORD = "non";

    /**
     * Grammaire restreinte : mot déclencheur + confirmation/annulation vocale +
     * token spécial « [unk] » (tout le reste). Réduit faux positifs et CPU.
     */
    private static final String GRAMMAR = "[\""
            + WAKE_WORD + "\", \""
            + CONFIRM_WORD + "\", \""
            + CANCEL_WORD + "\", \"[unk]\"]";

    /**
     * Garde-fou : durée maximale d'un cycle d'alerte (auto-libération si
     * MainActivity ne notifie jamais la résolution, ex. process tué). Doit
     * couvrir les ~5 s de compte à rebours + une marge de latence.
     */
    private static final long CYCLE_MAX_MS = 8_000L;

    /**
     * Court anti-doublon APRÈS un envoi : on ignore « sos » pendant ce délai pour
     * ne pas renvoyer un SMS immédiatement. Après une ANNULATION, pas de garde
     * (libération immédiate, on peut redire « sos » tout de suite).
     */
    private static final long SEND_GUARD_MS = 3_000L;

    // --- Garde d'état (partagée Activity/Service, même process) ---
    // Un seul cycle d'alerte actif à la fois : tant qu'il tourne, « sos » est
    // ignoré ; dès résolution (oui/non/timeout), la garde est libérée.
    private static volatile boolean alertCycleActive = false;
    /** Échéance d'auto-libération du cycle (garde-fou anti-blocage). */
    private static volatile long cycleDeadlineElapsed = 0L;
    /** Échéance du court anti-doublon post-envoi. */
    private static volatile long sendGuardUntilElapsed = 0L;

    private Model model;
    private SpeechService speechService;

    /**
     * Notifie le service que le cycle d'alerte est résolu (appelé par
     * MainActivity). Après ENVOI : court anti-doublon. Après ANNULATION :
     * libération immédiate (sent=false) → « sos » réutilisable aussitôt.
     */
    public static void notifyCycleResolved(boolean sent) {
        alertCycleActive = false;
        sendGuardUntilElapsed = sent
                ? SystemClock.elapsedRealtime() + SEND_GUARD_MS
                : 0L;
    }

    /** Un cycle est-il en cours ? (avec auto-libération de sécurité.) */
    private boolean isCycleActive() {
        return alertCycleActive && SystemClock.elapsedRealtime() < cycleDeadlineElapsed;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Passer immédiatement en foreground (obligatoire dès le démarrage).
        startListeningNotification();
        initModel();
        // START_NOT_STICKY : un FGS micro ne peut pas être relancé de façon
        // fiable par le système en background ; l'utilisateur réactive l'écoute
        // depuis l'app. Voir NOTES.md.
        return START_NOT_STICKY;
    }

    /** Place le service au premier plan avec la notification d'écoute. */
    private void startListeningNotification() {
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_LISTENING)
                .setContentTitle(getString(R.string.voice_notif_title))
                .setContentText(getString(R.string.voice_notif_text))
                .setSmallIcon(R.drawable.ic_alert)
                .setOngoing(true)
                .setContentIntent(activityPendingIntent(null))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                : 0;
        ServiceCompat.startForeground(this, NOTIF_LISTENING_ID, notif, type);
    }

    /** Décompresse le modèle depuis les assets puis démarre l'écoute. */
    private void initModel() {
        StorageService.unpack(
                this,
                MODEL_ASSET,
                "model",
                unpacked -> {
                    this.model = unpacked;
                    startRecognition();
                },
                exception -> {
                    Log.e(TAG, "Échec de chargement du modèle Vosk", exception);
                    Toast.makeText(
                            this,
                            getString(R.string.voice_model_error),
                            Toast.LENGTH_LONG
                    ).show();
                    stopSelf();
                }
        );
    }

    /** Configure le Recognizer en mode grammaire et démarre le SpeechService. */
    private void startRecognition() {
        try {
            Recognizer recognizer = new Recognizer(model, SAMPLE_RATE, GRAMMAR);
            speechService = new SpeechService(recognizer, SAMPLE_RATE);
            speechService.startListening(this);
            Log.i(TAG, "Écoute vocale démarrée (grammaire: " + GRAMMAR + ")");
        } catch (IOException e) {
            Log.e(TAG, "Impossible de démarrer la reconnaissance", e);
            stopSelf();
        }
    }

    // ----- RecognitionListener -----

    @Override
    public void onResult(String hypothesis) {
        // LOG TEMPORAIRE : transcription brute de Vosk (à retirer en prod).
        Log.d(TAG, "result brut: " + hypothesis);
        handleHypothesis(hypothesis, "text"); // {"text" : "..."}
    }

    @Override
    public void onPartialResult(String hypothesis) {
        // LOG TEMPORAIRE : transcription partielle brute de Vosk (à retirer en prod).
        Log.d(TAG, "partialResult brut: " + hypothesis);
        handleHypothesis(hypothesis, "partial"); // {"partial" : "..."} — réactivité accrue
    }

    @Override
    public void onFinalResult(String hypothesis) {
        // LOG TEMPORAIRE : transcription finale brute de Vosk (à retirer en prod).
        Log.d(TAG, "finalResult brut: " + hypothesis);
        handleHypothesis(hypothesis, "text");
    }

    @Override
    public void onError(Exception e) {
        Log.e(TAG, "Erreur de reconnaissance vocale", e);
    }

    @Override
    public void onTimeout() {
        // Rien de spécial : SpeechService gère la reprise de l'écoute.
    }

    /**
     * Route une hypothèse Vosk selon l'état :
     *  - pendant la fenêtre de confirmation : « non » annule, « oui » envoie ;
     *  - sinon (au repos) : « sos » déclenche le compte à rebours.
     * La comparaison se fait token par token (égalité stricte) pour ne pas
     * matcher un fragment au sein d'un autre mot.
     */
    private void handleHypothesis(String hypothesisJson, String key) {
        String text = extractText(hypothesisJson, key);
        if (text.isEmpty()) {
            return;
        }
        java.util.List<String> words = java.util.Arrays.asList(text.split("\\s+"));

        if (isCycleActive()) {
            // Cycle en cours : on écoute la réponse. « non » (annulation)
            // prioritaire sur « oui » : en cas d'ambiguïté, on évite un envoi.
            // On libère la garde tout de suite pour ne pas router en double ;
            // MainActivity confirmera la résolution via notifyCycleResolved().
            if (words.contains(CANCEL_WORD)) {
                alertCycleActive = false;
                Log.i(TAG, "« non » détecté → annulation vocale");
                sendActionToActivity(MainActivity.ACTION_SOS_CANCEL);
            } else if (words.contains(CONFIRM_WORD)) {
                alertCycleActive = false;
                Log.i(TAG, "« oui » détecté → envoi vocal immédiat");
                sendActionToActivity(MainActivity.ACTION_SOS_CONFIRM);
            }
        } else if (words.contains(WAKE_WORD)) {
            triggerSosFlow();
        }
    }

    /** Extrait le texte (minuscules) de la clé demandée d'une hypothèse JSON. */
    private String extractText(String hypothesisJson, String key) {
        if (hypothesisJson == null) {
            return "";
        }
        try {
            return new JSONObject(hypothesisJson).optString(key, "").trim().toLowerCase();
        } catch (Exception e) {
            Log.w(TAG, "Hypothèse illisible: " + hypothesisJson, e);
            return "";
        }
    }

    /**
     * Relance MainActivity avec l'action oui/non. Le compte à rebours est alors
     * au premier plan : un startActivity direct est autorisé (app visible).
     */
    private void sendActionToActivity(String action) {
        Intent i = new Intent(this, MainActivity.class)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    /**
     * Déclenche le flux d'alerte (avec anti-rebond) : notification full-screen
     * qui ouvre MainActivity sur le compte à rebours d'annulation, et ouvre la
     * fenêtre d'écoute « oui »/« non ». N'envoie PAS le SMS directement.
     */
    private void triggerSosFlow() {
        long now = SystemClock.elapsedRealtime();
        if (isCycleActive()) {
            return; // un cycle d'alerte est déjà en cours
        }
        if (now < sendGuardUntilElapsed) {
            return; // court anti-doublon juste après un envoi
        }
        // Ouvre le cycle : « sos » est ignoré jusqu'à résolution (oui/non/timeout),
        // « oui »/« non » seront interprétés tant que le cycle est actif.
        alertCycleActive = true;
        cycleDeadlineElapsed = now + CYCLE_MAX_MS;
        Log.i(TAG, "« sos » détecté → ouverture du compte à rebours");

        Intent fullScreenIntent = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_SOS_COUNTDOWN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent fullScreenPi = PendingIntent.getActivity(
                this,
                1,
                fullScreenIntent,
                pendingIntentFlags()
        );

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setContentTitle(getString(R.string.voice_alert_title))
                .setContentText(getString(R.string.voice_alert_text))
                .setSmallIcon(R.drawable.ic_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(fullScreenPi);

        // Full-screen intent : ouvre l'Activity même écran verrouillé, sans
        // startActivity direct depuis le background (cas urgent autorisé).
        // Sur API >= 34, elle est rétrogradée en heads-up si la permission
        // USE_FULL_SCREEN_INTENT n'est pas effective : on vérifie d'abord.
        if (canUseFullScreenIntent(nm)) {
            builder.setFullScreenIntent(fullScreenPi, true);
        } else {
            // Repli : notification heads-up classique (priorité haute + canal
            // IMPORTANCE_HIGH). L'utilisateur appuie pour ouvrir le compte à rebours.
            Log.w(TAG, "Full-screen intent indisponible → repli heads-up");
        }

        nm.notify(NOTIF_ALERT_ID, builder.build());
    }

    /**
     * Avant API 34, les full-screen intents sont toujours autorisées. À partir
     * d'Android 14, elles ne le sont que si l'utilisateur/le système a accordé
     * USE_FULL_SCREEN_INTENT (auto pour les apps d'appel/réveil uniquement).
     */
    private boolean canUseFullScreenIntent(NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true;
        }
        return nm.canUseFullScreenIntent();
    }

    private PendingIntent activityPendingIntent(@Nullable String action) {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (action != null) {
            i.setAction(action);
        }
        return PendingIntent.getActivity(this, 0, i, pendingIntentFlags());
    }

    private int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    /** Crée les canaux de notification (obligatoire pour API >= 26). */
    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel listening = new NotificationChannel(
                CHANNEL_LISTENING,
                getString(R.string.voice_channel_listening),
                NotificationManager.IMPORTANCE_LOW
        );
        listening.setDescription(getString(R.string.voice_channel_listening_desc));

        NotificationChannel alert = new NotificationChannel(
                CHANNEL_ALERT,
                getString(R.string.voice_channel_alert),
                NotificationManager.IMPORTANCE_HIGH
        );
        alert.setDescription(getString(R.string.voice_channel_alert_desc));

        nm.createNotificationChannel(listening);
        nm.createNotificationChannel(alert);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // service non lié
    }

    @Override
    public void onDestroy() {
        // Libération propre du micro et du modèle.
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
            speechService = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
        // Réinitialise la garde : un prochain démarrage repart d'un état propre.
        alertCycleActive = false;
        sendGuardUntilElapsed = 0L;
        Log.i(TAG, "Service d'écoute arrêté, ressources libérées");
        super.onDestroy();
    }
}
