package com.gathin.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Hands the TV's location (the zone, its main town and coordinates) to the companion apps, so the
 * Weather app opens on the same place as Home. Read-only, and only for apps signed with the same key
 * (the permission is "signature"): content://com.gathin.tvhome.location.
 */
public class LocationProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{"code", "town", "state", "lat", "lon"});
        SharedPreferences p = Location.prefs(getContext());
        String lat = p.getString("now_lat", null), lon = p.getString("now_lon", null);
        if (lat != null && lon != null) {
            c.addRow(new Object[]{p.getString("now_code", ""), p.getString("now_town", ""), p.getString("now_state", ""),
                    Double.parseDouble(lat), Double.parseDouble(lon)});
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
