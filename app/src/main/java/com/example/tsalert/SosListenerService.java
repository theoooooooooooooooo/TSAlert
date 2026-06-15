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

import org.json.JSONArray;
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

    /**
     * Seuil de confiance minimal (0.0–1.0) pour accepter « sos » comme un vrai
     * déclenchement. Anti faux positif : en dessous, la détection est ignorée.
     * NB : « sos » (sigle court) sort souvent à faible confiance — d'où ce seuil
     * abaissé à 0.3. Remonter si trop de faux positifs, baisser si de vrais
     * « sos » sont rejetés.
     */
    private static final double WAKE_CONF_THRESHOLD = 0.3;

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
     * Anti-doublon APRÈS un envoi : on ignore « sos » pendant ce délai pour ne
     * pas renvoyer une alerte tout de suite. Après une ANNULATION, pas de garde
     * (libération immédiate, on peut redire « sos » aussitôt).
     * 3 s : assez court pour enchaîner plusieurs SOS, assez long pour éviter un
     * double-envoi accidental sur la même détection.
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

    /**
     * Instance vivante du service (même process que MainActivity), pour que la
     * méthode statique {@link #notifyCycleResolved(boolean)} puisse relancer
     * proprement l'écoute à la résolution du cycle.
     */
    private static volatile SosListenerService instance;

    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;

    /**
     * Notifie le service que le cycle d'alerte est résolu (appelé par
     * MainActivity). Après ENVOI : court anti-doublon. Après ANNULATION :
     * libération immédiate (sent=false) → « sos » réutilisable aussitôt.
     * <p>
     * Relance aussi l'écoute dans un état propre : sans cela, après un premier
     * cycle (envoi ou annulation) Vosk ne redétectait plus rien.
     */
    public static void notifyCycleResolved(boolean sent) {
        alertCycleActive = false;
        sendGuardUntilElapsed = sent
                ? SystemClock.elapsedRealtime() + SEND_GUARD_MS
                : 0L;
        SosListenerService self = instance;
        if (self != null) {
            self.resumeAndResetRecognition();
        }
    }

    /**
     * Remet l'écoute dans un état propre à la fin d'un cycle.
     * <p>
     * Le SpeechService est arrêté après le premier cycle (son thread de
     * reconnaissance ne reprend pas) : {@code setPause(false)} + {@code reset()}
     * ne suffisent donc pas. On RECRÉE entièrement l'écoute — exactement ce que
     * fait le re-toggle du switch — via {@link #startRecognition()} (stop propre
     * + nouveau Recognizer/SpeechService + startListening).
     * Objectif : pouvoir redire « sos » et être détecté immédiatement.
     */
    private void resumeAndResetRecognition() {
        if (model == null) {
            return; // modèle non chargé : rien à recréer
        }
        try {
            startRecognition(); // stop propre + recréation complète de l'écoute
        } catch (Exception e) {
            Log.e(TAG, "Échec de la réinitialisation de l'écoute", e);
        }
    }

    /** Un cycle est-il en cours ? (avec auto-libération de sécurité.) */
    private boolean isCycleActive() {
        return alertCycleActive && SystemClock.elapsedRealtime() < cycleDeadlineElapsed;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
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

        // FOREGROUND_SERVICE_TYPE_MICROPHONE n'est référencé que sur API >= 30 ;
        // en dessous, on passe 0 (type ignoré par ServiceCompat).
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
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

    /**
     * (Re)configure le Recognizer en mode grammaire et démarre le SpeechService.
     * Idempotent : toute écoute en cours est d'abord proprement arrêtée, de sorte
     * que cette méthode sert AUSSI bien au démarrage initial qu'à la
     * réinitialisation entre deux cycles (cf. {@link #resumeAndResetRecognition()}).
     */
    private void startRecognition() {
        // Repart toujours d'un état propre (no-op au premier démarrage).
        stopRecognition();
        try {
            recognizer = new Recognizer(model, SAMPLE_RATE, GRAMMAR);
            // Confiances par mot : le JSON du résultat final inclut un tableau
            // "result" avec un "conf" par mot, exploité pour filtrer « sos ».
            recognizer.setWords(true);
            speechService = new SpeechService(recognizer, SAMPLE_RATE);
            speechService.startListening(this);
            Log.i(TAG, "Écoute vocale démarrée (grammaire: " + GRAMMAR + ")");
        } catch (IOException e) {
            Log.e(TAG, "Impossible de démarrer la reconnaissance", e);
            stopSelf();
        }
    }

    /**
     * Arrête et libère l'écoute courante (SpeechService + Recognizer) si présente.
     * {@code SpeechService.stop()} interrompt et joint son thread de
     * reconnaissance : une fois revenu, plus personne n'utilise le Recognizer,
     * on peut donc le fermer sans course de données.
     */
    private void stopRecognition() {
        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
            speechService = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }

    // ----- RecognitionListener -----

    @Override
    public void onResult(String hypothesis) {
        // Résultat FINAL : seul moment où « sos » peut déclencher le SOS
        // (transcription stable + confiances disponibles).
        handleHypothesis(hypothesis, "text", true); // {"text" : "...", "result":[...]}
    }

    @Override
    public void onPartialResult(String hypothesis) {
        // Partiel : instable et sans confiance → sert UNIQUEMENT à la réactivité
        // oui/non pendant un cycle, JAMAIS au déclenchement du SOS.
        handleHypothesis(hypothesis, "partial", false); // {"partial" : "..."}
    }

    @Override
    public void onFinalResult(String hypothesis) {
        handleHypothesis(hypothesis, "text", true);
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
     *  - pendant la fenêtre de confirmation : « non » annule, « oui » envoie
     *    (partiel accepté pour la réactivité) ;
     *  - sinon (au repos) : « sos » déclenche le compte à rebours, mais
     *    UNIQUEMENT sur un résultat final propre et suffisamment confiant
     *    (cf. {@link #isAcceptableWakeWord(String, java.util.List)}).
     * La comparaison se fait token par token (égalité stricte) pour ne pas
     * matcher un fragment au sein d'un autre mot.
     *
     * @param isFinal résultat final (true) ou partiel (false).
     */
    private void handleHypothesis(String hypothesisJson, String key, boolean isFinal) {
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
        } else if (isFinal && isAcceptableWakeWord(hypothesisJson, words)) {
            triggerSosFlow();
        }
    }

    /**
     * Vrai seulement si le résultat final correspond PROPREMENT au mot
     * déclencheur, pour écarter les faux positifs :
     *  1. le texte reconnu est EXACTEMENT « sos » (un seul token) : on rejette
     *     les cas où « sos » est noyé au milieu d'autres tokens ([unk], bruit…) ;
     *  2. la confiance du mot « sos » (tableau "result", via setWords(true))
     *     dépasse {@link #WAKE_CONF_THRESHOLD}.
     */
    private boolean isAcceptableWakeWord(String hypothesisJson, java.util.List<String> words) {
        // 1. « sos » doit être le SEUL token reconnu (sinon : noyé → ignoré).
        if (words.size() != 1 || !WAKE_WORD.equals(words.get(0))) {
            return false;
        }
        // 2. Confiance du mot déclencheur.
        return wakeWordConfidence(hypothesisJson) >= WAKE_CONF_THRESHOLD;
    }

    /**
     * Confiance du mot « sos » dans le tableau "result" du JSON final
     * (présent grâce à {@code recognizer.setWords(true)}). 0 si absent/illisible.
     */
    private double wakeWordConfidence(String hypothesisJson) {
        if (hypothesisJson == null) {
            return 0.0;
        }
        try {
            JSONArray result = new JSONObject(hypothesisJson).optJSONArray("result");
            if (result == null) {
                return 0.0;
            }
            for (int i = 0; i < result.length(); i++) {
                JSONObject w = result.getJSONObject(i);
                String word = w.optString("word", "").trim().toLowerCase();
                if (WAKE_WORD.equals(word)) {
                    return w.optDouble("conf", 0.0);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Confiance illisible: " + hypothesisJson, e);
        }
        return 0.0;
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
        // Libération propre du micro (SpeechService + Recognizer) et du modèle.
        stopRecognition();
        if (model != null) {
            model.close();
            model = null;
        }
        // Réinitialise la garde : un prochain démarrage repart d'un état propre.
        alertCycleActive = false;
        sendGuardUntilElapsed = 0L;
        // Évite que notifyCycleResolved n'agisse sur un service détruit.
        if (instance == this) {
            instance = null;
        }
        Log.i(TAG, "Service d'écoute arrêté, ressources libérées");
        super.onDestroy();
    }
}
