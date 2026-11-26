package com.famspace.unlock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the app's own cache.
 *
 * The proxy runs inside the app process, so it can read the app's data
 * directory.  The app caches what it fetched from the API (MMKV, shared
 * preferences, plain files) - and what it caches is the *decrypted* answer,
 * because the Dart side decrypts it before storing it.  That plaintext is
 * what the UI shows when the API is not reachable.
 *
 * So instead of fighting the encryption, the cached copy is patched directly:
 * the wallet becomes unlimited and the premium flags are set.
 *
 * The scan also shows the real field names of the profile, which is what the
 * rewrite rules need.
 */
public final class LocalCache {

    /** set by the Android side: the app's data directory */
    public static volatile String dataDir;

    private static final int MAX_FILE = 32 * 1024 * 1024;

    /** a file that was patched */
    public static final class Hit {
        public final String file;
        public final List<String> keys;

        Hit(String file, List<String> keys) {
            this.file = file;
            this.keys = keys;
        }
    }

    /** directories that can hold cached answers */
    private static List<File> roots() {
        List<File> out = new ArrayList<File>();
        String base = dataDir;
        if (base == null) {
            return out;
        }
        String[] sub = {"shared_prefs", "files", "databases", "no_backup",
                "app_flutter", "cache", "mmkv", "datastore"};
        for (String s : sub) {
            File f = new File(base, s);
            if (f.isDirectory()) {
                out.add(f);
            }
        }
        return out;
    }

    /** every file below the roots (not too deep, not too big) */
    private static List<File> files() {
        List<File> out = new ArrayList<File>();
        for (File root : roots()) {
            collect(root, out, 0);
        }
        return out;
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (depth > 4) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
            } else if (f.isFile() && f.length() > 0 && f.length() < MAX_FILE) {
                out.add(f);
            }
        }
    }

    /**
     * Patches every cached answer that holds a wallet or a premium flag.
     *
     * @return the files that were changed
     */
    public static List<Hit> patch() {
        List<Hit> hits = new ArrayList<Hit>();
        for (File f : files()) {
            try {
                byte[] raw = readAll(f);
                if (raw == null) {
                    continue;
                }
                String text = new String(raw, StandardCharsets.UTF_8);
                if (text.indexOf('"') < 0 || text.length() < 20) {
                    continue;
                }
                if (!text.contains("coins") && !text.contains("gems")
                        && !text.contains("vipLv") && !text.contains("canView")
                        && !text.contains("premium")) {
                    continue;
                }
                List<String> changed = new ArrayList<String>();
                String out = rewriteNumbers(text, changed);
                if (!changed.isEmpty()) {
                    writeAll(f, out.getBytes(StandardCharsets.UTF_8));
                    hits.add(new Hit(f.getPath(), changed));
                }
            } catch (Throwable ignored) {
            }
        }
        return hits;
    }

    /** the wallet, the premium flags and the locks - as plain numbers */
    private static final Pattern[] NUMBERS = {
            Pattern.compile("(?i)(\"(?:coins|gems|coin|gem|balance|coinBalance|gemBalance|"
                    + "availableCoins|totalCoins|myCoins|userCoins|diamonds|credits|points)\"\\s*:\\s*)-?\\d+"),
            Pattern.compile("(?i)(\"(?:vipLv|vipLevel)\"\\s*:\\s*)-?\\d+"),
    };

    private static final Pattern[] BOOLEANS = {
            Pattern.compile("(?i)(\"(?:canView|canDownload|canPlay|canShare|premiumPurchased|isVip|"
                    + "isVIP|vip|premium|isPremium|isPro|pro|subscriptionActive|isSubscribed|"
                    + "subscribed|adFree|noAd|isAdFree|unlimitedDownloads|incognitoEnabled|"
                    + "incognito|isFavorite|alreadyUnlocked|unlocked|isUnlocked)\"\\s*:\\s*)false"),
    };

    private static final Pattern[] FALSE_FLAGS = {
            Pattern.compile("(?i)(\"(?:locked|isLocked|needUnlock|needCoins|restricted|"
                    + "viewingRestricted|downloadRestricted|memberOnly|membersOnly)\"\\s*:\\s*)true"),
    };

    private static final Pattern[] COSTS = {
            Pattern.compile("(?i)(\"(?:cost|coinCost|gemCost|unlockCost|videoCost|needCoin|"
                    + "needGem|needCoins|coinPrice|gemPrice|coinsRequired|gemsRequired|"
                    + "coinsNeeded|gemsNeeded|unlockPrice)\"\\s*:\\s*)-?\\d+"),
    };

    private static String rewriteNumbers(String text, List<String> changed) {
        String out = text;
        for (Pattern p : NUMBERS) {
            Matcher m = p.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String key = m.group(1);
                boolean vip = key.toLowerCase(java.util.Locale.US).contains("vipl")
                        || key.toLowerCase(java.util.Locale.US).contains("viplevel");
                String value = vip ? "99" : "999999999";
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + value));
                changed.add(key.replaceAll("[:\\s\"]", "").toLowerCase(java.util.Locale.US));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        for (Pattern p : BOOLEANS) {
            Matcher m = p.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "true"));
                changed.add(m.group(1).replaceAll("[:\\s\"]", "").toLowerCase(java.util.Locale.US));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        for (Pattern p : FALSE_FLAGS) {
            Matcher m = p.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "false"));
                changed.add(m.group(1).replaceAll("[:\\s\"]", "").toLowerCase(java.util.Locale.US));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        for (Pattern p : COSTS) {
            Matcher m = p.matcher(out);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "0"));
                changed.add(m.group(1).replaceAll("[:\\s\"]", "").toLowerCase(java.util.Locale.US));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    private static byte[] readAll(File f) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                byte[] b = new byte[(int) Math.min(raf.length(), MAX_FILE)];
                raf.readFully(b);
                return b;
            } finally {
                raf.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeAll(File f, byte[] data) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(data);
            out.flush();
            out.close();
        } catch (Exception ignored) {
        }
    }
}
