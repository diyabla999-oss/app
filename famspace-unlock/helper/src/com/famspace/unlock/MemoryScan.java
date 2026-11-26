package com.famspace.unlock;

import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans the app's OWN memory for the AES key.
 *
 * The proxy runs inside the app process, so it can read /proc/self/maps and
 * /proc/self/mem.  The Dart heap (the read-only heap of libapp.so) and the
 * app's own heap are mapped there - and so is the key, whether it is a
 * constant in the snapshot or something the app assembled at runtime.
 *
 * Every 16/24/32 byte window is tried as a key against the captured
 * ciphertext; the one that really decrypts it is the key.
 *
 * Order matters: the snapshot's data segment is scanned first (that is where
 * a hard coded key lives), the rest of the address space afterwards.
 */
public final class MemoryScan {

    /** one readable region */
    private static final class Region {
        final long start;
        final long end;
        final String name;
        final boolean snapshot;

        Region(long start, long end, String name, boolean snapshot) {
            this.start = start;
            this.end = end;
            this.name = name;
            this.snapshot = snapshot;
        }

        long size() {
            return end - start;
        }
    }

    /** the readable regions of this process */
    public static List<Region> regions() {
        List<Region> out = new ArrayList<Region>();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream("/proc/self/maps"), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 5 || !parts[1].contains("r")) {
                    continue;
                }
                String[] range = parts[0].split("-");
                long start = Long.parseLong(range[0], 16);
                long end = Long.parseLong(range[1], 16);
                String name = parts.length > 5 ? parts[5] : "[anon]";
                boolean snapshot = name.contains("libapp.so");
                if (snapshot || name.contains("heap") || name.contains("[anon")
                        || name.contains("dalvik") || name.contains("data")) {
                    out.add(new Region(start, end, name, snapshot));
                }
            }
            r.close();
        } catch (Exception e) {
            // no /proc - nothing to scan
        }
        // the snapshot first, then the big regions last

        // the snapshot's data segment first - that is where a hard coded key
        // lives, and it is the smallest region
        List<Region> ordered = new ArrayList<Region>();
        for (Region r : out) {
            if (r.snapshot) {
                ordered.add(r);
            }
        }
        for (Region r : out) {
            if (!r.snapshot) {
                ordered.add(r);
            }
        }
        return ordered;
    }

    /**
     * Scan the process memory for a key that decrypts the blob.
     *
     * Two passes: 16 byte keys first (the most common), then 24 and 32.
     */
    public static byte[] scan(byte[] blob, List<Crypto.Combo> combos, Progress progress) {
        List<Region> regions = regions();
        long total = 0;
        for (Region r : regions) {
            total += r.size();
        }
        if (progress != null) {
            progress.start(total);
        }
        for (int[] lengths : new int[][]{{16}, {24, 32}}) {
            byte[] key = scanPass(regions, blob, combos, lengths, progress);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    private static byte[] scanPass(List<Region> regions, byte[] blob, List<Crypto.Combo> combos,
                                   int[] lengths, Progress progress) {
        RandomAccessFile mem = null;
        try {
            mem = new RandomAccessFile("/proc/self/mem", "r");
            byte[] buf = new byte[1 << 20];
            for (Region region : regions) {
                long pos = region.start;
                long remaining = region.end - region.start;
                while (remaining > 0) {
                    int chunk = (int) Math.min(buf.length, remaining);
                    try {
                        mem.seek(pos);
                        mem.readFully(buf, 0, chunk);
                    } catch (Exception e) {
                        break;
                    }
                    for (int i = 0; i + 32 <= chunk; i++) {
                        for (int kl : lengths) {
                            if (i + kl > chunk) {
                                break;
                            }
                            byte[] key = new byte[kl];
                            System.arraycopy(buf, i, key, 0, kl);
                            Crypto.Combo combo = Crypto.findComboFast(key, blob, combos);
                            if (combo != null) {
                                // confirm with the whole payload
                                Crypto.Combo full = Crypto.findCombo(key, blob, combos);
                                if (full == null) {
                                    continue;
                                }
                                if (progress != null) {
                                    progress.found(region.name, region.start + i, full, key);
                                }
                                return key;
                            }
                        }
                    }
                    pos += chunk;
                    remaining -= chunk;
                }
            }
        } catch (Exception e) {
            if (progress != null) {
                progress.failed(e);
            }
        } finally {
            try {
                if (mem != null) {
                    mem.close();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** progress callback so the log can show what the scan is doing */
    public interface Progress {
        void start(long bytes);

        void found(String region, long offset, Crypto.Combo combo, byte[] key);

        void failed(Exception e);
    }
}
