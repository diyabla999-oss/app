package com.famspace.unlock;

/**
 * The little helpers the proxy needs: base64 (self contained, so it works on
 * every Android version), the digests used to look at the bearer token and
 * the helpers that describe it in the log.
 *
 * The API crypto itself lives in ApiCrypto.
 */
public final class Crypto {

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Crypto() {
    }

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
            return java.security.MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] md5(byte[] in) {
        try {
            return java.security.MessageDigest.getInstance("MD5").digest(in);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The values that the bearer token could be signed with.  The token is a
     * HS256 JWT; the proxy only looks at it, it never sends it anywhere.
     */
    public static java.util.List<byte[]> tokenCandidates(String authValue, String uuid) {
        java.util.List<byte[]> out = new java.util.ArrayList<byte[]>();
        if (authValue == null) {
            return out;
        }
        String token = authValue.trim();
        int sp = token.indexOf(' ');
        if (sp >= 0) {
            token = token.substring(sp + 1).trim();
        }
        add(out, token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        int dot = token.indexOf('.');
        if (dot > 0) {
            String head = token.substring(0, dot);
            add(out, head.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        if (uuid != null) {
            add(out, uuid.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            add(out, (uuid + token).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        byte[] sha = sha256(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        add(out, sha);
        byte[] md5 = md5(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        add(out, md5);
        return out;
    }

    private static void add(java.util.List<byte[]> out, byte[] b) {
        if (b == null || b.length == 0) {
            return;
        }
        for (byte[] e : out) {
            if (java.util.Arrays.equals(e, b)) {
                return;
            }
        }
        out.add(b);
    }

    /** a short description of the bearer token for the log */
    public static String describeToken(String authValue) {
        if (authValue == null) {
            return "none";
        }
        String t = authValue.trim();
        int sp = t.indexOf(' ');
        if (sp >= 0) {
            t = t.substring(sp + 1).trim();
        }
        int dot = t.indexOf('.');
        StringBuilder sb = new StringBuilder();
        sb.append(t.length()).append(" chars");
        if (dot > 0) {
            sb.append(", ").append(dot).append(" header chars");
            String head = t.substring(0, dot);
            if (head.startsWith("ey")) {
                sb.append(", JWT");
            }
        }
        return sb.toString();
    }
}
