package com.famspace.unlock;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One-time cleanup for installs that ran the previous build's cache patcher.
 *
 * That patcher treated every file under the app data directory as UTF-8 text
 * and rewrote wallet fields in place. The app stores its login token in MMKV,
 * a binary key-value store, so this damaged MMKV data and made the token
 * unreadable. Dio then rejected the resulting Authorization header before
 * sending requests. The MMKV records cannot be reconstructed reliably; clear
 * the MMKV directories once, before Flutter/MMKV initializes, so the user can
 * log in again. The proxy itself no longer writes to app storage.
 */
public final class Repair {

    /** status included in the local proxy's diagnostic banner */
    public static volatile String state = "not run";

    private static final String MARKER = "famunlock.repaired";
    private static final byte[] AUTH = "auth_access_token".getBytes();
    private static final byte[] SHIFTED_AUTH = "ccess_token".getBytes();
    private static final byte[] JWT = "eyJ".getBytes();
    private static final byte[] REPLACEMENT = {(byte) 0xef, (byte) 0xbf, (byte) 0xbd};

    private Repair() {
    }

    /** Called from Application.attachBaseContext(), before Flutter starts. */
    public static void heal(Context context) {
        if (context == null) {
            state = "skipped: no context";
            return;
        }
        try {
            String dataDir = context.getApplicationInfo().dataDir;
            if (dataDir == null) {
                state = "skipped: no data dir";
                return;
            }
            healDataDir(new File(dataDir));
        } catch (Throwable t) {
            state = "skipped: " + t;
        }
    }

    /** Package-private for the plain-Java regression test. */
    static void healDataDir(File base) {
        try {
            File files = new File(base, "files");
            File marker = new File(files, MARKER);
            if (marker.isFile()) {
                state = "done earlier";
                return;
            }

            boolean removed = false;
            for (File dir : stores(base)) {
                if (dir.exists() && damaged(dir)) {
                    wipe(dir);
                    removed = true;
                }
            }

            if (!files.isDirectory() && !files.mkdirs() && !files.isDirectory()) {
                state = "failed: cannot create repair marker directory";
                return;
            }
            FileOutputStream out = new FileOutputStream(marker);
            try {
                out.write((removed ? "cleared MMKV after cache corruption" : "no MMKV store found")
                        .getBytes("UTF-8"));
            } finally {
                out.close();
            }
            state = removed ? "cleared MMKV - log in again" : "store ok";
        } catch (Throwable t) {
            // Do not create a marker on failure; a later startup can retry.
            state = "failed: " + t;
        }
    }

    private static boolean damaged(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return false;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                if (damaged(f)) {
                    return true;
                }
                continue;
            }
            byte[] raw = read(f);
            if (raw == null) {
                continue;
            }
            int key = indexOf(raw, AUTH, 0);
            int shifted = key >= 0 ? key : indexOf(raw, SHIFTED_AUTH, 0);
            if (shifted < 0) {
                continue;
            }
            int jwt = indexOf(raw, JWT, shifted + SHIFTED_AUTH.length);
            int replacement = indexOf(raw, REPLACEMENT, shifted + SHIFTED_AUTH.length);
            if (jwt >= 0 && replacement >= 0 && replacement < jwt
                    && jwt - shifted < 128) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) {
                j++;
            }
            if (j == needle.length) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] read(File file) {
        try {
            long length = file.length();
            if (length <= 0 || length > 16L * 1024L * 1024L) {
                return null;
            }
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                byte[] data = new byte[(int) length];
                int offset = 0;
                while (offset < data.length) {
                    int n = in.read(data, offset, data.length - offset);
                    if (n < 0) {
                        break;
                    }
                    offset += n;
                }
                return offset == data.length ? data : null;
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<File> stores(File base) {
        List<File> out = new ArrayList<File>();
        out.add(new File(base, "files/mmkv"));
        out.add(new File(base, "app_flutter/mmkv"));
        out.add(new File(base, "mmkv"));
        out.add(new File(base, "cache/mmkv"));
        out.add(new File(base, "no_backup/mmkv"));
        return out;
    }

    private static void wipe(File file) {
        File[] kids = file.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                wipe(kid);
            }
        }
        file.delete();
    }
}
