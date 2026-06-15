package com.example.tsalert;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logique métier d'envoi d'alerte, réutilisable par l'Activity (clic SOS) et le
 * service (détection vocale).
 *
 * Fiabilisation de la position (asynchrone, sans bloquer le thread appelant) :
 *  1. fix immédiat depuis le cache : getLastKnownLocation GPS puis NETWORK ;
 *  2. si rien en cache, demande un fix ACTIF (getCurrentLocation sur API >= 30,
 *     repli requestSingleUpdate pour API 24-29) avec timeout ~5 s ;
 *  3. construit le message (coordonnées + adresse + lien Maps) à partir du
 *     meilleur fix ; si aucun fix au timeout, "Position introuvable" mais
 *     l'alerte est ENVOYÉE quand même.
 *
 * getCurrentLocation étant asynchrone, l'envoi se fait par callback. Le
 * géocodage (bloquant) et l'envoi du SMS sont exécutés hors du thread principal.
 *
 * Ne gère PAS les permissions : l'appelant garantit SEND_SMS et
 * ACCESS_FINE_LOCATION avant d'appeler {@link #sendAlert()}.
 */
public class AlertSender {

    /** Valeur par défaut du numéro du contact d'urgence (si rien n'est saisi). */
    public static final String DEFAULT_NUMERO = "+1 555-123-4567";

    /** Fichier de préférences et clé du numéro configurable par l'utilisateur. */
    public static final String PREFS = "tsalert_prefs";
    public static final String KEY_NUMERO = "emergency_number";

    /** Délai max d'attente d'un fix GPS actif avant d'envoyer sans position. */
    private static final long FIX_TIMEOUT_MS = 5_000L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public AlertSender(Context context) {
        // applicationContext : la classe peut survivre à l'Activity (cas service).
        this.context = context.getApplicationContext();
    }

    /** Callback optionnel, invoqué sur le thread principal après l'envoi du SMS. */
    public interface Callback {
        void onSent(boolean positionFound);
    }

    // ===================== Numéro du contact (SharedPreferences) =====================

