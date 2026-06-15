package com.example.tsalert;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    /**
     * Action portée par la full-screen intent du service vocal : indique à
     * MainActivity d'ouvrir le compte à rebours d'annulation avant l'envoi.
     */
    public static final String ACTION_SOS_COUNTDOWN =
            "com.example.tsalert.action.SOS_COUNTDOWN";

    /** Action « oui » (confirmation vocale) : envoi immédiat, émise par le service. */
    public static final String ACTION_SOS_CONFIRM =
            "com.example.tsalert.action.SOS_CONFIRM";

    /** Action « non » (annulation vocale) : annulation immédiate, émise par le service. */
    public static final String ACTION_SOS_CANCEL =
            "com.example.tsalert.action.SOS_CANCEL";

    /** Durée du compte à rebours d'annulation avant l'envoi réel (anti faux positif). */
    private static final long COUNTDOWN_MS = 5_000L;

    private static final int REQ_SMS_LOCATION = 0; // demande liée au clic SOS
    private static final int REQ_VOICE = 1;        // demande liée à l'écoute vocale

    /** Préférence : l'écoute vocale était-elle activée ? (persiste au redémarrage) */
    private static final String KEY_VOICE_ENABLED = "voice_enabled";

    private static final String TAG = "MainActivity";

    // --- Son d'alerte joué au clic SOS ---
    private SoundPool soundPool;
    private int alertSoundId;
    private boolean alertSoundLoaded = false;
    private boolean hasRawAlertSound = false;
    private ToneGenerator toneGenerator;

    // --- Animation de pulsation décorative derrière le bouton SOS ---
    private AnimatorSet pulseAnim1;
    private AnimatorSet pulseAnim2;

    // --- Écoute vocale ---
    private CompoundButton voiceSwitch;
    private AlertDialog countdownDialog;
    private CountDownTimer countdownTimer;
    private Vibrator vibrator;
    private ToneGenerator countdownTone;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        initAlertSound();
        startPulseAnimation();
        setupContactNumberField();
        setupVoiceSwitch();
        // Si l'Activity est ouverte par la full-screen intent (écran verrouillé inclus).
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /**
     * Route les intents émis par le service vocal :
     *  - SOS_COUNTDOWN : ouvre le compte à rebours ;
     *  - SOS_CONFIRM (« oui ») : envoi immédiat ;
     *  - SOS_CANCEL (« non ») : annulation immédiate.
     */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_SOS_COUNTDOWN.equals(action)) {
            showOverLockScreen();
            startSosCountdown();
        } else if (ACTION_SOS_CONFIRM.equals(action)) {
            confirmSosNow();
        } else if (ACTION_SOS_CANCEL.equals(action)) {
            cancelCountdown();
        }
    }

    /**
     * Lance l'effet d'ondes : deux anneaux qui grandissent et s'estompent en
     * boucle, décalés dans le temps. Purement visuel : ne déclenche aucune
     * action et n'interfère pas avec sendSMS().
     */
    private void startPulseAnimation() {
        pulseAnim1 = buildPulse(findViewById(R.id.pulseRing1), 0L);
        pulseAnim2 = buildPulse(findViewById(R.id.pulseRing2), 600L);
    }

    private AnimatorSet buildPulse(View ring, long startDelay) {
        if (ring == null) {
            return null;
        }
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 2.4f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 2.4f);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.45f, 0f);
        for (ObjectAnimator anim : new ObjectAnimator[]{scaleX, scaleY, alpha}) {
            anim.setRepeatCount(ObjectAnimator.INFINITE);
            anim.setRepeatMode(ObjectAnimator.RESTART);
        }

        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY, alpha);
        set.setDuration(1600);
        set.setStartDelay(startDelay);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.start();
        return set;
    }

    /**
     * Prépare le son d'alerte une seule fois.
     * SoundPool est configuré en USAGE_ALARM / CONTENT_TYPE_SONIFICATION.
     * Si res/raw/sos_alert(.ogg/.wav/.mp3) existe, il est chargé ; sinon
     * playAlertSound() basculera sur ToneGenerator (aucune ressource requise).
     */
    private void initAlertSound() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(attrs)
                .build();

        // Ne jouer que lorsque le sample est prêt : évite un premier clic muet.
        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            if (status == 0 && sampleId == alertSoundId) {
                alertSoundLoaded = true;
            }
        });

        // Résolution à l'exécution : compile même si le fichier audio est absent.
        int rawId = getResources().getIdentifier("sos_alert", "raw", getPackageName());
        if (rawId != 0) {
            hasRawAlertSound = true;
            alertSoundId = soundPool.load(this, rawId, 1);
        }
    }

    /**
     * Joue le son d'alerte de façon non bloquante. Toute défaillance audio est
     * absorbée ici pour ne JAMAIS empêcher l'envoi de l'alerte.
     */
    private void playAlertSound() {
        try {
            if (hasRawAlertSound && alertSoundLoaded && soundPool != null) {
                soundPool.play(alertSoundId, 1f, 1f, 1, 0, 1f);
            } else {
                // Pas de fichier audio (ou pas encore chargé) -> tonalité d'alarme ~1s.
                if (toneGenerator == null) {
                    toneGenerator = new ToneGenerator(
                            AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
                }
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000);
            }
        } catch (Exception e) {
            Log.w(TAG, "Lecture du son d'alerte impossible", e);
        }
    }

    @Override
    protected void onDestroy() {
        if (pulseAnim1 != null) {
            pulseAnim1.cancel();
            pulseAnim1 = null;
        }
        if (pulseAnim2 != null) {
            pulseAnim2.cancel();
            pulseAnim2 = null;
        }
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
        cancelCountdown();
        if (countdownTone != null) {
            countdownTone.release();
            countdownTone = null;
        }
        super.onDestroy();
    }

    /**
     * Déclenché par le clic sur le bouton SOS (android:onClick="sendSMS").
     * <p>
     * MainActivity ne s'occupe plus QUE de l'UI et des permissions :
     *  - signal sonore d'alerte ;
     *  - vérification / demande des permissions runtime ;
     *  - retour visuel (Toast).
     * Toute la logique métier (GPS, géocodage, message, SMS) est déléguée à
     * {@link AlertSender}, réutilisable par un service.
     */
    public void sendSMS(View v) {
        // Signal sonore d'alerte, avant toute la logique (permissions, GPS, SMS).
        playAlertSound();

        if (!hasSmsPermission() || !hasLocationPermission()) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQ_SMS_LOCATION
            );
            return;
        }

        // Envoi asynchrone : la position est fiabilisée (cache puis fix actif
        // ~5 s) sans bloquer l'UI ; le Toast s'affiche une fois le SMS parti.
        new AlertSender(this).sendAlert(found ->
                Toast.makeText(
                        this,
                        "SMS envoyé au " + AlertSender.getNumero(this),
                        Toast.LENGTH_SHORT
                ).show());
    }

    /**
     * Pré-remplit le champ « Contact d'urgence » avec le numéro persisté
     * (ou la valeur par défaut) et sauvegarde chaque modification en
     * SharedPreferences via {@link AlertSender}. AlertSender lit ce numéro
     * à l'envoi : plus de numéro en dur.
     */
    private void setupContactNumberField() {
        EditText field = findViewById(R.id.etContactNumber);
        if (field == null) {
            return;
        }
        field.setText(AlertSender.getNumero(this));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                // Persistance immédiate ; un champ vide retombera sur la valeur
                // par défaut au moment de la lecture (AlertSender.getNumero).
                AlertSender.setNumero(MainActivity.this, s.toString());
            }
        });
    }

    // ===================== ÉCOUTE VOCALE =====================

    /**
     * Câble le Switch « Activer l'écoute vocale » et RESTAURE son état persisté.
     * Si l'utilisateur l'avait activé, le switch reste coché après un
     * redémarrage du téléphone et l'écoute se ré-arme à l'ouverture de l'app.
     * <p>
     * Note : Android n'autorise pas le démarrage fiable d'un foreground service
     * micro depuis le boot (cf. NOTES.md) ; l'écoute reprend donc à la première
     * ouverture de l'app, mais l'état du toggle, lui, est bien conservé.
     */
    private void setupVoiceSwitch() {
        voiceSwitch = findViewById(R.id.switchVoice);
        if (voiceSwitch == null) {
            return;
        }

        boolean wasEnabled = isVoiceEnabledPref();
        // Si les permissions ont été retirées entre-temps, on reflète l'état réel.
        if (wasEnabled && !hasVoicePermissions()) {
            setVoiceEnabledPref(false);
            wasEnabled = false;
        }

        // Restaure l'état visuel SANS déclencher le listener, puis l'attache.
        setVoiceSwitchChecked(wasEnabled);

        // Ré-arme réellement l'écoute si elle était activée (sans Toast au lancement).
        if (wasEnabled) {
            startVoiceService(false);
        }
    }

    /** Coche/décoche le switch sans déclencher le listener, puis (ré)attache le listener. */
    private void setVoiceSwitchChecked(boolean checked) {
        voiceSwitch.setOnCheckedChangeListener(null);
        voiceSwitch.setChecked(checked);
        voiceSwitch.setOnCheckedChangeListener(
                (button, isChecked) -> onVoiceSwitchToggled(isChecked));
    }

    /** Action utilisateur sur le switch : démarre/arrête l'écoute et persiste le choix. */
    private void onVoiceSwitchToggled(boolean isChecked) {
        if (isChecked) {
            if (hasVoicePermissions()) {
                setVoiceEnabledPref(true);
                startVoiceService(true);
            } else {
                // On demande les permissions ; le service démarrera au retour
                // si tout est accordé. On laisse le switch off en attendant.
                requestVoicePermissions();
                setVoiceSwitchChecked(false);
            }
        } else {
            setVoiceEnabledPref(false);
            stopVoiceService();
        }
    }

    /** État persisté du toggle d'écoute vocale (survit aux redémarrages). */
    private boolean isVoiceEnabledPref() {
        return getSharedPreferences(AlertSender.PREFS, MODE_PRIVATE)
                .getBoolean(KEY_VOICE_ENABLED, false);
    }

    private void setVoiceEnabledPref(boolean enabled) {
        getSharedPreferences(AlertSender.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_VOICE_ENABLED, enabled)
                .apply();
    }

    /** Permission d'envoi de SMS accordée ? */
    private boolean hasSmsPermission() {
        return checkSelfPermission(Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Permission de localisation accordée ? On accepte « Précise » (FINE) OU
     * « Approximative » (COARSE) : ainsi l'envoi n'est plus bloqué si
     * l'utilisateur choisit « Approximative » sur Android 12+.
     */
    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** RECORD_AUDIO (toujours) + POST_NOTIFICATIONS (API >= 33). */
    private boolean hasVoicePermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        // POST_NOTIFICATIONS n'est une permission runtime que depuis API 33.
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestVoicePermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        // Permissions nécessaires à l'envoi réel de l'alerte vocale : on les
        // demande dès l'activation de l'écoute pour que le SOS puisse partir.
        perms.add(Manifest.permission.SEND_SMS);
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        // POST_NOTIFICATIONS est une permission runtime seulement depuis API 33.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(perms.toArray(new String[0]), REQ_VOICE);
    }

    /**
     * Démarre le foreground service micro (jamais depuis le background).
     * @param userInitiated affiche un Toast de confirmation (false pour la
     *                       restauration silencieuse au lancement de l'app).
     */
    private void startVoiceService(boolean userInitiated) {
        Intent intent = new Intent(this, SosListenerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        if (userInitiated) {
            Toast.makeText(this, R.string.voice_started, Toast.LENGTH_SHORT).show();
        }
    }

    private void stopVoiceService() {
        stopService(new Intent(this, SosListenerService.class));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_VOICE) {
            // RECORD_AUDIO est indispensable ; POST_NOTIFICATIONS est souhaitable
            // mais le service peut tourner sans (la notif ne s'affichera pas).
            boolean micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            if (micGranted) {
                setVoiceEnabledPref(true);
                if (voiceSwitch != null) {
                    setVoiceSwitchChecked(true); // coche sans re-déclencher le listener
                }
                startVoiceService(true);
            } else {
                Toast.makeText(this, R.string.voice_perm_denied, Toast.LENGTH_LONG).show();
                setVoiceEnabledPref(false);
                if (voiceSwitch != null) {
                    setVoiceSwitchChecked(false);
                }
            }
        }
    }

    // ===================== COMPTE À REBOURS ANTI FAUX POSITIF =====================

    /**
     * Affiche un compte à rebours de 5 s avant l'envoi réel. Si rien n'est dit
     * ni touché, l'alerte PART à la fin (priorité sécurité / mains-libres).
     * Pendant le décompte : bips + vibration répétés pour prévenir en mains-libres
     * qu'un envoi est imminent et laisser le temps de dire « non ». Le service
     * écoute « oui » (envoi immédiat) / « non » (annulation). Le bouton « Annuler »
     * reste disponible comme filet pour ceux qui ont les mains libres.
     */
    private void startSosCountdown() {
        // Une seule popup à la fois.
        if (countdownDialog != null && countdownDialog.isShowing()) {
            return;
        }
        // Le compte à rebours est lancé : on retire la notification d'alerte.
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(1002); // NOTIF_ALERT_ID du service
        }

        final long total = COUNTDOWN_MS;
        countdownDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.countdown_title)
                .setMessage(getString(R.string.countdown_message, total / 1000))
                .setCancelable(false)
                .setNegativeButton(R.string.countdown_cancel, (dialog, which) -> cancelCountdown())
                .setPositiveButton(R.string.countdown_send_now, (dialog, which) -> confirmSosNow())
                .create();
        countdownDialog.show();

        startCountdownFeedback(); // bips + vibration

        countdownTimer = new CountDownTimer(total, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (countdownDialog != null) {
                    long secondsLeft = (millisUntilFinished / 1000) + 1;
                    countdownDialog.setMessage(getString(R.string.countdown_message, secondsLeft));
                }
                beepCountdown(); // bip à chaque seconde
            }

            @Override
            public void onFinish() {
                stopCountdownFeedback();
                dismissCountdownDialog();
                performVocalAlert();
            }
        }.start();
    }

    /** « oui » vocal ou bouton « Envoyer » : envoi immédiat, on saute le décompte. */
    private void confirmSosNow() {
        // Ne rien faire s'il n'y a pas de compte à rebours en cours.
        if (countdownTimer == null
                && (countdownDialog == null || !countdownDialog.isShowing())) {
            return;
        }
        if (countdownTimer != null) {
            countdownTimer.cancel();
            countdownTimer = null;
        }
        stopCountdownFeedback();
        dismissCountdownDialog();
        performVocalAlert();
    }

    /** Annulation (bouton « Annuler » ou « non » vocal) : aucun SMS envoyé. */
    private void cancelCountdown() {
        if (countdownTimer != null) {
            countdownTimer.cancel();
            countdownTimer = null;
        }
        stopCountdownFeedback();
        dismissCountdownDialog();
        // Annulation : libération immédiate de la garde → « sos » réutilisable aussitôt.
        SosListenerService.notifyCycleResolved(false);
    }

    private void dismissCountdownDialog() {
        if (countdownDialog != null) {
            if (countdownDialog.isShowing()) {
                countdownDialog.dismiss();
            }
            countdownDialog = null;
        }
    }

    /**
     * Démarre la vibration répétée et un premier bip dès l'ouverture du décompte.
     * Signaux d'alerte imminente perceptibles en mains-libres.
     */
    private void startCountdownFeedback() {
        beepCountdown();
        if (vibrator == null) {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        // Motif répété : 400 ms ON / 600 ms OFF, en boucle (index 0).
        long[] pattern = {0, 400, 600};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    /** Bip court d'alerte (tonalité dédiée, n'interfère pas avec playAlertSound). */
    private void beepCountdown() {
        try {
            if (countdownTone == null) {
                countdownTone = new ToneGenerator(
                        AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
            }
            countdownTone.startTone(ToneGenerator.TONE_PROP_BEEP, 200);
        } catch (Exception e) {
            // un échec audio ne doit jamais bloquer le flux
            Log.w(TAG, "Bip de compte à rebours impossible", e);
        }
    }

    /** Arrête la vibration en cours (fin, annulation ou envoi anticipé). */
    private void stopCountdownFeedback() {
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    /** Envoi réel après expiration du compte à rebours, via AlertSender. */
    private void performVocalAlert() {
        if (!hasSmsPermission() || !hasLocationPermission()) {
            // Impossible de demander des permissions de façon fiable dans ce flux
            // (potentiellement écran verrouillé) : on informe l'utilisateur.
            Toast.makeText(this, R.string.voice_alert_no_perm, Toast.LENGTH_LONG).show();
            // Rien n'a été envoyé : libération immédiate de la garde.
            SosListenerService.notifyCycleResolved(false);
            return;
        }
        playAlertSound();
        // Cycle résolu : on libère la garde tout de suite (l'envoi est lancé).
        SosListenerService.notifyCycleResolved(true);
        // Position fiabilisée (cache puis fix actif ~5 s) : envoi asynchrone,
        // Toast affiché une fois le SMS parti.
        new AlertSender(this).sendAlert(found ->
                Toast.makeText(this, "SMS envoyé au " + AlertSender.getNumero(this),
                        Toast.LENGTH_SHORT).show());
    }

    /** Permet l'affichage par-dessus l'écran verrouillé (full-screen intent). */
    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }
}

