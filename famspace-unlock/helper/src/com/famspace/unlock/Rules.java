package com.famspace.unlock;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The rewrite rules.
 *
 * The goal: make the Famspace client behave as if the server had answered
 * "you own everything" - unlimited wallet, active VIP, every item unlocked
 * and viewable.
 *
 * Every rewrite is TYPE PRESERVING: a value is only replaced when the
 * replacement has the same JSON type as the original (number -> number,
 * boolean -> boolean, string -> string).  Swapping a string for a number (or
 * an int for a bool) would break the Dart model of the app - which shows up
 * as "the login does not work" rather than as a crash.
 */
public final class Rules {

    public static final long BIG = 999999999L;

    private static final java.util.Map<String, Object> FORCE = new java.util.HashMap<String, Object>();
    private static final java.util.Map<String, Object> ZERO_COST = new java.util.HashMap<String, Object>();
    private static final java.util.Map<String, Object> FUTURE = new java.util.HashMap<String, Object>();
    private static final java.util.Map<String, Object> VINT = new java.util.HashMap<String, Object>();

    static {
        // wallet
        FORCE.put("gems", BIG);
        FORCE.put("coins", BIG);
        FORCE.put("balance", BIG);
        FORCE.put("addedGems", BIG);
        FORCE.put("coinCount", BIG);
        FORCE.put("coinBalance", BIG);
        FORCE.put("gemBalance", BIG);
        FORCE.put("userBalance", BIG);
        FORCE.put("rewardCoins", BIG);
        FORCE.put("favoriteCoins", BIG);
        FORCE.put("baseCoins", BIG);
        // wallet - singular / variant spellings the server may use
        FORCE.put("coin", BIG);
        FORCE.put("gem", BIG);
        FORCE.put("coinAmount", BIG);
        FORCE.put("gemAmount", BIG);
        FORCE.put("coinTotal", BIG);
        FORCE.put("gemTotal", BIG);
        FORCE.put("coinsTotal", BIG);
        FORCE.put("gemsTotal", BIG);
        FORCE.put("coinsBalance", BIG);
        FORCE.put("gemsBalance", BIG);
        FORCE.put("availableCoins", BIG);
        FORCE.put("availableGems", BIG);
        FORCE.put("usableCoins", BIG);
        FORCE.put("usableGems", BIG);
        FORCE.put("totalCoins", BIG);
        FORCE.put("totalGems", BIG);
        FORCE.put("myCoins", BIG);
        FORCE.put("myGems", BIG);
        FORCE.put("userCoins", BIG);
        FORCE.put("userGems", BIG);
        FORCE.put("coinNum", BIG);
        FORCE.put("gemNum", BIG);
        FORCE.put("numCoins", BIG);
        FORCE.put("numGems", BIG);
        FORCE.put("diamonds", BIG);
        FORCE.put("credit", BIG);
        FORCE.put("credits", BIG);
        FORCE.put("points", BIG);
        FORCE.put("point", BIG);
        // subscription / premium
        FORCE.put("vipLv", 99);
        FORCE.put("vipLevel", 99);
        FORCE.put("premiumPurchased", Boolean.TRUE);
        FORCE.put("isVip", Boolean.TRUE);
        FORCE.put("isVIP", Boolean.TRUE);
        FORCE.put("vip", Boolean.TRUE);
        FORCE.put("premium", Boolean.TRUE);
        FORCE.put("isPremium", Boolean.TRUE);
        FORCE.put("isPro", Boolean.TRUE);
        FORCE.put("pro", Boolean.TRUE);
        FORCE.put("subscriptionActive", Boolean.TRUE);
        FORCE.put("isSubscribed", Boolean.TRUE);
        FORCE.put("subscribed", Boolean.TRUE);
        FORCE.put("adFree", Boolean.TRUE);
        FORCE.put("noAd", Boolean.TRUE);
        FORCE.put("isAdFree", Boolean.TRUE);
        FORCE.put("unlimitedDownloads", Boolean.TRUE);
        FORCE.put("incognitoEnabled", Boolean.TRUE);
        FORCE.put("incognito", Boolean.TRUE);
        // access flags
        FORCE.put("canView", Boolean.TRUE);
        FORCE.put("canDownload", Boolean.TRUE);
        FORCE.put("canPlay", Boolean.TRUE);
        FORCE.put("canShare", Boolean.TRUE);
        FORCE.put("isFavorite", Boolean.TRUE);
        FORCE.put("alreadyUnlocked", Boolean.TRUE);
        FORCE.put("unlocked", Boolean.TRUE);
        FORCE.put("isUnlocked", Boolean.TRUE);
        FORCE.put("unlockType", "");
        FORCE.put("unlockStatus", "unlocked");
        FORCE.put("locked", Boolean.FALSE);
        FORCE.put("isLocked", Boolean.FALSE);
        FORCE.put("needUnlock", Boolean.FALSE);
        FORCE.put("needCoins", Boolean.FALSE);
        FORCE.put("restricted", Boolean.FALSE);
        FORCE.put("viewingRestricted", Boolean.FALSE);
        FORCE.put("downloadRestricted", Boolean.FALSE);
        FORCE.put("memberOnly", Boolean.FALSE);
        FORCE.put("membersOnly", Boolean.FALSE);
        // limits
        FORCE.put("expireDay", 36500);
        FORCE.put("expiresIn", 36500);
        FORCE.put("dailyLimit", 100000);
        FORCE.put("adWatchLimit", 100000);
        FORCE.put("unlockLimit", 100000);
        FORCE.put("freeUnlockLimit", 100000);
        FORCE.put("todayLimit", 100000);
        FORCE.put("maxCount", 100000);

        // unlock costs become free (IAP product prices are NOT touched)
        ZERO_COST.put("cost", 0);
        ZERO_COST.put("coinCost", 0);
        ZERO_COST.put("gemCost", 0);
        ZERO_COST.put("unlockCost", 0);
        ZERO_COST.put("videoCost", 0);
        ZERO_COST.put("needCoin", 0);
        ZERO_COST.put("needGem", 0);
        ZERO_COST.put("needCoins", 0);
        ZERO_COST.put("coinPrice", 0);
        ZERO_COST.put("gemPrice", 0);
        ZERO_COST.put("coinsRequired", 0);
        ZERO_COST.put("gemsRequired", 0);
        ZERO_COST.put("coinsNeeded", 0);
        ZERO_COST.put("gemsNeeded", 0);
        ZERO_COST.put("coinPerAd", 0);
        ZERO_COST.put("coinsPerAd", 0);
        ZERO_COST.put("unlockPrice", 0);

        FUTURE.put("vipEnd", "2099-12-31T23:59:59Z");
        FUTURE.put("vipExpire", "2099-12-31T23:59:59Z");
        FUTURE.put("expireTime", "2099-12-31T23:59:59Z");
        FUTURE.put("expiredTime", "2099-12-31T23:59:59Z");
        FUTURE.put("expiresAt", "2099-12-31T23:59:59Z");
        FUTURE.put("endTime", "2099-12-31T23:59:59Z");

        // The profile model reads "vipEnd" as an INT and formats it as a date,
        // so a date string would not parse.  2038-01-19 (2147483647) is the
        // largest value that still fits into a signed 32 bit integer.
        VINT.put("vipEnd", 2147483647L);
        VINT.put("vipExpire", 2147483647L);
        VINT.put("expireTime", 2147483647L);
        VINT.put("expiresAt", 2147483647L);
        VINT.put("endTime", 2147483647L);
        VINT.put("expiredTime", 2147483647L);
    }

