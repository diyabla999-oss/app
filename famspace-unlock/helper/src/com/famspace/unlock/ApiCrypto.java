package com.famspace.unlock;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The encryption of the Famspace API.
 *
 * Everything the app and the server exchange as "business data" is a JSON
 * document that is encrypted with AES-128-CBC and sent base64 encoded:
 *
 *     responseData = base64( ciphertext || index byte )
 *
 * The index byte selects one of sixteen keys that the app keeps in a map
 * (the keys "a".."f" and "0".."9").  The IV of the cipher is the selected
 * key itself, the plaintext is the JSON padded with zero bytes up to the
 * next block boundary.
 *
 * The key table was read out of the Dart object pool of libapp.so
 * (pp+0x9230, Map&lt;String, String&gt;(16)).
 */
public final class ApiCrypto {

    /** the index characters of the key table, in the order of the map */
    public static final String[] INDEX = {
        "a", "b", "c", "d", "e", "f", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
    };

    /** the sixteen keys of the table */
    public static final String[] KEYS = {
        "x9kPzQjL7vR2wE4F",
        "hG6sN1mVbC8yU3tA",
        "pL5oI9uJkE0rTqWz",
        "fD2gH4jK6mN8pS0v",
        "cZ1xV3bN5mK7lJ9h",
        "yT4rE6wQ8aI2uO0p",
        "gH7jK9lM1nB3vC5x",
        "sD6fG8hJ0kL2mN4b",
        "wE3rT5yU7iO9pA1s",
        "qA2zS4xW6cE8dF0v",
        "uI5oP7lK9jH1gT3f",
        "bN8mV6cZ4xL2jK0h",
        "rT1yU3iO5pA7sD9f",
        "kL9jH7gV5bN3mC1x",
        "eW4rT6yU8iO0pA2s",
        "vC5xZ7aQ9wE1sD3f",
    };

    private ApiCrypto() {
    }

    /** the key for an index character, or null */
    public static String keyFor(char index) {
        for (int i = 0; i < INDEX.length; i++) {
            if (INDEX[i].charAt(0) == index) {
                return KEYS[i];
            }
        }
        return null;
    }

    /**
     * Decrypt an encrypted answer.
     *
     * @return the plaintext (the JSON, without the zero padding) or null
     */
    public static byte[] decrypt(byte[] blob) {
        if (blob == null || blob.length < 17) {
            return null;
        }
        int last = blob.length - 1;
        String key = keyFor((char) (blob[last] & 0xff));
        if (key == null) {
            return null;
        }
        byte[] ct = Arrays.copyOfRange(blob, 0, last);
        if (ct.length == 0 || ct.length % 16 != 0) {
            return null;
        }
        byte[] plain = aes(key.getBytes(StandardCharsets.US_ASCII),
                key.getBytes(StandardCharsets.US_ASCII), ct, Cipher.DECRYPT_MODE);
        if (plain == null) {
            return null;
        }
        // the plaintext is the JSON padded with zero bytes
        int end = plain.length;
        while (end > 0 && plain[end - 1] == 0) {
            end--;
        }
        return Arrays.copyOfRange(plain, 0, end);
    }

    /**
     * Encrypt a plaintext the way the app expects it: the index byte of the
     * original answer is kept, so the app picks the same key again.
     */
    public static byte[] encrypt(byte[] blob, byte[] plain) {
        if (blob == null || blob.length < 1) {
            return null;
        }
        int last = blob.length - 1;
        String keyName = keyFor((char) (blob[last] & 0xff));
        if (keyName == null) {
            return null;
        }
        byte[] keyBytes = keyName.getBytes(StandardCharsets.US_ASCII);
        // pad to the block size with zero bytes
        int len = plain.length;
        int padded = (len + 15) / 16 * 16;
        byte[] paddedPlain = new byte[padded];
        System.arraycopy(plain, 0, paddedPlain, 0, len);
        byte[] ct = aes(keyBytes, keyBytes, paddedPlain, Cipher.ENCRYPT_MODE);
        if (ct == null) {
            return null;
        }
        byte[] out = new byte[ct.length + 1];
        System.arraycopy(ct, 0, out, 0, ct.length);
        out[ct.length] = blob[last];
        return out;
    }

    private static byte[] aes(byte[] key, byte[] iv, byte[] data, int mode) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Exception e) {
            return null;
        }
    }

    /** the index byte of an answer, as a character (0 if there is none) */
    public static char indexOf(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return 0;
        }
        return (char) (blob[blob.length - 1] & 0xff);
    }
}
