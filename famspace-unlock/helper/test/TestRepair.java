package com.famspace.unlock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class TestRepair {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static byte[] corruptSignature() {
        byte[] prefix = "MMKV\u0000auth_access_token".getBytes(StandardCharsets.UTF_8);
        byte[] damaged = "ccess_token\u0001\ufffd\u0001eyJhbGciOiJ.fake.jwt".getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[prefix.length + damaged.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(damaged, 0, out, prefix.length, damaged.length);
        return out;
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("famunlock-repair-test").toFile();
        File mmkv = new File(root, "mmkv");
        check(mmkv.mkdirs(), "make mmkv directory");
        File store = new File(mmkv, "default.mmkv");
        Files.write(store.toPath(), corruptSignature());
        File unrelated = new File(mmkv, "unrelated");
        Files.write(unrelated.toPath(), "ordinary settings".getBytes(StandardCharsets.UTF_8));

        Repair.healDataDir(root);
        check(!mmkv.exists(), "corrupted MMKV store should be removed");
        check("cleared MMKV - log in again".equals(Repair.state), "repair state: " + Repair.state);
        File marker = new File(new File(root, "files"), "famunlock.repaired");
        check(marker.isFile(), "marker should be written");

        // A second run is idempotent and must not clear anything newly created.
        File fresh = new File(root, "mmkv");
        check(fresh.mkdirs(), "recreate MMKV directory");
        File freshData = new File(fresh, "fresh.mmkv");
        Files.write(freshData.toPath(), "fresh login".getBytes(StandardCharsets.UTF_8));
        Repair.healDataDir(root);
        check(freshData.isFile(), "second run must leave fresh data untouched");
        check("done earlier".equals(Repair.state), "repeat state: " + Repair.state);

        // A healthy token signature is not enough to trigger a wipe.
        File healthyRoot = Files.createTempDirectory("famunlock-repair-healthy").toFile();
        File healthy = new File(healthyRoot, "mmkv");
        check(healthy.mkdirs(), "make healthy store");
        Files.write(new File(healthy, "default.mmkv").toPath(),
                "auth_access_tokeneyJhbGciOiJ.valid.jwt".getBytes(StandardCharsets.UTF_8));
        Repair.healDataDir(healthyRoot);
        check(new File(healthy, "default.mmkv").isFile(), "healthy store must remain");
        check("store ok".equals(Repair.state), "healthy state: " + Repair.state);

    System.out.println("PASS - damaged MMKV cleared once; healthy store preserved");
    delete(root);
    delete(healthyRoot);
  }

  private static void delete(File file) {
    File[] kids = file.listFiles();
    if (kids != null) for (File kid : kids) delete(kid);
    file.delete();
  }
}
