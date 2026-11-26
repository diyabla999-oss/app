package com.famspace.unlock;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The API does not answer with plain JSON - every payload is encrypted into
 * the "responseData" field:
 *
 *   {"success":true,"code":200,"responseData":"<base64>","t":1795723924}
 *
 * The Dart client uses the `encrypt` package (pointycastle): AES with PKCS7
 * padding.  The pointycastle "encrypt" package puts the IV (or nonce) in
 * front of the ciphertext, so the blob is one of
 *
 *   [16 IV][ciphertext]            CBC (ciphertext is a multiple of 16) / CTR
 *   [1][16 IV][ciphertext]         CBC, with a leading version byte
 *   [12 nonce][ciphertext][16 tag] GCM
 *
 * The proxy does not know the key - it has to find it.  It learns it from the
 * traffic: a key that is delivered in the clear (config, device/install), or
 * one derived from the bearer token, is tried against the encrypted payloads
 * and the one that really decrypts them is remembered.
 */
public final class Crypto {

    /** how the blob is laid out, and with which AES mode */
    public static final class Combo {
        public final String mode;      // AES/CBC/PKCS5Padding, AES/CTR/NoPadding, ...
        public final int ivOffset;     // where the IV/nonce starts
        public final int ctOffset;     // where the ciphertext starts
        public final int ctLength;     // length of the ciphertext part

        Combo(String mode, int ivOffset, int ctOffset, int ctLength) {
            this.mode = mode;
            this.ivOffset = ivOffset;
            this.ctOffset = ctOffset;
            this.ctLength = ctLength;
        }

        @Override
        public String toString() {
            return mode + "[iv@" + ivOffset + ",ct@" + ctOffset + "+" + ctLength + "]";
        }
    }

