package com.famspace.unlock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Trigger for the in-app rewrite proxy.
 *
 * The Android framework instantiates every declared ContentProvider in the
 * main process during start-up - before Application.onCreate() and long
 * before the Flutter engine runs.  Declaring this provider therefore starts
 * the proxy before the app issues its first API request, without needing a
 * second app, a service or a notification.
 */
public final class ProxyProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        ProxyHolder.ensureStarted(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
