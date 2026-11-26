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
    /** base64url (JWT) - different alphabet, may be unpadded */
    public static byte[] decodeBase64Url(String s) {
        if (s == null) {
            return null;
        }
        String t = s.replace('-', '+').replace('_', '/');
        while (t.length() % 4 != 0) {
            t = t + "=";
        }
        return decodeBase64(t);
    }

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
        // the IV/nonce derived from the key instead of taken from the blob
        if (len > 16) {
            out.add(new Combo("AES/CTR/NoPadding", -2, 0, len));
            out.add(new Combo("AES/CBC/NoPadding", -2, 0, len));
        }
        return out;
    }

    /** the IV for a layout: -1 = none, -2 = derived from the key */
    private static byte[] ivOf(byte[] key, byte[] blob, Combo c) {
        if (c.ivOffset == -1) {
            return null;
        }
        if (c.ivOffset == -2) {
            byte[] iv = new byte[16];
            System.arraycopy(key, 0, iv, 0, Math.min(16, key.length));
            return iv;
        }
        int ivLen = c.mode.startsWith("AES/GCM") ? 12 : 16;
        byte[] iv = new byte[ivLen];
        System.arraycopy(blob, c.ivOffset, iv, 0, ivLen);
        return iv;
    }

    /**
     * Decrypt a blob with one layout.  Returns the plaintext or null.
     * The plaintext is returned raw (padding is not removed) - the caller
     * decides what a valid answer looks like.
     */
    public static byte[] decrypt(byte[] key, byte[] blob, Combo c) {
        try {
            Cipher cipher = Cipher.getInstance(c.mode);
            byte[] iv = ivOf(key, blob, c);
            if (iv == null) {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            } else if (c.mode.startsWith("AES/GCM")) {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, iv));
            } else {
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
            byte[] iv = ivOf(key, blob, c);
            if (iv == null) {
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            } else if (c.mode.startsWith("AES/GCM")) {
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, iv));
            } else {
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new IvParameterSpec(iv));
            }
            byte[] ct = cipher.doFinal(plain);
            int head = c.ctOffset > 0 ? Math.min(c.ctOffset, blob.length) : 0;
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

    /**
     * Same, but only the first blocks are decrypted - that is what the memory
     * scan uses, because it tries millions of candidates.
     *
     * With CBC the first block is wrong when the IV is wrong, so the second
     * one is checked as well.
     */
    public static Combo findComboFast(byte[] key, byte[] blob, List<Combo> combos) {
        for (Combo c : combos) {
            byte[] plain = decryptPart(key, blob, c, 32);
            if (plain == null) {
                continue;
            }
            if (plain.length >= 16 && jsonStart(plain, 0)) {
                return c;
            }
            if (plain.length >= 32 && jsonStart(plain, 16)) {
                return c;
            }
        }
        return null;
    }

    /** decrypts only the first bytes of the ciphertext */
    public static byte[] decryptPart(byte[] key, byte[] blob, Combo c, int bytes) {
        try {
            Cipher cipher = Cipher.getInstance(c.mode);
            byte[] iv = ivOf(key, blob, c);
            if (iv == null) {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            } else if (c.mode.startsWith("AES/GCM")) {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, iv));
            } else {
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new IvParameterSpec(iv));
            }
            int len = Math.min(bytes, c.ctLength);
            if (len <= 0) {
                return null;
            }
            return cipher.doFinal(blob, c.ctOffset, len);
        } catch (Exception e) {
            return null;
        }
    }

    /** does this 16 byte window look like the start of a JSON document? */
    private static boolean jsonStart(byte[] b, int off) {
        int p = 0;
        for (int i = off; i < off + 16 && i < b.length; i++) {
            byte x = b[i];
            if (x >= 32 && x < 127) {
                p++;
            }
        }
        if (p < 15) {
            return false;
        }
        for (int i = off; i < off + 16 && i < b.length; i++) {
            byte x = b[i];
            if (x == '{' || x == '"' || x == '[') {
                return true;
            }
        }
        return false;
    }

    /**
     * Cracks the HS256 secret of a JWT with a dictionary.
     *
     * The token is signed by the server, but the app has to VERIFY it - so it
     * carries the same secret.  A JWT gives a known-plaintext oracle:
     * HMAC-SHA256(secret, "header.payload") == signature, which is exactly
     * what the dictionary is searched for.
     *
     * @param token       the raw JWT
     * @param dictionary  newline separated candidates (the app's own strings)
     * @param substringLen 0 = whole lines only, otherwise also try every
     *                    substring of that length (the heap packs strings
     *                    back to back, so the real secret can be a slice of a
     *                    longer run)
     * @return the secret bytes or null
     */
    public static byte[] crackJwtSecret(String token, String dictionary, int substringLen) {
        if (token == null || dictionary == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length < 3) {
            return null;
        }
        String message = parts[0] + "." + parts[1];
        byte[] sig = decodeBase64Url(parts[2]);
        if (sig == null || sig.length == 0) {
            return null;
        }
        byte[] msg = message.getBytes(StandardCharsets.US_ASCII);
        String[] lines = dictionary.split("\n");
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            byte[] cand = line.getBytes(StandardCharsets.US_ASCII);
            if (matches(cand, msg, sig)) {
                return cand;
            }
            byte[] dec = decodeBase64(line);
            if (dec != null && dec.length > 0 && matches(dec, msg, sig)) {
                return dec;
            }
        }
        if (substringLen > 0) {
            for (String line : lines) {
                if (line.length() < substringLen) {
                    continue;
                }
                for (int off = 0; off + substringLen <= line.length(); off++) {
                    byte[] cand = line.substring(off, off + substringLen)
                            .getBytes(StandardCharsets.US_ASCII);
                    if (matches(cand, msg, sig)) {
                        return cand;
                    }
                }
            }
        }
        return null;
    }

    private static boolean matches(byte[] key, byte[] msg, byte[] sig) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return java.util.Arrays.equals(mac.doFinal(msg), sig);
        } catch (Exception e) {
            return false;
        }
    }

    /** key candidates derived from a secret we cracked */
    public static List<byte[]> secretCandidates(byte[] secret) {
        List<byte[]> out = new ArrayList<byte[]>();
        add(out, secret);
        add(out, sha256(secret));
        add(out, md5(secret));
        byte[] d = decodeBase64Url(new String(secret, StandardCharsets.US_ASCII));
        add(out, d);
        if (d != null) {
            add(out, sha256(d));
            add(out, md5(d));
        }
        add(out, sha256(new String(secret, StandardCharsets.US_ASCII).getBytes(StandardCharsets.US_ASCII)));
        add(out, md5(new String(secret, StandardCharsets.US_ASCII).getBytes(StandardCharsets.US_ASCII)));
        return out;
    }

    /** the plaintext of a successful decryption, as text */
    public static String text(byte[] plain) {
        return plain == null ? null : new String(plain, StandardCharsets.UTF_8);
    }

    /**
     * Key candidates derived from the session token.
     *
     * The cipher is deterministic (the same answer gives the same bytes) but
     * the keystream changes with the session, so the key is derived from the
     * bearer token the app sends in the Authorization header - the server
     * knows it as well.
     */
    public static List<byte[]> tokenCandidates(String authValue, String uuid) {
        List<byte[]> out = new ArrayList<byte[]>();
        if (authValue == null) {
            return out;
        }
        String token = authValue.trim();
        int sp = token.indexOf(' ');
        if (sp >= 0) {
            token = token.substring(sp + 1).trim();
        }
        byte[] raw = token.getBytes(StandardCharsets.UTF_8);

        add(out, sha256(raw));
        add(out, md5(raw));
        add(out, sha256(token.getBytes(StandardCharsets.US_ASCII)));
        add(out, md5(token.getBytes(StandardCharsets.US_ASCII)));

        // the token itself may be base64 / hex encoded key material
        byte[] dec = decodeBase64(token);
        if (dec != null) {
            add(out, sha256(dec));
            add(out, md5(dec));
            if (dec.length >= 16) {
                add(out, java.util.Arrays.copyOf(dec, 16));
            }
            if (dec.length >= 32) {
                add(out, java.util.Arrays.copyOf(dec, 32));
            }
        }
        // a JWT: header.payload.signature - every part is a candidate
        String[] parts = token.split("\\.");
        for (String p : parts) {
            byte[] pb = decodeBase64(p);
            if (pb != null) {
                add(out, sha256(pb));
                add(out, md5(pb));
                if (pb.length >= 16) {
                    add(out, java.util.Arrays.copyOf(pb, 16));
                }
                if (pb.length >= 32) {
                    add(out, java.util.Arrays.copyOf(pb, 32));
                }
            }
            byte[] pb2 = p.getBytes(StandardCharsets.US_ASCII);
            add(out, md5(pb2));
            add(out, sha256(pb2));
            // hex encoded
            if (p.length() == 32 || p.length() == 64) {
                try {
                    byte[] h = new byte[p.length() / 2];
                    for (int i = 0; i < h.length; i++) {
                        h[i] = (byte) Integer.parseInt(p.substring(2 * i, 2 * i + 2), 16);
                    }
                    add(out, h);
                } catch (Exception ignored) {
                }
            }
        }
        // combined with the per request uuid
        if (uuid != null) {
            byte[] u = uuid.getBytes(StandardCharsets.US_ASCII);
            add(out, md5(concat(raw, u)));
            add(out, sha256(concat(raw, u)));
            add(out, md5(concat(u, raw)));
            add(out, sha256(concat(u, raw)));
        }
        // slices of the token itself
        byte[] ascii = token.getBytes(StandardCharsets.US_ASCII);
        for (int len : new int[]{16, 24, 32}) {
            if (ascii.length > len) {
                add(out, java.util.Arrays.copyOf(ascii, len));
                add(out, java.util.Arrays.copyOfRange(ascii, ascii.length - len, ascii.length));
            }
        }
        return out;
    }

    /** describes the shape of a bearer token without revealing it */
    public static String describeToken(String authValue) {
        if (authValue == null) {
            return "none";
        }
        String token = authValue.trim();
        int sp = token.indexOf(' ');
        if (sp >= 0) {
            token = token.substring(sp + 1).trim();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(token.length()).append(" chars");
        String[] parts = token.split("\\.");
        sb.append(", ").append(parts.length).append(" dot separated part(s)");
        for (int i = 0; i < parts.length; i++) {
            byte[] dec = decodeBase64(parts[i]);
            sb.append(", part").append(i).append("=").append(parts[i].length()).append(" chars");
            if (dec != null) {
                sb.append(" -> ").append(dec.length).append(" bytes");
                // the JWT header is not secret
                if (i == 0 && dec.length < 400) {
                    String h = new String(dec, StandardCharsets.UTF_8);
                    if (h.trim().startsWith("{")) {
                        sb.append(" ").append(h);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static void add(List<byte[]> out, byte[] b) {
        if (b == null) {
            return;
        }
        if (b.length != 16 && b.length != 24 && b.length != 32) {
            return;
        }
        for (byte[] e : out) {
            if (java.util.Arrays.equals(e, b)) {
                return;
            }
        }
        out.add(b);
    }
}