    /**
     * Numéro du contact d'urgence configuré par l'utilisateur, avec repli sur
     * {@link #DEFAULT_NUMERO} si vide/absent.
     */
    public static String getNumero(Context context) {
        String saved = context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_NUMERO, DEFAULT_NUMERO);
        if (saved == null || saved.trim().isEmpty()) {
            return DEFAULT_NUMERO;
        }
        return saved.trim();
    }

    /** Persiste le numéro saisi par l'utilisateur. */
    public static void setNumero(Context context, String numero) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NUMERO, numero)
                .apply();
    }

    // ===================== Envoi de l'alerte =====================

    /** Variante simple sans callback. */
    public void sendAlert() {
        sendAlert(null);
    }

    /**
     * Récupère la meilleure position disponible (cache puis fix actif) puis
     * envoie le SMS d'urgence. Non bloquant. {@code callback} est invoqué sur le
     * thread principal une fois le SMS parti.
     */
    @SuppressLint("MissingPermission") // permissions garanties par l'appelant
    public void sendAlert(@Nullable Callback callback) {
        LocationManager lm =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            dispatchSend(null, callback);
            return;
        }

        // 1. Fix immédiat depuis le cache : GPS puis NETWORK.
        Location cached = lastKnown(lm, LocationManager.GPS_PROVIDER);
        if (cached == null) {
            cached = lastKnown(lm, LocationManager.NETWORK_PROVIDER);
        }
        if (cached != null) {
            dispatchSend(cached, callback);
            return;
        }

        // 2. Rien en cache : on demande un fix actif (avec timeout).
        requestActiveFix(lm, callback);
    }

    @SuppressLint("MissingPermission")
    private Location lastKnown(LocationManager lm, String provider) {
        try {
            return lm.getLastKnownLocation(provider);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Demande une position active : getCurrentLocation (API >= 30) ou
     * requestSingleUpdate (API 24-29). Garantit un seul envoi (timeout OU fix).
     */
    @SuppressLint("MissingPermission")
    private void requestActiveFix(LocationManager lm, @Nullable Callback callback) {
        final String provider = pickProvider(lm);
        if (provider == null) {
            dispatchSend(null, callback); // aucun fournisseur actif
            return;
        }

        final AtomicBoolean done = new AtomicBoolean(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API >= 30 : getCurrentLocation (un seul fix, haute précision via GPS).
            final CancellationSignal cancel = new CancellationSignal();
            final Runnable timeout = () -> {
                if (done.compareAndSet(false, true)) {
                    cancel.cancel();
                    dispatchSend(null, callback);
                }
            };
            mainHandler.postDelayed(timeout, FIX_TIMEOUT_MS);
            try {
                lm.getCurrentLocation(provider, cancel, context.getMainExecutor(),
                        location -> {
                            if (done.compareAndSet(false, true)) {
                                mainHandler.removeCallbacks(timeout);
                                dispatchSend(location, callback); // peut être null
                            }
                        });
            } catch (Exception e) {
                if (done.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeout);
                    dispatchSend(null, callback);
                }
            }
        } else {
            // API 24-29 : requestSingleUpdate + timeout manuel.
            final LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (done.compareAndSet(false, true)) {
                        mainHandler.removeCallbacksAndMessages(this);
                        try { lm.removeUpdates(this); } catch (Exception ignored) { }
                        dispatchSend(location, callback);
                    }
                }

                @Override public void onStatusChanged(String p, int s, Bundle extras) { }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            };
            final Runnable timeout = () -> {
                if (done.compareAndSet(false, true)) {
                    try { lm.removeUpdates(listener); } catch (Exception ignored) { }
                    dispatchSend(null, callback);
                }
            };
            try {
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
                mainHandler.postDelayed(timeout, FIX_TIMEOUT_MS);
            } catch (Exception e) {
                if (done.compareAndSet(false, true)) {
                    dispatchSend(null, callback);
                }
            }
        }
    }

    /** Choisit le meilleur fournisseur actif : GPS (précis) sinon NETWORK. */
    private String pickProvider(LocationManager lm) {
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Géocode, construit le message et envoie le SMS HORS du thread principal
     * (le géocodage est bloquant), puis notifie le callback sur le thread
     * principal.
     */
    private void dispatchSend(@Nullable Location location, @Nullable Callback callback) {
        new Thread(() -> {
            String position = buildPositionText(location);
            sendSms(position);
            if (callback != null) {
                final boolean found = location != null;
                mainHandler.post(() -> callback.onSent(found));
            }
        }, "AlertSender-send").start();
    }

    @SuppressLint("MissingPermission") // permissions garanties par l'appelant
    private void sendSms(String position) {
        SmsManager smsManager = SmsManager.getDefault();
        smsManager.sendTextMessage(
                getNumero(context),
                null,
                "Urgence veillez me trouvez à cette position : " + position,
                null,
                null
        );
    }

    /**
     * Met en forme la position (géocodage inverse + lien Maps) à partir du fix.
     * Retourne "Position introuvable" si aucun fix n'a été obtenu.
     */
    private String buildPositionText(@Nullable Location location) {
        if (location == null) {
            return "Position introuvable";
        }

        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        Geocoder geocoder = new Geocoder(context, Locale.getDefault());

        String adresse = "";
        try {
            List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
            if (addresses != null && !addresses.isEmpty()) {
                adresse = addresses.get(0).getAddressLine(0);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Lien Google Maps cliquable, bien plus exploitable que des coordonnées brutes.
        String mapsLink = "https://maps.google.com/?q=" + latitude + "," + longitude;

        return "Latitude : " + latitude +
                "\nLongitude : " + longitude +
                "\naAdresse : " + adresse +
                "\n" + mapsLink;
    }
}
