package com.famspace.unlock;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONObject;

/**
 * The local rewrite proxy (pure Java, no Android APIs) so it can be unit
 * tested on a desktop JVM as well as run inside the phone.
 *
 * It listens on 127.0.0.1 and forwards everything to the real service,
 * keeping the caller's headers, then rewrites the JSON answers.
 */
public final class LocalProxy {

    /**
     * Identifies the running build.  It is printed at the top of the log page
     * and in the health answer, so it is always visible which proxy is
     * serving a request - an old build without this marker cannot be told
     * apart from a new one that simply has nothing to log.
     */
    public static final String BUILD_ID = "2026-11-27-wallet7";

    // Android refuses privileged ports (<1024) for unprivileged apps:
    // binding 888 fails with BindException: EACCES (permission denied).
    public static final int DEFAULT_PORT = 8888;

    /**
     * The port of the previous build.  Binding it is best effort - it only
     * works on devices that allow privileged ports (rooted, custom kernel) -
     * but when it does, the log and the health check stay reachable at the
     * old URL as well.
     */
    public static final int LEGACY_PORT = 888;

    private static final String[] UPSTREAM = {
            "https://famspace.disoms.net",
            "https://www.famspace.us",
    };

    private final String[] upstream;
    private final int port;
    private final String publicUrl;

    private final List<ServerSocket> servers = new java.util.ArrayList<ServerSocket>();
    private volatile boolean running;
    private volatile String lastError;

    /** recent requests, for the /__famunlock/log endpoint */
    private final java.util.LinkedList<String> log = new java.util.LinkedList<String>();
    private volatile String lastPath = "";
    private volatile String lastMethod = "";

    // how many encrypted answers have been decrypted and rewritten
    private volatile int rewrittenCount;
    // the state of the cache patch
    private volatile String cacheState = "idle";
    private volatile boolean cachePatched;

    public LocalProxy() {
        this(UPSTREAM, DEFAULT_PORT, "http://127.0.0.1:" + DEFAULT_PORT);
    }

    public LocalProxy(String[] upstream, int port, String publicUrl) {
        this.upstream = upstream;
        this.port = port;
        this.publicUrl = publicUrl;
    }

    public String publicUrl() {
        return publicUrl;
    }

    public boolean isRunning() {
        return running;
    }

    public String lastError() {
        return lastError;
    }