    private Crypto() {
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /**
     * Base64 without java.util.Base64 (that one is API 26+, the app targets 24).
     */
    public static byte[] decodeBase64(String s) {
        if (s == null) {
            return null;
        }
        int[] table = new int[256];
        for (int i = 0; i < table.length; i++) {
            table[i] = -1;
        }
        for (int i = 0; i < B64.length; i++) {
            table[B64[i]] = i;
        }
        table['='] = -2;
        int bits = 0;
        int acc = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') {
                continue;
            }
            int v = table[ch & 0xff];
            if (v == -1) {
                return null;
            }
            if (v == -2) {
                break;
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    public static String encodeBase64(byte[] b) {
        StringBuilder sb = new StringBuilder((b.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < b.length) {
            int n = ((b[i] & 0xff) << 16) | ((b[i + 1] & 0xff) << 8) | (b[i + 2] & 0xff);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63])
                    .append(B64[(n >> 6) & 63]).append(B64[n & 63]);
            i += 3;
        }
        if (i + 1 == b.length) {
            int n = (b[i] & 0xff) << 16;
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append("==");
        } else if (i + 2 == b.length) {
            int n = ((b[i] & 0xff) << 16) | ((b[i + 1] & 0xff) << 8);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append(B64[(n >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    public static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] md5(byte[] in) {
        try {
            return MessageDigest.getInstance("MD5").digest(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** all layouts worth trying for a blob of this size */
    public static List<Combo> combos(int len) {
        List<Combo> out = new ArrayList<Combo>();
        // CBC: [16 IV][ct]
        if (len > 32 && (len - 16) % 16 == 0) {
            out.add(new Combo("AES/CBC/PKCS5Padding", 0, 16, len - 16));
        }
        // CBC: [1][16 IV][ct]
        if (len > 48 && (len - 17) % 16 == 0) {
            out.add(new Combo("AES/CBC/PKCS5Padding", 1, 17, len - 17));
        }
        // CBC: [1][ct]
        if (len > 16 && (len - 1) % 16 == 0) {
            out.add(new Combo("AES/CBC/PKCS5Padding", -1, 1, len - 1));
        }
        // CTR: [16 IV][ct] - no block alignment
        if (len > 32) {
            out.add(new Combo("AES/CTR/NoPadding", 0, 16, len - 16));
            out.add(new Combo("AES/CTR/NoPadding", 1, 17, len - 17));
        }
        // GCM: [12 nonce][ct + 16 tag]
        if (len > 40) {
            out.add(new Combo("AES/GCM/NoPadding", 0, 12, len - 12));
            out.add(new Combo("AES/GCM/NoPadding", 1, 13, len - 13));
        }
        return out;
    }

    /**
     * Decrypt a blob with one layout.  Returns the plaintext or null.
     * The plaintext is returned raw (padding is not removed) - the caller
     * decides what a valid answer looks like.
     */
    public static byte[] decrypt(byte[] key, byte[] blob, Combo c) {
        try {
            Cipher cipher = Cipher.getInstance(c.mode);
            int ivLen = c.mode.startsWith("AES/GCM") ? 12 : 16;
            if (c.mode.startsWith("AES/ECB")) {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            } else if (c.mode.startsWith("AES/GCM")) {
                byte[] nonce = new byte[ivLen];
                System.arraycopy(blob, c.ivOffset, nonce, 0, ivLen);
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce));
            } else {
                byte[] iv = new byte[ivLen];
                System.arraycopy(blob, c.ivOffset, iv, 0, ivLen);
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new IvParameterSpec(iv));
            }
            return cipher.doFinal(blob, c.ctOffset, c.ctLength);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Encrypt a plaintext back into the same layout.
     *
     * The rewritten payload is usually LONGER than the original (50 becomes
     * 999999999), so the result is a new array - only the IV/nonce in front
     * is kept from the original blob.
     */
    public static byte[] encrypt(byte[] key, byte[] blob, Combo c, byte[] plain) {
        try {
            Cipher cipher = Cipher.getInstance(c.mode);
            int ivLen = c.mode.startsWith("AES/GCM") ? 12 : 16;
            if (c.mode.startsWith("AES/ECB")) {
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            } else if (c.mode.startsWith("AES/GCM")) {
                byte[] nonce = new byte[ivLen];
                System.arraycopy(blob, c.ivOffset, nonce, 0, ivLen);
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce));
            } else {
                byte[] iv = new byte[ivLen];
                System.arraycopy(blob, c.ivOffset, iv, 0, ivLen);
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new IvParameterSpec(iv));
            }
            byte[] ct = cipher.doFinal(plain);
            int head = Math.min(c.ctOffset, blob.length);
            byte[] out = new byte[head + ct.length];
            System.arraycopy(blob, 0, out, 0, head);
            System.arraycopy(ct, 0, out, head, ct.length);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** does this look like a decrypted JSON document? */
    public static boolean looksLikeJson(byte[] b) {
        if (b == null || b.length < 2) {
            return false;
        }
        int start = 0;
        while (start < b.length && (b[start] == ' ' || b[start] == '\n' || b[start] == '\r'
                || b[start] == '\t' || b[start] == 0)) {
            start++;
        }
        if (start >= b.length) {
            return false;
        }
        char c = (char) b[start];
        if (c != '{' && c != '[') {
            return false;
        }
        // the rest has to be mostly printable
        int printable = 0;
        int n = 0;
        for (int i = start; i < b.length; i++) {
            byte x = b[i];
            if (x == 9 || x == 10 || x == 13 || (x >= 32 && x < 127)) {
                printable++;
            }
            n++;
        }
        return n > 0 && (float) printable / n > 0.92;
    }

    /** find the layout in which this key really decrypts the blob */
    public static Combo findCombo(byte[] key, byte[] blob, List<Combo> combos) {
        for (Combo c : combos) {
            byte[] plain = decrypt(key, blob, c);
            if (looksLikeJson(plain)) {
                return c;
            }
        }
        return null;
    }

    /** the plaintext of a successful decryption, as text */
    public static String text(byte[] plain) {
        return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
    }
}
