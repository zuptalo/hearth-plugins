package dev.hearth.plugin.ha_bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import dev.hearth.sdk.HearthLog;
import dev.hearth.sdk.Host;
import dev.hearth.sdk.PluginException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The TV as Home Assistant sees it (design: hearth-homeassistant/DESIGN.md). A small HTTP API on
 * the home network, port 47815, found with NSD as {@code _hearth-tv._tcp}:
 *
 * - {@code GET /api/hello}: who this TV is (no token).
 * - {@code POST /api/pair} {client}: asks on the TV, with a code to compare; {@code GET /api/pair/<id>}
 *   until it's allowed, then the token, once. {@code DELETE /api/pair}: Home Assistant leaves.
 * - {@code GET /api/describe}, {@code /api/state}, {@code /api/events} (Server-Sent Events) and
 *   {@code POST /api/command} {entity, command, args}: with {@code Authorization: Bearer <token>}.
 *
 * Everything it does goes through Hearth TV ({@link Host}), which checks what an adult allowed
 * and keeps the profile's age limit. Only private network addresses are answered. Tokens are kept
 * as hashes; they're never logged.
 */
final class Bridge {
    static final String TAG = "HaBridge";
    static final int PORT = 47815, API = 1;
    private static final long PAIR_MS = 120_000;

    private static Bridge instance;

