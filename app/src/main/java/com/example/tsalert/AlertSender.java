package com.example.tsalert;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.telephony.SmsManager;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Logique métier d'envoi d'alerte, extraite de MainActivity pour être
 * réutilisable aussi bien par l'Activity (clic SOS) que par un service
 * (détection vocale, à venir en phase 2).
 *
 * Responsabilités : récupération de la position GPS, géocodage, construction
 * du message et envoi du SMS au contact d'urgence.
 *
 * Cette classe NE gère PAS les permissions ni l'UI (Toast, son) : c'est le
 * rôle de l'appelant (MainActivity ou service) de s'assurer que SEND_SMS et
 * ACCESS_FINE_LOCATION sont accordées avant d'appeler {@link #sendAlert()}.
 *
 * Le comportement est strictement identique à l'ancien MainActivity.sendSMS(),
 * il a simplement été déplacé ici.
 */
public class AlertSender {

    /** Valeur par défaut du numéro du contact d'urgence (si rien n'est saisi). */
    public static final String DEFAULT_NUMERO = "+1 555-123-4567";

    /** Fichier de préférences et clé du numéro configurable par l'utilisateur. */
    public static final String PREFS = "tsalert_prefs";
    public static final String KEY_NUMERO = "emergency_number";

    private final Context context;

    /**
     * Numéro du contact d'urgence tel que configuré par l'utilisateur
     * (SharedPreferences), avec repli sur {@link #DEFAULT_NUMERO} si vide/absent.
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

    public AlertSender(Context context) {
        // applicationContext : la classe peut survivre à l'Activity (cas service).
        this.context = context.getApplicationContext();
    }

    /**
     * Construit le message de position et envoie le SMS d'urgence.
     * Suppose que les permissions SEND_SMS et ACCESS_FINE_LOCATION sont
     * déjà accordées (vérifiées par l'appelant).
     */
    @SuppressLint("MissingPermission") // permissions garanties par l'appelant
    public void sendAlert() {
        String position = buildPositionText();

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
     * Récupère la dernière position GPS connue et la met en forme, avec
     * géocodage inverse pour obtenir une adresse lisible. Retourne
     * "Position introuvable" si aucune position n'est disponible.
     */
    @SuppressLint("MissingPermission") // permissions garanties par l'appelant
    private String buildPositionText() {
        LocationManager locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        Location location =
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

        if (location == null) {
            return "Position introuvable";
        }

        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        Geocoder geocoder = new Geocoder(context, Locale.getDefault());

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

        // Lien Google Maps cliquable : bien plus exploitable par le contact
        // d'urgence que des coordonnées brutes. Construit depuis les coordonnées
        // réelles. Les libellés Latitude/Longitude/Adresse restent identiques.
        String mapsLink = "https://maps.google.com/?q=" + latitude + "," + longitude;

        return "Latitude : " + latitude +
                "\nLongitude : " + longitude +
                "\naAdresse : " + adresse +
                "\n" + mapsLink;
    }
}
