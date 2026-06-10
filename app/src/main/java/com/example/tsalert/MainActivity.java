package com.example.tsalert;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
    }

    public void sendSMS(View v) {
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