    static synchronized Bridge get(Context c) {
        if (instance == null) instance = new Bridge(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService work = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ha-bridge");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    private volatile ServerSocket server;
    private NsdManager.RegistrationListener nsd;
    private volatile JSONObject tv; // {id, name, version, model}

    private Bridge(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("bridge", Context.MODE_PRIVATE);
    }

    boolean running() { return server != null; }

    // ---- starting and stopping ------------------------------------------------------------------

    synchronized void start() {
        if (running) return;
        running = true;
        work.execute(this::run);
    }

    synchronized void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
        unadvertise();
        synchronized (clients) {
            for (Client c : clients) c.close();
            clients.clear();
        }
    }

    /** Waits until Hearth TV lets it be found on the network, then listens. */
    private void run() {
        while (running) {
            try {
                tv = Host.call(app, "tv.info", new JSONObject());
                break;
            } catch (PluginException e) {
                HearthLog.i(TAG, "Waiting for Hearth TV: " + e.code);
            }
            sleep(20_000);
        }
        while (running && server == null) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                server = s;
            } catch (IOException e) {
                HearthLog.w(TAG, "Port " + PORT + " busy, trying again: " + e.getMessage());
                sleep(10_000);
            }
        }
        if (!running) return;
        HearthLog.i(TAG, "Listening on " + PORT);
        advertise();
        work.execute(this::poll);
        while (running) {
            try {
                Socket s = server.accept();
                work.execute(() -> Server.serve(this, s));
            } catch (IOException e) {
                if (running) HearthLog.w(TAG, "accept: " + e.getMessage());
                else break;
            }
        }
    }

    private void advertise() {
        try {
            NsdServiceInfo info = new NsdServiceInfo();
            String name = tv.optString("name", "Hearth TV");
            info.setServiceName(name.length() > 60 ? name.substring(0, 60) : name);
            info.setServiceType("_hearth-tv._tcp");
            info.setPort(PORT);
            info.setAttribute("id", tv.optString("id"));
            info.setAttribute("api", String.valueOf(API));
            info.setAttribute("name", name);
            NsdManager m = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
            nsd = new NsdManager.RegistrationListener() {
                @Override public void onRegistrationFailed(NsdServiceInfo s, int err) { HearthLog.w(TAG, "NSD failed: " + err); }
                @Override public void onUnregistrationFailed(NsdServiceInfo s, int err) {}
                @Override public void onServiceRegistered(NsdServiceInfo s) { HearthLog.i(TAG, "Found on the network as " + s.getServiceName()); }
                @Override public void onServiceUnregistered(NsdServiceInfo s) {}
            };
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsd);
        } catch (Exception e) {
            HearthLog.w(TAG, "NSD: " + e);
        }
    }

    private void unadvertise() {
        if (nsd == null) return;
        try {
            ((NsdManager) app.getSystemService(Context.NSD_SERVICE)).unregisterService(nsd);
        } catch (Exception ignored) {
        }
        nsd = null;
    }

    // ---- tokens -------------------------------------------------------------------------------

    private JSONArray paired() {
        try {
            return new JSONArray(prefs.getString("clients", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    int pairedCount() { return paired().length(); }

    /** The Home Assistant this {@code Authorization} header belongs to, or null. */
    String who(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return null;
        byte[] h = hash(authorization.substring(7).trim()).getBytes(StandardCharsets.US_ASCII);
        JSONArray all = paired();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && MessageDigest.isEqual(h, o.optString("hash").getBytes(StandardCharsets.US_ASCII)))
                return o.optString("name", "Home Assistant");
        }
        return null;
    }

    synchronized void forget(String authorization) {
        String h = hash(authorization.substring(7).trim());
        JSONArray all = paired(), keep = new JSONArray();
        for (int i = 0; i < all.length(); i++) if (!h.equals(all.optJSONObject(i).optString("hash"))) keep.put(all.opt(i));
        prefs.edit().putString("clients", keep.toString()).apply();
        HearthLog.i(TAG, "A Home Assistant left");
    }

    private synchronized String newToken(String client) throws Exception {
        byte[] b = new byte[32];
        random.nextBytes(b);
        String token = hex(b);
        JSONArray all = paired();
        all.put(new JSONObject().put("hash", hash(token)).put("name", client).put("since", System.currentTimeMillis()));
        while (all.length() > 8) all.remove(0); // the oldest go first
        prefs.edit().putString("clients", all.toString()).apply();
        return token;
    }

    static String hash(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    // ---- pairing ------------------------------------------------------------------------------

    private static final class Pair {
        String code, client, state = "waiting", token;
        long at;
    }

    private final Map<String, Pair> pairs = new HashMap<>();

    JSONObject pairStart(String client) throws Exception {
        String name = client == null || client.trim().isEmpty() ? "Home Assistant" : client.trim();
        if (name.length() > 40) name = name.substring(0, 40);
        Pair p = new Pair();
        synchronized (pairs) {
            long now = System.currentTimeMillis();
            pairs.values().removeIf(x -> now - x.at > PAIR_MS);
            for (Pair x : pairs.values())
                if ("waiting".equals(x.state)) return new JSONObject().put("ok", false).put("code", "busy").put("reason", "Already asking on the TV");
            p.code = String.format(Locale.ROOT, "%04d", random.nextInt(10000));
            p.client = name;
            p.at = now;
            byte[] b = new byte[12];
            random.nextBytes(b);
            pairs.put(hex(b), p);
            String id = hex(b);
            work.execute(() -> ask(p));
            return new JSONObject().put("ok", true).put("id", id).put("code", p.code).put("seconds", PAIR_MS / 1000);
        }
    }

    private void ask(Pair p) {
        String answer;
        try {
            answer = Host.call(app, "ask", new JSONObject().put("title", p.client + " wants to control this TV")
                    .put("message", "Code " + spaced(p.code) + ". Check it's the code " + p.client + " shows, then allow it. "
                            + "It can see what's playing, play and pause, start films, episodes and music, and show notices.")
                    .put("yes", "Allow").put("no", "Don't allow").put("seconds", PAIR_MS / 1000 - 5)).optString("answer");
        } catch (Exception e) {
            answer = "error";
        }
        synchronized (pairs) {
            try {
                if ("yes".equals(answer)) {
                    p.token = newToken(p.client);
                    p.state = "allowed";
                } else p.state = "timeout".equals(answer) ? "expired" : "denied";
            } catch (Exception e) {
                p.state = "denied";
            }
        }
        HearthLog.i(TAG, "Pairing: " + p.state);
    }

    JSONObject pairStatus(String id) throws Exception {
        synchronized (pairs) {
            Pair p = pairs.get(id);
            if (p == null) return new JSONObject().put("state", "expired");
            JSONObject o = new JSONObject().put("state", p.state);
            if ("allowed".equals(p.state)) {
                o.put("token", p.token).put("tv", tv);
                pairs.remove(id); // handed over once
            } else if (!"waiting".equals(p.state)) pairs.remove(id);
            return o;
        }
    }

    private static String spaced(String code) { return code.replace("", " ").trim(); }

    // ---- what Home Assistant sees -------------------------------------------------------------

    JSONObject hello() throws Exception {
        JSONObject t = tv == null ? new JSONObject() : tv;
        return new JSONObject().put("id", t.optString("id")).put("name", t.optString("name")).put("api", API)
                .put("version", t.optString("version")).put("model", t.optString("model")).put("paired", pairedCount() > 0);
    }

    private static JSONObject entity(String id, String kind, String name, String icon) throws Exception {
        return new JSONObject().put("id", id).put("kind", kind).put("name", name).put("icon", icon)
                .put("meta", new JSONObject()).put("commands", new JSONArray());
    }

    JSONObject describe() throws Exception {
        JSONArray e = new JSONArray();
        JSONObject media = entity("tv", "media_player", null, "mdi:television-play");
        media.getJSONObject("meta").put("features", new JSONArray().put("play").put("pause").put("stop").put("next")
                        .put("previous").put("seek").put("volume_set").put("volume_mute").put("volume_step").put("play_media")
                        .put("browse_media").put("search_media"))
                .put("screens", new JSONArray().put("home").put("music").put("now_playing").put("youtube").put("weather"));
        media.put("commands", new JSONArray().put("play").put("pause").put("toggle").put("stop").put("next").put("previous")
                .put("forward").put("rewind").put("seek").put("volume_set").put("volume_mute").put("volume_up").put("volume_down")
                .put("play_media").put("handoff").put("open").put("open_profile"));
        e.put(media);
        e.put(entity("notify", "notify", "Show on TV", "mdi:message-text").put("commands", new JSONArray().put("show")));
        JSONObject watching = entity("playing_video", "binary_sensor", "Playing video", "mdi:movie-open-play");
        watching.getJSONObject("meta").put("device_class", "running");
        e.put(watching);
        JSONObject music = entity("playing_music", "binary_sensor", "Playing music", "mdi:music");
        music.getJSONObject("meta").put("device_class", "running");
        e.put(music);
        JSONObject source = entity("source", "sensor", "Now playing from", "mdi:play-network");
        source.getJSONObject("meta").put("device_class", "enum").put("options", new JSONArray().put("none").put("jellyfin")
                .put("youtube").put("youtube_music"));
        e.put(source);
        e.put(entity("profile", "sensor", "Profile", "mdi:account"));
        JSONObject screen = entity("screen", "sensor", "Screen", "mdi:monitor");
        screen.getJSONObject("meta").put("device_class", "enum").put("options", new JSONArray().put("none").put("home")
                .put("player").put("now_playing").put("music").put("profiles").put("settings").put("weather").put("youtube")
                .put("smart_home").put("other"));
        e.put(screen);
        JSONObject version = entity("version", "sensor", "Hearth version", "mdi:information-outline");
        version.getJSONObject("meta").put("entity_category", "diagnostic");
        e.put(version);
        e.put(entity("resume_episode", "button", "Continue the series", "mdi:television-classic").put("commands", new JSONArray().put("press")));
        e.put(entity("resume_movie", "button", "Continue the film", "mdi:movie-open").put("commands", new JSONArray().put("press")));
        JSONObject remote = entity("remote", "remote", "Remote", "mdi:remote-tv");
        remote.getJSONObject("meta").put("keys", new JSONArray().put("up").put("down").put("left").put("right").put("ok")
                .put("back").put("home"));
        remote.put("commands", new JSONArray().put("send"));
        e.put(remote);
        JSONObject ev = entity("playback", "event", "Playback", "mdi:play-pause");
        ev.getJSONObject("meta").put("event_types", new JSONArray().put("started").put("paused").put("resumed").put("stopped"));
        e.put(ev);
        return new JSONObject().put("api", API).put("tv", tv).put("entities", e);
    }

    private static final Pattern EPISODE = Pattern.compile("S(\\d+):E(\\d+)(?: · (.*))?");

    /** Every entity's state, from one look at Hearth TV. */
    JSONObject state() throws Exception {
        JSONObject s, v;
        try {
            s = Host.call(app, "playback.state", new JSONObject());
        } catch (PluginException e) {
            s = new JSONObject().put("state", "idle").put("problem", e.code);
        }
        try {
            v = Host.call(app, "volume.get", new JSONObject());
        } catch (PluginException e) {
            v = new JSONObject();
        }
        String st = s.optString("state", "idle"), kind = s.optString("kind", ""), where = s.optString("where", "");
        JSONObject a = new JSONObject();
        String title = s.optString("title", null), sub = s.optString("subtitle", null);
        if ("episode".equals(kind) && sub != null) {
            Matcher m = EPISODE.matcher(sub);
            if (m.matches()) {
                a.put("media_series_title", title).put("media_season", Integer.parseInt(m.group(1)))
                        .put("media_episode", Integer.parseInt(m.group(2)));
                title = m.group(3) != null ? m.group(3) : title;
            }
        }
        if (title != null) a.put("media_title", title);
        if (s.has("artist") && !s.isNull("artist")) a.put("media_artist", s.optString("artist"));
        if (s.has("album") && !s.isNull("album")) a.put("media_album_name", s.optString("album"));
        if (!kind.isEmpty()) a.put("media_content_type", kind.equals("musicvideo") ? "music" : kind);
        if (s.has("id")) a.put("media_content_id", s.optString("id"));
        if (s.has("position")) a.put("media_position", s.optLong("position"));
        if (s.has("duration")) a.put("media_duration", s.optLong("duration"));
        if (v.has("level")) a.put("volume_level", v.optDouble("level"));
        if (v.has("muted")) a.put("is_volume_muted", v.optBoolean("muted"));
        a.put("source", s.optString("source", "none")).put("where", where);
        boolean playing = "playing".equals(st) || "buffering".equals(st);
        boolean video = !"music".equals(where) && ("movie".equals(kind) || "episode".equals(kind) || "video".equals(kind));
        JSONObject out = new JSONObject();
        boolean on = s.optBoolean("on", true);
        out.put("tv", new JSONObject().put("available", true).put("value", on ? st : "off").put("attributes", a));
        out.put("playing_video", value(playing && video));
        out.put("playing_music", value(playing && ("music".equals(kind) || "musicvideo".equals(kind))));
        out.put("source", value("idle".equals(st) ? "none" : s.optString("source", "none")));
        out.put("profile", value(s.optString("profile", null)));
        out.put("screen", value(s.optString("screen", "none")));
        out.put("version", value(tv == null ? null : tv.optString("version")));
        out.put("notify", value(null));
        out.put("resume_episode", value(null));
        out.put("resume_movie", value(null));
        out.put("playback", value(null));
        out.put("remote", value(on));
        return new JSONObject().put("entities", out);
    }

    private static JSONObject value(Object v) throws Exception {
        return new JSONObject().put("available", true).put("value", v == null ? JSONObject.NULL : v).put("attributes", new JSONObject());
    }

    /** One command, checked against what each entity declares: nothing else runs. */
    JSONObject command(String entity, String command, JSONObject args) throws Exception {
        if (args == null) args = new JSONObject();
        JSONObject reply = new JSONObject().put("ok", true);
        try {
            switch (entity + "/" + command) {
                case "tv/play": case "tv/pause": case "tv/toggle": case "tv/stop": case "tv/next": case "tv/previous":
                case "tv/forward": case "tv/rewind":
                    Host.call(app, "playback.press", new JSONObject().put("button", command));
                    break;
                case "tv/seek":
                    Host.call(app, "playback.seek", new JSONObject().put("position", args.optLong("position", -1)));
                    break;
                case "tv/volume_set":
                    Host.call(app, "volume.set", new JSONObject().put("level", args.optDouble("level", 0)));
                    break;
                case "tv/volume_mute":
                    Host.call(app, "volume.set", new JSONObject().put("muted", args.optBoolean("muted", true)));
                    break;
                case "tv/volume_up": case "tv/volume_down":
                    Host.call(app, "volume.set", new JSONObject().put("step", command.endsWith("up") ? 1 : -1));
                    break;
                case "tv/play_media": {
                    JSONObject r = new JSONObject();
                    for (String k : new String[]{"what", "id", "kind", "query", "artist", "season", "episode", "source", "fromStart", "back",
                            "handoff", "at", "playlist", "title", "channel", "music"})
                        if (args.has(k)) r.put(k, args.get(k));
                    reply.put("playing", Host.call(app, "media.play", r).optJSONObject("playing"));
                    break;
                }
                case "tv/handoff": // what plays, for another TV to carry on (tv/play_media what handoff)
                    reply.put("handoff", Host.call(app, "media.handoff", new JSONObject()));
                    break;
                case "tv/open_profile": // Home Assistant vouches for who is asking; Hearth checks the profile allows it
                    reply.put("profile", Host.call(app, "profiles.open", new JSONObject().put("name", args.optString("name"))));
                    break;
                case "remote/send":
                    Host.call(app, "navigate.key", new JSONObject().put("key", args.optString("key")));
                    break;
                case "tv/open":
                    Host.call(app, "navigate.open", new JSONObject().put("screen", args.optString("screen")));
                    break;
                case "notify/show":
                    Host.call(app, "notice.show", new JSONObject().put("title", args.optString("title", ""))
                            .put("message", args.optString("message", "")));
                    break;
                case "resume_episode/press":
                    reply.put("playing", Host.call(app, "media.play", new JSONObject().put("what", "resume").put("kind", "episode")).optJSONObject("playing"));
                    break;
                case "resume_movie/press":
                    reply.put("playing", Host.call(app, "media.play", new JSONObject().put("what", "resume").put("kind", "movie")).optJSONObject("playing"));
                    break;
                default:
                    return new JSONObject().put("ok", false).put("code", "not_found").put("reason", "No command " + command + " on " + entity);
            }
        } catch (PluginException e) {
            return new JSONObject().put("ok", false).put("code", e.code).put("reason", e.getMessage());
        }
        HearthLog.i(TAG, "Command " + entity + "/" + command);
        nudge();
        return reply;
    }

    /** Films and series matching {@code q}, for Home Assistant's search and Assist. */
    JSONObject search(String q, String limit) throws Exception {
        if (q == null || q.trim().isEmpty()) return new JSONObject().put("items", new JSONArray());
        int n = 12;
        try {
            n = Integer.parseInt(limit);
        } catch (Exception ignored) {
        }
        try {
            return Host.call(app, "media.search", new JSONObject().put("query", q.trim()).put("limit", n)).put("ok", true);
        } catch (PluginException e) {
            return new JSONObject().put("ok", false).put("code", e.code).put("reason", e.getMessage()).put("items", new JSONArray());
        }
    }

    /** A picture of what plays or was found (JPEG bytes), or null. Through the TV: no key leaves it. */
    byte[] art(String source, String id) {
        try {
            JSONObject r = Host.call(app, "media.art", new JSONObject().put("source", source == null ? "jellyfin" : source).put("id", id));
            return android.util.Base64.decode(r.optString("data", ""), android.util.Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code resume} or {@code next_up}, for Home Assistant's media browser. */
    JSONObject browse(String list) throws Exception {
        try {
            return Host.call(app, "media.list", new JSONObject().put("list", list == null ? "" : list)).put("ok", true);
        } catch (PluginException e) {
            return new JSONObject().put("ok", false).put("code", e.code).put("reason", e.getMessage()).put("items", new JSONArray());
        }
    }

    // ---- live updates (Server-Sent Events) ----------------------------------------------------

    static final class Client {
        final Socket socket;
        final OutputStream out;

        Client(Socket s, OutputStream o) {
            socket = s;
            out = o;
        }

        synchronized boolean send(String event, String data) {
            try {
                out.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                close();
                return false;
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private final List<Client> clients = new ArrayList<>();
    private final Object tick = new Object();
    private final Map<String, String> last = new HashMap<>();
    private String lastPlayback = "idle", lastTitle = "", lastKind, lastSource;

    void addClient(Client c) {
        synchronized (clients) {
            clients.add(c);
        }
        try {
            c.send("hello", hello().toString());
        } catch (Exception ignored) {
        }
        synchronized (last) {
            last.clear(); // the new one hears everything once
        }
        nudge();
    }

    void nudge() {
        synchronized (tick) {
            tick.notifyAll();
        }
    }

    /** While anyone listens: Hearth TV's state every second, and what changed sent on. */
    private void poll() {
        long pingAt = 0;
        while (running) {
            boolean anyone;
            synchronized (clients) {
                anyone = !clients.isEmpty();
            }
            if (anyone) {
                try {
                    JSONObject ents = state().getJSONObject("entities");
                    for (Iterator<String> it = ents.keys(); it.hasNext(); ) {
                        String id = it.next();
                        JSONObject e = ents.getJSONObject(id);
                        JSONObject cmp = new JSONObject(e.toString());
                        JSONObject at = cmp.optJSONObject("attributes");
                        if (at != null) at.remove("media_position"); // moves every second: sent with other changes only
                        String key = cmp.toString();
                        boolean moved;
                        synchronized (last) {
                            moved = !key.equals(last.get(id));
                            if (moved) last.put(id, key);
                        }
                        if (moved) broadcast("state", new JSONObject().put("id", id).put("state", e).toString());
                    }
                    events(ents.getJSONObject("tv"));
                } catch (Exception e) {
                    HearthLog.w(TAG, "poll: " + e);
                }
                long now = System.currentTimeMillis();
                if (now - pingAt > 25_000) {
                    pingAt = now;
                    broadcast("ping", "{}");
                }
            }
            synchronized (tick) {
                try {
                    tick.wait(anyone ? 1000 : 30_000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** started / paused / resumed / stopped, for automations ("dim the lights when a film starts"). */
    private void events(JSONObject tvState) throws Exception {
        String st = tvState.optString("value", "idle");
        JSONObject a = tvState.optJSONObject("attributes");
        String title = a == null ? "" : a.optString("media_series_title", a.optString("media_title", ""));
        String now = "buffering".equals(st) ? "playing" : "off".equals(st) ? "idle" : st;
        String type = null;
        if (!now.equals(lastPlayback) || (!"idle".equals(now) && !title.equals(lastTitle))) {
            if ("playing".equals(now)) type = "paused".equals(lastPlayback) && title.equals(lastTitle) ? "resumed" : "started";
            else if ("paused".equals(now)) type = "paused";
            else if ("idle".equals(now) && !"idle".equals(lastPlayback)) type = "stopped";
        }
        String kind = a == null ? null : a.optString("media_content_type", null), source = a == null ? null : a.optString("source", null);
        if ("stopped".equals(type)) { // what stopped: as it was a moment ago
            title = lastTitle;
            kind = lastKind;
            source = lastSource;
        }
        lastPlayback = now;
        if (!"idle".equals(now)) {
            lastTitle = title;
            lastKind = kind;
            lastSource = source;
        }
        if (type == null) return;
        JSONObject data = new JSONObject().put("type", type).put("kind", kind).put("title", title).put("source", source);
        broadcast("event", new JSONObject().put("id", "playback").put("event", data).toString());
    }

    private void broadcast(String event, String data) {
        List<Client> all;
        synchronized (clients) {
            all = new ArrayList<>(clients);
        }
        for (Client c : all)
            if (!c.send(event, data)) synchronized (clients) {
                clients.remove(c);
            }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
