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
        for (final ServerSocket s : servers) {
            Thread t = new Thread(() -> serve(s), "famunlock-" + s.getLocalPort());
            t.setDaemon(false);
            t.start();
        }
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

            // request log, readable from the phone browser:
            // http://127.0.0.1:888/__famunlock/log
            if (target.startsWith("/__famunlock/log")) {
                StringBuilder sb = new StringBuilder();
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
                byte[] ok = ("{\"status\":\"ok\",\"url\":\"" + publicUrl
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
                if (json) {
                    try {
                        Object doc = new JSONObject(text);
                        java.util.List<String> keys = Rules.rewrite(doc, publicUrl);
                        if (!keys.isEmpty()) {
                            payload = doc.toString().getBytes(StandardCharsets.UTF_8);
                            record("   rewrote " + path + ": " + keys);
                        } else {
                            record("   no rule matched " + path);
                        }
                        // the wallet can live in any answer, so log every body
                        // that mentions it - that is how we find the field the
                        // app really reads
                        String lower = text.toLowerCase(Locale.US);
                        if (lower.contains("coin") || lower.contains("gem")
                                || lower.contains("vip") || lower.contains("premium")
                                || path.contains("profile") || path.contains("login")
                                || path.contains("wallet") || path.contains("/me/")) {
                            record("   body " + path + ": " + text.substring(0, Math.min(600, text.length())));
                        }
                    } catch (Exception e) {
                        record("   body is not a JSON object " + path + ": "
                                + text.substring(0, Math.min(200, text.length())));
                    }
                } else if (text.length() > 0) {
                    record("   not json " + path + ": "
                            + text.substring(0, Math.min(200, text.length())));
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
