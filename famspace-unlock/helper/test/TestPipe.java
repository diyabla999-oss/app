import com.famspace.unlock.ApiCrypto;
import com.famspace.unlock.Json;
import com.famspace.unlock.Rules;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The round trip of the API crypto, checked against the answers that were
 * captured from the real server:
 *
 *   encrypted answer -> decrypt -> rewrite -> encrypt -> decrypt
 *
 * The result has to be the same document as the original, apart from the
 * rewritten values, and the profile has to carry the unlimited wallet.
 *
 * The captured answers are the encrypted payloads of
 *   GET /api/v1/me/profile        (two sessions)
 *   GET /api/v1/config/app        (twice)
 *   GET /api/v1/push/report-token
 *   GET /api/v1/ai/task/list
 *   GET /api/v1/recent/space/list
 *   GET /api/v1/push/badge/clear
 */
public final class TestPipe {

    /** the profile of the first session */
    static final String PROFILE =
        "aOihRcdAtL8w4bieSA6PiB7ChfnwPESVWzXiXCdBajmouxuza6Ah/GfgFTqfglJ+qvTxPf2jQ0sW9I5R"
        + "/mWLTFjg9EslSNSsuwN8CmeOgVeFA1PswZ40DRV3oOEtgW51giD8vzwCsQVDxPEthiXWzxhbycAtYWfx"
        + "LFyqmtONtu7YI4obdGuhZQd2DzVzuGP1H+Ejt4BILOjnkmz0NvrHknbKiimiRRhjWnO7FZCdTTyS/iRDs"
        + "DRrlzwACo8ZuLbsKK4kMp1+SHpcYIMmIikP9ECSplZe9uAMAKrLp+kbWDKH3BbQGzR4GhGr8Z7aobhFkS"
        + "KWRiZsVEAGa8Y8tqHNHCO25f8fnelA1MgkAOh6NQox";

    /** the profile of the second session (same account, different session) */
    static final String PROFILE_S2 =
        "Uk7Uw+7xuMaIoslhT0jUczEImqdZITdxZnxhIg1ipEPT38vdxfrtZgQA91hAvFz3UkYROfjvJ4kYFyv55B2"
        + "MjVu+WVhQkKcdWFS49KhpVT+eaLGxWKZQ60rsTKGMkGG3KZEJ+/ciQQRnj8sFvHrWerx+9aY+Skfjxy/5f"
        + "fDreS9w6SEJCni+FusXVJk2TdSAZMEpGz+OL8avBohaGy7kbhMrrdZSTQTQh3i3+WZ6AtL7QCjkW+ActF"
        + "Dw4nC31gSlsGkLv0SapgMWHX0x6TanZaQ6ja/8dngspxas7W2p5xMI2HT3Mp19MAucH/FtVJSoE5HWkZD"
        + "ESGH97foop2dgLTdwqeBlgGv2KYuzWGOBa100";

    static final String[][] SAMPLES = {
        {"profile, session 1", PROFILE},
        {"profile, session 2", PROFILE_S2},
        {"config/app", "CoBY4KOcbPZZag6xMwRDm2U="},
        {"config/app, later", "fE1JwnZC1xZpbXKiB/xQVGI="},
        {"push/report-token", "Mn2ZB2BhpOkf0LrCnGrqimE="},
        {"ai/task/list", "YsiunqhRo1e6QiI+TLNmdUKAQPUCWuE93gdEtT5g0mJl"},
        {"recent/space/list", "n+oLqbDRKVdJq4nOGpBQffaYrPfSx/siEkqPj1q0/f/9CrMGgT+e01y6UjNrGgEuMQ=="},
        {"push/badge/clear", "j7LiB8UXCpOnsG2h2kB/IWY="},
    };

    private static int failures;

    private static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    public static void main(String[] args) {
        for (String[] row : SAMPLES) {
            byte[] blob = Base64.getDecoder().decode(row[1]);
            byte[] plain = ApiCrypto.decrypt(blob);
            check(plain != null, row[0] + ": decrypts");
            if (plain == null) {
                continue;
            }
            String original = new String(plain, StandardCharsets.UTF_8);

            // the tree has to reproduce the original byte for byte
            Json tree = Json.parse(original.trim());
            check(tree.write().equals(original.trim()),
                    row[0] + ": the tree keeps the document unchanged");

            // rewrite
            List<String> changed = Rules.rewrite(tree, "http://127.0.0.1:8888");
            String rewritten = tree.write();

            // encrypt again and decrypt again - the app must see the rewrite
            byte[] enc = ApiCrypto.encrypt(blob, rewritten.getBytes(StandardCharsets.UTF_8));
            check(enc != null, row[0] + ": encrypts");
            if (enc == null) {
                continue;
            }
            byte[] back = ApiCrypto.decrypt(enc);
            check(back != null, row[0] + ": decrypts again");
            if (back == null) {
                continue;
            }
            check(new String(back, StandardCharsets.UTF_8).equals(rewritten),
                    row[0] + ": the app sees the rewritten answer");
            // the framing: the ciphertext is a multiple of the block size and
            // the index byte follows it.  The rewritten JSON is longer than the
            // original, so the ciphertext grows - but the framing holds, and
            // the app strips the zero padding (the captured answers carry
            // between 1 and 14 zero bytes, so it has to).
            check((enc.length - 1) % 16 == 0,
                    row[0] + ": the answer keeps the framing");
            check(enc[enc.length - 1] == blob[blob.length - 1],
                    row[0] + ": the index byte is kept");

            System.out.println("ok  " + row[0] + ": " + changed);
        }

        // the wallet of the profile
        Json p = Json.parse(new String(
                ApiCrypto.decrypt(Base64.getDecoder().decode(PROFILE)), StandardCharsets.UTF_8));
        Rules.rewrite(p, "http://127.0.0.1:8888");
        String out = p.write();
        System.out.println("\nprofile -> " + out);
        check(out.contains("\"coins\":999999999"), "coins = 999999999");
        check(out.contains("\"gems\":999999999"), "gems = 999999999");
        check(out.contains("\"vipLv\":99"), "vipLv = 99");
        check(out.contains("\"vipEnd\":2147483647"), "vipEnd is a far future int");
        check(out.contains("\"uid\":5342202629"), "uid is untouched");
        check(out.contains("\"email\":\"j1xxyboo@gmail.com\""), "email is untouched");
        check(out.contains("\"appAccountToken\""), "appAccountToken is untouched");
        check(out.contains("\"hasCircle\":false"), "a boolean is untouched");

        System.out.println(failures == 0 ? "\nPASS - all checks passed"
                : "\n" + failures + " FAILURES");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