    public void start() throws Exception {
        if (running) {
            return;
        }
        Throwable first = null;
        // 1) the exact address the patched app talks to
        try {
            servers.add(new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1")));
        } catch (Throwable t) {
            first = t;
            try {
                // 2) any interface (covers a device where 127.0.0.1 is odd)
                servers.add(new ServerSocket(port, 16));
            } catch (Throwable t2) {
                try {
                    // 3) IPv6 loopback
                    servers.add(new ServerSocket(port, 16, InetAddress.getByName("::1")));
                } catch (Throwable t3) {
                    if (first instanceof Exception) {
                        throw (Exception) first;
                    }
                    throw new java.io.IOException("cannot bind the proxy port: " + t2);
                }
            }
        }
        // 4) the legacy port, best effort - keeps /__famunlock/log reachable
        //    at the old URL on devices that allow privileged ports
        if (port != LEGACY_PORT) {
            try {
                servers.add(new ServerSocket(LEGACY_PORT, 16, InetAddress.getByName("127.0.0.1")));
            } catch (Throwable ignored) {
                // privileged port - the app does not use it any more
            }
        }
        running = true;
        patchLocalCache();
        for (final ServerSocket s : servers) {
            Thread t = new Thread(() -> serve(s), "famunlock-" + s.getLocalPort());
            t.setDaemon(false);
            t.start();
        }
        record("FamUnlock proxy build " + BUILD_ID + " started on ports " + ports());
    }

    /** The ports the proxy is listening on. */
    public String ports() {
        StringBuilder sb = new StringBuilder();
        for (ServerSocket s : servers) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s.getLocalPort());
        }
        return sb.toString();
    }

    public void stop() {
        running = false;
        for (ServerSocket s : servers) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void serve(ServerSocket server) {
        while (running) {
            try {
                final Socket client = server.accept();
                new Thread(() -> handle(client)).start();
            } catch (Exception e) {
                if (running) {
                    lastError = String.valueOf(e);
                }
            }
        }
    }

    private void handle(Socket client) {
        try (Socket sock = client) {
            sock.setSoTimeout(60000);
            InputStream in = sock.getInputStream();
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0];
            lastMethod = method;
            String rawTarget = parts[1];

            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.US),
                            line.substring(c + 1).trim());
                }
            }
            byte[] body = null;
            String cl = headers.get("content-length");
            if (cl != null) {
                body = readFully(in, Integer.parseInt(cl.trim()));
            }

            String target = normalise(rawTarget);
            lastPath = target;

            // The API answers with an encrypted payload ("responseData"), so
            // the wallet cannot be rewritten by key name.  The key either
            // comes from the server (device/install) or is assembled at
            // runtime, so the whole traffic is logged: request headers (the
            // signature) and every response body.
            record("REQ " + method + " " + target);
            if (!headers.isEmpty()) {
                record("    headers: " + redactHeaders(headers));
            }
            if (body != null && body.length > 0) {
                String rb = new String(body, StandardCharsets.UTF_8);
                record("    body: " + redactBody(rb));
            }

            // request log, readable from the phone browser:
            // http://127.0.0.1:888/__famunlock/log
            if (target.startsWith("/__famunlock/log")) {
                StringBuilder sb = new StringBuilder();
                // the banner is regenerated on every read, so the page always
                // says which build is serving it
                sb.append("FamUnlock proxy build ").append(BUILD_ID)
                        .append("  listening on ports ").append(ports())
                        .append("  [rewritten: ").append(rewrittenCount)
                        .append(", cache: ").append(cacheState).append(']').append('\n');
                synchronized (log) {
                    for (String entry : log) {
                        sb.append(entry).append('\n');
                    }
                }
                byte[] ok = sb.toString().getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                        + ok.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(ok);
                out.flush();
                return;
            }

            // health check, so the proxy can be verified from the phone's
            // browser: http://127.0.0.1:888/__famunlock/health
            if (target.startsWith("/__famunlock/health")) {
                byte[] ok = ("{\"status\":\"ok\",\"build\":\"" + BUILD_ID
                        + "\",\"url\":\"" + publicUrl
                        + "\",\"ports\":\"" + ports()
                        + "\",\"upstream\":\"" + upstream[0] + "\"}").getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + ok.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(ok);
                out.flush();
                return;
            }
            int q = target.indexOf('?');
            String path = q >= 0 ? target.substring(0, q) : target;
            String query = q >= 0 ? target.substring(q + 1) : null;

            String base = pickUpstream(path);
            URL url = new URL(base + path + (query != null ? "?" + query : ""));

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod(method);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey();
                if (k.equals("host") || k.equals("content-length") || k.equals("connection")
                        || k.equals("accept-encoding") || k.equals("proxy-connection")
                        || k.equals("transfer-encoding")) {
                    continue;
                }
                try {
                    conn.addRequestProperty(k, e.getValue());
                } catch (Exception ignored) {
                }
            }
            if (body != null && body.length > 0) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = conn.getOutputStream()) {
                    o.write(body);
                }
            }

            int status = conn.getResponseCode();
            byte[] payload = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            String ctype = conn.getContentType();
            record(method + " " + path + " -> " + status + " (" + payload.length + " bytes)"
                    + (ctype == null ? " [no content-type]" : " [" + ctype + "]"));

            if (payload.length > 0) {
                String text = new String(payload, StandardCharsets.UTF_8);
                boolean json = ctype != null && ctype.toLowerCase(Locale.US).contains("json");
                record("    response (" + payload.length + " bytes): "
                        + text.substring(0, Math.min(700, text.length())));
                if (json) {
                    try {
                        // The tree keeps the order of the keys and the text of
                        // the values that are not changed, so the app sees the
                        // same answer it would have seen - only the values we
                        // rewrite differ.
                        Json doc = Json.parse(text);
                        // the API ships its payload encrypted -> decrypt it,
                        // rewrite the wallet and encrypt it again
                        handleEncrypted(doc, headers, text,
                                body == null ? null : new String(body, StandardCharsets.UTF_8));
                        java.util.List<String> keys = Rules.rewrite(doc, publicUrl);
                        if (!keys.isEmpty()) {
                            record("    rewrote: " + keys);
                        }
                        // the answer has to be written back whenever it
                        // changed - the rewrite can be inside the encrypted
                        // payload, where the outer object looks untouched
                        String rewritten = doc.write();
                        if (!rewritten.equals(text)) {
                            payload = rewritten.getBytes(StandardCharsets.UTF_8);
                            record("    app sees: "
                                    + rewritten.substring(0, Math.min(700, rewritten.length())));
                        } else {
                            record("    no rule matched");
                        }
                    } catch (Throwable e) {
                        record("    rewrite failed: " + e);
                    }
                }
            }

            writeResponse(out, status, conn.getHeaderFields(), payload);
            out.flush();
        } catch (Exception e) {
            lastError = String.valueOf(e);
            record(lastMethod + " " + lastPath + " -> ERROR " + e);
        }
    }

    private void record(String line) {
        synchronized (log) {
            log.add(line);
            while (log.size() > 80) {
                log.removeFirst();
            }
        }
    }

    /**
     * The API answers with an encrypted payload: the real answer sits in the
     * "responseData" field, encrypted.
     *
     * The encryption was reverse engineered out of the Dart code of the app
     * (see ApiCrypto):
     *
     *   responseData = base64( ciphertext || index byte )
     *
     * The index byte selects one of the sixteen keys that the app keeps in
     * a map, the IV of the cipher is that key and the plaintext is the JSON
     * padded with zero bytes.
     *
     * So the proxy can decrypt every answer, rewrite the wallet inside it
     * and encrypt it again - the app sees a normal answer and never notices
     * anything.
     */
    private void handleEncrypted(Json doc, Map<String, String> headers, String rawText,
                                 String reqBody) {
        try {
            // 1) is there an encrypted payload in this answer?
            String b64 = null;
            String field = null;
            Json fieldNode = null;
            Map<String, Json> members = doc.members();
            if (members == null) {
                return;
            }
            for (String key : new String[]{"responseData", "response_data", "data",
                    "payload", "encryptedData", "result", "content"}) {
                Json node = members.get(key);
                if (node == null) {
                    continue;
                }
                String s = node.asString();
                if (s != null && s.length() > 16) {
                    b64 = s;
                    field = key;
                    fieldNode = node;
                    break;
                }
            }
            if (b64 == null) {
                return;
            }
            byte[] blob = Crypto.decodeBase64(b64);
            if (blob == null || blob.length < 17) {
                return;
            }
            // the field holds plain JSON -> nothing to do
            String probe = new String(blob, StandardCharsets.UTF_8);
            if (probe.trim().startsWith("{")) {
                return;
            }

            // 2) decrypt it with the key the index byte points at
            byte[] plain = ApiCrypto.decrypt(blob);
            if (plain == null) {
                record("   " + field + " is encrypted with an unknown key"
                        + " (index '" + ApiCrypto.indexOf(blob) + "')");
                return;
            }
            String text = new String(plain, StandardCharsets.UTF_8).trim();
            record("   decrypted " + field + ": " + text.substring(0, Math.min(700, text.length())));

            // 3) rewrite the wallet inside it.  The tree keeps the order of
            //    the keys and the text of the values that are not touched, so
            //    the app sees exactly the answer it would have seen.
            Json inner;
            try {
                inner = Json.parse(text);
            } catch (Exception e) {
                record("   decrypted payload is not JSON: " + e);
                return;
            }
            java.util.List<String> keys = Rules.rewrite(inner, publicUrl);
            if (!keys.isEmpty()) {
                record("   decrypted rewrite: " + keys);
            }
            record("   wallet: " + describe(inner));

            // 4) encrypt it again - the index byte stays the same, so the
            //    app picks the same key
            byte[] newPlain = inner.write().getBytes(StandardCharsets.UTF_8);
            byte[] enc = ApiCrypto.encrypt(blob, newPlain);
            if (enc != null) {
                fieldNode.set(Json.quote(Crypto.encodeBase64(enc)), Crypto.encodeBase64(enc));
                rewrittenCount++;
                record("   re-encrypted " + field + " (" + enc.length + " bytes)");
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * A short description of the wallet inside a decrypted answer, so the log
     * shows what the app is going to see.
     */
    private static String describe(Json node) {
        Map<String, Json> m = node.members();
        if (m == null) {
            return "(not an object)";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : new String[]{"coins", "gems", "vipLv", "vipEnd", "balance",
                "coin", "gem", "points"}) {
            Json v = m.get(key);
            if (v == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(key).append("=").append(v.value());
        }
        return sb.length() == 0 ? "(no wallet fields)" : sb.toString();
    }

    /**
     * Patches the app's own cache.  What the app caches is the DECRYPTED
     * answer (the Dart side decrypts before storing), so the wallet and the
     * premium flags can be set there - no key needed.
     */
    private void patchLocalCache() {
        if (cachePatched) {
            return;
        }
        cachePatched = true;
        Thread t = new Thread(new CacheTask(this), "famunlock-cache");
        t.setDaemon(true);
        t.start();
    }

    /** the cache patch (a named class: d8 trips over nested anonymous ones) */
    private static final class CacheTask implements Runnable {
        private final LocalProxy proxy;

        CacheTask(LocalProxy proxy) {
            this.proxy = proxy;
        }

        public void run() {
            // the Dart side stores the answer AFTER it was received, so the
            // cache is patched again and again for a while
            for (int round = 0; round < 20; round++) {
                try {
                    List<LocalCache.Hit> hits = LocalCache.patch();
                    if (!hits.isEmpty()) {
                        for (LocalCache.Hit h : hits) {
                            proxy.record("   patched " + h.file + " -> " + h.keys);
                        }
                        proxy.cacheState = "patched " + hits.size() + " file(s)";
                    } else if (round == 0) {
                        proxy.cacheState = "no cached answer found";
                        proxy.record("   no cached answer with a wallet in the app's data dir");
                    }
                } catch (Throwable e) {
                    proxy.cacheState = "failed: " + e;
                    proxy.record("   cache patch failed: " + e);
                    return;
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    /**
     * The request body carries the per request material in the clear:
     *
     *   anm=famspace&ver=1.0.8&uuid=<uuid>&request_data=<enc>&sign=<md5 hex>
     *
     * The sign is 32 hex characters - 16 bytes, exactly an AES-128 key - and
     * the uuid is a per request nonce.  Both are candidates.
     */
    private List<byte[]> bodyCandidates(String reqBody) {
        List<byte[]> out = new ArrayList<byte[]>();
        if (reqBody == null) {
            return out;
        }
        String uuid = field(reqBody, "uuid");
        String sign = field(reqBody, "sign");
        String data = field(reqBody, "request_data");
        if (uuid != null) {
            byte[] u = uuid.getBytes(StandardCharsets.UTF_8);
            out.add(Crypto.md5(u));
            out.add(Crypto.sha256(u));
            out.addAll(Crypto.tokenCandidates(uuid, null));
        }
        if (sign != null && sign.length() == 32) {
            try {
                byte[] h = new byte[16];
                for (int i = 0; i < 16; i++) {
                    h[i] = (byte) Integer.parseInt(sign.substring(2 * i, 2 * i + 2), 16);
                }
                out.add(h);
            } catch (Exception ignored) {
            }
            byte[] a = sign.getBytes(StandardCharsets.US_ASCII);
            out.add(Crypto.md5(a));
            out.add(Crypto.sha256(a));
        }
        if (uuid != null && sign != null) {
            byte[] both = (uuid + sign).getBytes(StandardCharsets.UTF_8);
            out.add(Crypto.md5(both));
            out.add(Crypto.sha256(both));
        }
        if (data != null) {
            byte[] d = Crypto.decodeBase64(data);
            if (d != null) {
                out.add(Crypto.md5(d));
                out.add(Crypto.sha256(d));
                if (d.length >= 16) {
                    out.add(java.util.Arrays.copyOf(d, 16));
                }
                if (d.length >= 32) {
                    out.add(java.util.Arrays.copyOf(d, 32));
                }
            }
        }
        return out;
    }

    /** reads one field out of an url encoded body */
    private static String field(String body, String name) {
        try {
            for (String part : body.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).equals(name)) {
                    return java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** every place a key could hide in the traffic */
    private List<byte[]> keyCandidates(Map<String, String> headers, JSONObject doc, String rawText) {
        List<byte[]> out = new ArrayList<byte[]>();
        // 1) values that look like key material in a plain answer
        try {
            java.util.Iterator<String> keys = doc.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = doc.opt(k);
                if (!(v instanceof String)) {
                    continue;
                }
                String s = (String) v;
                if (s.length() < 8 || s.length() > 128) {
                    continue;
                }
                String lk = k.toLowerCase(Locale.US);
                boolean named = lk.contains("key") || lk.contains("secret")
                        || lk.contains("encrypt") || lk.contains("salt")
                        || lk.contains("cipher") || lk.contains("nonce")
                        || lk.contains("iv");
                byte[] raw = s.getBytes(StandardCharsets.UTF_8);
                // the string itself, when it already is a key length
                if (raw.length == 16 || raw.length == 24 || raw.length == 32) {
                    out.add(raw);
                }
                // base64 encoded
                byte[] d = Crypto.decodeBase64(s);
                if (d != null && (d.length == 16 || d.length == 24 || d.length == 32)) {
                    out.add(d);
                }
                // hex encoded
                if (s.length() == 32 || s.length() == 48 || s.length() == 64) {
                    try {
                        byte[] h = new byte[s.length() / 2];
                        boolean hex = true;
                        for (int i = 0; i < h.length; i++) {
                            int hi = Character.digit(s.charAt(2 * i), 16);
                            int lo = Character.digit(s.charAt(2 * i + 1), 16);
                            if (hi < 0 || lo < 0) {
                                hex = false;
                                break;
                            }
                            h[i] = (byte) ((hi << 4) | lo);
                        }
                        if (hex && (h.length == 16 || h.length == 24 || h.length == 32)) {
                            out.add(h);
                        }
                    } catch (Exception ignored) {
                    }
                }
                // derived from the string
                if (named) {
                    byte[] sh = Crypto.sha256(raw);
                    if (sh != null) {
                        out.add(sh);
                    }
                    byte[] md = Crypto.md5(raw);
                    if (md != null) {
                        out.add(md);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 2) derived from the bearer token / the signature
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String lk = e.getKey();
            if (!lk.equals("authorization") && !lk.equals("sign") && !lk.equals("signature")
                    && !lk.equals("token") && !lk.equals("x-sign")) {
                continue;
            }
            if (lk.equals("authorization")) {
                // describe the token, never print it
                record("   token: " + Crypto.describeToken(e.getValue()));
            }
            byte[] raw = e.getValue().getBytes(StandardCharsets.UTF_8);
            byte[] h = Crypto.sha256(raw);
            if (h != null) {
                out.add(h);
            }
            byte[] m = Crypto.md5(raw);
            if (m != null) {
                out.add(m);
            }
            // a token that is already a key length
            int start = e.getValue().indexOf(' ');
            String token = start >= 0 ? e.getValue().substring(start + 1).trim() : e.getValue().trim();
            byte[] t = token.getBytes(StandardCharsets.UTF_8);
            if (t.length == 16 || t.length == 24 || t.length == 32) {
                out.add(t);
            }
            byte[] td = Crypto.decodeBase64(token);
            if (td != null && (td.length == 16 || td.length == 24 || td.length == 32)) {
                out.add(td);
            }
            // the whole family of token derived candidates
            out.addAll(Crypto.tokenCandidates(e.getValue(), null));
        }
        return out;
    }

    /** Hides the bearer token, keeps every other header (the signature!). */
    private static String redactHeaders(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey();
            if (!first) {
                sb.append(", ");
            }
            first = false;
            String v = e.getValue();
            if (k.equals("authorization") || k.equals("cookie")) {
                v = "<redacted " + v.length() + " chars>";
            }
            sb.append(k).append('=').append(v);
        }
        return sb.append('}').toString();
    }

    /** Hides passwords and tokens in a JSON request body. */
    private static String redactBody(String body) {
        String out = body;
        for (String key : new String[]{"password", "passwd", "pwd", "token",
                "accessToken", "refreshToken", "idToken", "code", "otp",
                "verificationCode", "sessionId", "deviceToken"}) {
            out = out.replaceAll("(?i)\"" + key + "\"\\s*:\\s*\"[^\"]*\"",
                    "\"" + key + "\":\"<redacted>\"");
        }
        return out;
    }

    /** Strips the padding prefix that the patched host string carries. */
    public static String normalise(String target) {
        int idx = target.indexOf("/api/v1");
        if (idx >= 0) {
            return target.substring(idx);
        }
        if (target.startsWith("/ap/")) {
            return target.substring(3);
        }
        return target;
    }

    private String pickUpstream(String path) {
        if (path.startsWith("/api/") || path.startsWith("/v1/")) {
            return upstream[0];
        }
        return upstream.length > 1 ? upstream[1] : upstream[0];
    }

    private void writeResponse(OutputStream out, int status, Map<String, List<String>> hdrs,
                               byte[] payload) throws Exception {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (hdrs != null) {
            for (Map.Entry<String, List<String>> e : hdrs.entrySet()) {
                String k = e.getKey();
                if (k == null) {
                    continue;
                }
                String lk = k.toLowerCase(Locale.US);
                if (lk.equals("content-length") || lk.equals("transfer-encoding")
                        || lk.equals("connection") || lk.equals("content-encoding")
                        || lk.equals("date") || lk.equals("server")) {
                    continue;
                }
                for (String v : e.getValue()) {
                    head.append(k).append(": ").append(v).append("\r\n");
                }
            }
        }
        head.append("Content-Length: ").append(payload.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            default: return "Status";
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                String s = buf.toString("ISO-8859-1");
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            buf.write(c);
        }
        return buf.size() == 0 ? null : buf.toString("ISO-8859-1");
    }

    private static byte[] readFully(InputStream in, int n) throws Exception {
        byte[] b = new byte[n];
        int read = 0;
        while (read < n) {
            int r = in.read(b, read, n - read);
            if (r < 0) {
                break;
            }
            read += r;
        }
        return b;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }
}
