package dev.hearth.plugin.ha_bridge;

import dev.hearth.sdk.HearthLog;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** One HTTP/1.1 request on the bridge's port: read, checked, answered (see {@link Bridge}). */
final class Server {
    private static final int MAX_BODY = 64 * 1024, MAX_HEAD = 16 * 1024;

    private Server() {}

    static void serve(Bridge b, Socket s) {
        boolean kept = false;
        try {
            if (!homeNetwork(s.getInetAddress())) {
                s.close();
                return;
            }
            s.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) {
                reply(out, 400, err("bad request"));
                return;
            }
            String method = parts[0], path = parts[1];
            Map<String, String> headers = new HashMap<>();
            int headBytes = 0;
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
                headBytes += h.length();
                if (headBytes > MAX_HEAD) {
                    reply(out, 431, err("headers too large"));
                    return;
                }
                int c = h.indexOf(':');
                if (c > 0) headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
            }
            int len = 0;
            try {
                len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            if (len < 0 || len > MAX_BODY) {
                reply(out, 413, err("body too large"));
                return;
            }
            byte[] body = new byte[len];
            for (int got = 0; got < len; ) {
                int n = in.read(body, got, len - got);
                if (n < 0) break;
                got += n;
            }
            JSONObject req = len == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
            int q = path.indexOf('?');
            Map<String, String> query = new HashMap<>();
            if (q >= 0) {
                for (String kv : path.substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) query.put(java.net.URLDecoder.decode(kv.substring(0, e), "UTF-8"),
                            java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
                path = path.substring(0, q);
            }

            // Without a token: who this is, and asking to pair.
            if ("GET".equals(method) && "/api/hello".equals(path)) {
                reply(out, 200, b.hello());
                return;
            }
            if ("POST".equals(method) && "/api/pair".equals(path)) {
                reply(out, 200, b.pairStart(req.optString("client")));
                return;
            }
            if ("GET".equals(method) && path.startsWith("/api/pair/")) {
                reply(out, 200, b.pairStatus(path.substring("/api/pair/".length())));
                return;
            }

            String auth = headers.get("authorization");
            if (b.who(auth) == null) {
                reply(out, 401, err("not paired"));
                return;
            }
            switch (method + " " + path) {
                case "DELETE /api/pair":
                    b.forget(auth);
                    reply(out, 200, new JSONObject().put("ok", true));
                    return;
                case "GET /api/describe":
                    reply(out, 200, b.describe());
                    return;
                case "GET /api/state":
                    reply(out, 200, b.state());
                    return;
                case "GET /api/search":
                    reply(out, 200, b.search(query.get("q"), query.get("limit")));
                    return;
                case "GET /api/browse":
                    reply(out, 200, b.browse(query.get("list")));
                    return;
                case "GET /api/art": {
                    byte[] img = b.art(query.get("source"), query.get("id"));
                    if (img == null || img.length == 0) {
                        reply(out, 404, err("no picture"));
                        return;
                    }
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + img.length
                            + "\r\nCache-Control: max-age=3600\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(img);
                    out.flush();
                    return;
                }
                case "POST /api/command":
                    reply(out, 200, b.command(req.optString("entity"), req.optString("command"), req.optJSONObject("args")));
                    return;
                case "GET /api/events":
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n"
                            + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    s.setSoTimeout(0);
                    kept = true;
                    b.addClient(new Bridge.Client(s, out));
                    return;
                default:
                    reply(out, 404, err("unknown"));
            }
        } catch (Exception e) {
            HearthLog.w(Bridge.TAG, "request: " + e.getClass().getSimpleName());
        } finally {
            if (!kept) try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Private, link-local or loopback addresses only: the bridge isn't for the internet. */
    static boolean homeNetwork(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet6Address) return (a.getAddress()[0] & 0xfe) == 0xfc; // unique local fc00::/7
        return false;
    }

    private static JSONObject err(String why) {
        try {
            return new JSONObject().put("ok", false).put("reason", why);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void reply(OutputStream out, int code, JSONObject body) throws IOException {
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : "Error";
        out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }
}
