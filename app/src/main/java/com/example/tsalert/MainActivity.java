package com.example.tsalert;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    // --- Son d'alerte joué au clic SOS ---
    private SoundPool soundPool;
    private int alertSoundId;
    private boolean alertSoundLoaded = false;
    private boolean hasRawAlertSound = false;
    private ToneGenerator toneGenerator;

    // --- Animation de pulsation décorative derrière le bouton SOS ---
    private AnimatorSet pulseAnim1;
    private AnimatorSet pulseAnim2;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        initAlertSound();
        startPulseAnimation();
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
        soundPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                if (status == 0 && sampleId == alertSoundId) {
                    alertSoundLoaded = true;
                }
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
            e.printStackTrace();
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
        super.onDestroy();
    }

    public void sendSMS(View v) {
        // Signal sonore d'alerte, avant toute la logique (permissions, GPS, SMS).
        playAlertSound();

        String numeroTel = "+1 555-123-4567";
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    0
            );
            return;
        }

        LocationManager locationManager =
                (LocationManager) getSystemService(LOCATION_SERVICE);

        Location location =
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

        String position;

        if (location != null) {

            double latitude = location.getLatitude();
            double longitude = location.getLongitude();
            Geocoder geocoder =
                    new Geocoder(this, Locale.getDefault());

            String adresse = "";

            try {

                List<Address> addresses =
                        geocoder.getFromLocation(
                                latitude,
                                longitude,
                                1
                        );

                if (addresses != null && !addresses.isEmpty()) {

                    Address address = addresses.get(0);

                    adresse = address.getAddressLine(0);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }

            position = "Latitude : " + latitude +
                    "\nLongitude : " + longitude +
                    "\naAdresse : " + adresse;

        } else {

            position = "Position introuvable";
        }

        SmsManager smsManager = SmsManager.getDefault();
        smsManager.sendTextMessage(
                numeroTel,
                null,
                "Urgence veillez me trouvez à cette position : " + position,
                null,
                null
        );

        Toast.makeText(
                this,
                "SMS envoyé au " + numeroTel,
                Toast.LENGTH_SHORT
        ).show();
    }
}