    private Rules() {
    }

    // ------------------------------------------------------------ the tree

    /**
     * Rewrites a decoded JSON tree in place; returns the keys that changed.
     * The order of the keys and the text of the untouched values are kept.
     */
    public static List<String> rewrite(Json node, String publicUrl) {
        List<String> changed = new ArrayList<String>();
        rewriteNode(node, publicUrl, changed);
        return changed;
    }

    private static void rewriteNode(Json node, String publicUrl, List<String> changed) {
        Object v = node.value();
        if (v instanceof Map) {
            for (Map.Entry<String, Json> e : node.members().entrySet()) {
                String key = e.getKey();
                Json child = e.getValue();
                if (child.isObject() || child.isArray()) {
                    rewriteNode(child, publicUrl, changed);
                    continue;
                }
                Object forced = forcedValue(key, child.value(), publicUrl);
                if (forced == null) {
                    continue;
                }
                if (forced.equals(child.value())) {
                    continue;
                }
                String raw = render(forced, child);
                if (raw == null) {
                    continue;
                }
                child.set(raw, forced);
                changed.add(key);
            }
            return;
        }
        if (v instanceof List) {
            for (Json child : node.items()) {
                rewriteNode(child, publicUrl, changed);
            }
        }
    }

    /**
     * Renders the replacement value so that it keeps the JSON type of the
     * original: an integer stays an integer, a decimal stays a decimal.
     */
    private static String render(Object forced, Json original) {
        Object old = original.value();
        if (old instanceof Number) {
            if (!(forced instanceof Number)) {
                return null;
            }
            Number n = (Number) forced;
            boolean wasDecimal = old instanceof Double
                    || original.raw().indexOf('.') >= 0
                    || original.raw().indexOf('e') >= 0
                    || original.raw().indexOf('E') >= 0;
            if (wasDecimal) {
                return n.toString();
            }
            return Long.toString(n.longValue());
        }
        if (old instanceof Boolean) {
            if (!(forced instanceof Boolean)) {
                return null;
            }
            return ((Boolean) forced).booleanValue() ? "true" : "false";
        }
        if (old instanceof String) {
            if (!(forced instanceof String)) {
                return null;
            }
            return Json.quote((String) forced);
        }
        return null;
    }

    /**
     * Returns the replacement for a key, or null when the value must stay as
     * it is.  The replacement always keeps the JSON type of the original.
     */
    private static Object forcedValue(String key, Object value, String publicUrl) {
        if ("baseUrl".equals(key) && publicUrl != null && value instanceof String) {
            return publicUrl;
        }
        Object forced = FORCE.get(key);
        if (forced == null) {
            forced = ZERO_COST.get(key);
        }
        if (forced == null) {
            forced = VINT.get(key);
        }
        if (forced == null) {
            forced = FUTURE.get(key);
        }
        if (forced == null) {
            return null;
        }
        return sameType(forced, value);
    }

    /** Converts the forced value to the JSON type of the original value. */
    private static Object sameType(Object forced, Object original) {
        if (original == null) {
            return null;                       // leave nulls alone
        }
        if (original instanceof Boolean) {
            return forced instanceof Boolean ? forced : null;
        }
        if (original instanceof Number) {
            if (forced instanceof Number) {
                return forced;
            }
            if (forced instanceof Boolean) {
                return ((Boolean) forced).booleanValue() ? 1L : 0L;
            }
            return null;
        }
        if (original instanceof String) {
            if (forced instanceof String) {
                return forced;
            }
            if (forced instanceof Boolean) {
                return String.valueOf(forced);
            }
            if (forced instanceof Number) {
                return String.valueOf(forced);
            }
        }
        return null;
    }
}
