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

    public static final int DEFAULT_PORT = 888;

    private static final String[] UPSTREAM = {
            "https://famspace.disoms.net",
            "https://www.famspace.us",
    };

    private final String[] upstream;
    private final int port;
    private final String publicUrl;
    private ServerSocket server;
    private volatile boolean running;
    private volatile String lastError;

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

    public String lastError() {
        return lastError;
    }

    public void start() throws Exception {
        server = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        running = true;
        Thread t = new Thread(this::serve, "famunlock");
        t.setDaemon(false);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void serve() {
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

            if (ctype != null && ctype.toLowerCase(Locale.US).contains("json") && payload.length > 0) {
                try {
                    Object doc = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                    if (Rules.rewrite(doc, publicUrl)) {
                        payload = doc.toString().getBytes(StandardCharsets.UTF_8);
                    }
                } catch (Exception ignored) {
                    // keep the original body if it is not plain JSON
                }
            }

            writeResponse(out, status, conn.getHeaderFields(), payload);
            out.flush();
        } catch (Exception e) {
            lastError = String.valueOf(e);
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
