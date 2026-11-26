package com.famspace.unlock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The rewrite rules, ported 1:1 from the desktop proxy (proxy.py).
 *
 * The goal: make the Famspace client behave as if the server had answered
 * "you own everything" - unlimited wallet, active VIP, every item unlocked
 * and viewable.
 *
 * Every rewrite is TYPE PRESERVING: a value is only replaced when the
 * replacement has the same JSON type as the original (number -> number,
 * boolean -> boolean, string -> string).  Swapping a string for a number (or
 * an int for a bool) would break the Dart model's parser - which shows up as
 * "the login does not work" rather than as a crash.
 */
public final class Rules {

    public static final long BIG = 999999999L;

    private static final Map<String, Object> FORCE = new HashMap<>();
    private static final Map<String, Object> ZERO_COST = new HashMap<>();
    private static final Map<String, Object> FUTURE = new HashMap<>();

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

        FUTURE.put("vipEnd", "2099-12-31T23:59:59Z");
        FUTURE.put("vipExpire", "2099-12-31T23:59:59Z");
        FUTURE.put("expireTime", "2099-12-31T23:59:59Z");
        FUTURE.put("expiredTime", "2099-12-31T23:59:59Z");
        FUTURE.put("expiresAt", "2099-12-31T23:59:59Z");
        FUTURE.put("endTime", "2099-12-31T23:59:59Z");
    }

    private Rules() {
    }

    /** Rewrites a decoded JSON tree in place; returns the keys that changed. */
    public static java.util.List<String> rewrite(Object node, String publicUrl) {
        java.util.List<String> changed = new java.util.ArrayList<String>();
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = obj.opt(key);
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    changed.addAll(rewrite(value, publicUrl));
                    continue;
                }
                Object forced = forcedValue(key, value, publicUrl);
                if (forced != null && !forced.equals(value)) {
                    try {
                        obj.put(key, forced);
                        changed.add(key);
                    } catch (Exception ignored) {
                    }
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object value = arr.opt(i);
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    changed.addAll(rewrite(value, publicUrl));
                }
            }
        }
        return changed;
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
                return forced.equals(Boolean.TRUE) ? 1 : 0;
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
