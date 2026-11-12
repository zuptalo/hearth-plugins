package dev.hearth.plugin.tmdb;

import android.content.Context;
import android.content.SharedPreferences;
import dev.hearth.sdk.HearthLog;
import dev.hearth.sdk.PluginException;
import dev.hearth.sdk.PluginProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Trailers and extras from TMDb for Hearth TV. TMDb lists the YouTube videos that belong to a
 * film, series, season or episode by its TMDb id (the id Jellyfin matched the title with), so every
 * video is known to be about that title. Needs the household's own TMDb key (a v3 API key or a v4
 * read access token), kept in this plugin only and never sent back to Hearth TV.
 *
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 *
 * Methods:
 * - {@code videos.for} {@code {tmdb, kind: movie|series|season|episode, season?, episode?, officialOnly?}}
 *   → {@code {videos[{youtubeId, name, type, official, published}]}}, still on YouTube, official first.
 * - {@code settings.get} → {@code {fields[{id:"apiKey", kind:"secret", label, set}]}}
 * - {@code settings.set} {@code {values:{apiKey}}} → {@code {}}, after TMDb accepts it (empty removes it).
 * - {@code migrate.import} {@code {values:{apiKey}}}: the key Hearth TV kept before this plugin.
 * - {@code actions.run} {@code {id:"trailer", context:{tmdb, kind}}} → {@code {play:{youtube}}}: its button on a
 *   film's or series' page (suggested: the red button), the title's first official trailer.
 */
public final class TmdbPlugin extends PluginProvider {
    private static final String TAG = "Tmdb";

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("tmdb", Context.MODE_PRIVATE);
    }

    private String key() {
        String k = prefs().getString("key", null);
        return k == null || k.trim().isEmpty() ? null : k.trim();
    }

    @Override
    protected JSONObject handle(String method, JSONObject request) throws Exception {
        switch (method) {
            case "describe":
                return new JSONObject().put("name", "Trailers").put("api", API)
                        .put("provides", new JSONArray().put("videos").put("settings").put("actions")).put("configured", key() != null);
            case "settings.get":
                return new JSONObject().put("fields", new JSONArray().put(new JSONObject().put("id", "apiKey").put("kind", "secret")
                        .put("label", "TMDb API key or read access token").put("set", key() != null)));
            case "settings.set":
                return setKey(request.optJSONObject("values"), true);
            case "migrate.import":
                return setKey(request.optJSONObject("values"), false);
            case "videos.for":
                return videos(request);
            case "actions.run":
                return action(request);
            default:
                return null;
        }
    }

    private JSONObject setKey(JSONObject values, boolean check) throws Exception {
        String k = values == null ? "" : values.optString("apiKey", "").replaceAll("\\s+", "");
        if (k.isEmpty()) {
            prefs().edit().remove("key").apply();
            HearthLog.i(TAG, "Key removed");
            return new JSONObject();
        }
        if (check) {
            String problem = problem(k);
            if (problem != null) throw new PluginException(PluginException.REFUSED, problem);
        }
        prefs().edit().putString("key", k).apply();
        HearthLog.i(TAG, (k.startsWith("eyJ") ? "Read access token" : "API key") + (check ? " saved" : " brought over from Hearth TV"));
        return new JSONObject();
    }

    // ---- its button ------------------------------------------------------------------------------

    private JSONObject action(JSONObject r) throws Exception {
        if (!"trailer".equals(r.optString("id"))) throw new PluginException(PluginException.NOT_FOUND, "No such action");
        JSONObject c = r.optJSONObject("context");
        if (c == null || c.optString("tmdb").isEmpty()) return new JSONObject().put("notice", "Open a film or a series first");
        JSONArray list = videos(new JSONObject().put("tmdb", c.optString("tmdb")).put("kind", c.optString("kind", "movie"))
                .put("officialOnly", true)).getJSONArray("videos");
        for (int i = 0; i < list.length(); i++) {
            JSONObject v = list.getJSONObject(i);
            if ("Trailer".equals(v.optString("type")) || "Teaser".equals(v.optString("type")))
                return new JSONObject().put("play", new JSONObject().put("youtube", v.getString("youtubeId")));
        }
        return new JSONObject().put("notice", "No trailer for this one");
    }

    // ---- videos --------------------------------------------------------------------------------

    private JSONObject videos(JSONObject r) throws Exception {
        String k = key();
        if (k == null) throw new PluginException(PluginException.SIGNED_OUT, "No TMDb key");
        String id = r.optString("tmdb", "");
        if (!id.matches("\\d{1,12}")) throw new PluginException(PluginException.NOT_FOUND, "No TMDb id");
        String kind = r.optString("kind", "movie");
        String path;
        if ("movie".equals(kind)) path = "/movie/" + id;
        else {
            path = "/tv/" + id;
            if (r.has("season") && !"series".equals(kind)) path += "/season/" + r.getInt("season");
            if (r.has("season") && r.has("episode") && "episode".equals(kind)) path += "/episode/" + r.getInt("episode");
        }
        List<JSONObject> list = fetch(path, k);
        if (r.optBoolean("officialOnly", false)) {
            List<JSONObject> kept = new ArrayList<>();
            for (JSONObject v : list) if (v.optBoolean("official")) kept.add(v);
            list = kept;
        }
        list = available(list);
        JSONArray out = new JSONArray();
        for (JSONObject v : list) out.put(v);
        return new JSONObject().put("videos", out);
    }

    private List<JSONObject> fetch(String path, String k) throws Exception {
        HttpURLConnection c = open(path + "/videos", k);
        try {
            int code = c.getResponseCode();
            if (code == 404) return Collections.emptyList();
            if (code == 401) throw new PluginException(PluginException.SIGNED_OUT, "TMDb refused the key");
            if (code != 200) throw new PluginException(PluginException.OFFLINE, "TMDb answered " + code);
            List<JSONObject> out = new ArrayList<>();
            try (InputStream in = c.getInputStream()) {
                JSONArray arr = new JSONObject(read(in)).optJSONArray("results");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null || !"YouTube".equalsIgnoreCase(o.optString("site"))) continue;
                    String yt = o.optString("key", "");
                    if (!yt.matches("[A-Za-z0-9_-]{6,20}")) continue;
                    out.add(new JSONObject().put("youtubeId", yt).put("name", o.optString("name", ""))
                            .put("type", o.optString("type", "")).put("official", o.optBoolean("official", false))
                            .put("published", o.optString("published_at", "")));
                }
            }
            // Official first, then oldest first (the first trailer before the later ones).
            Collections.sort(out, (a, b) -> a.optBoolean("official") != b.optBoolean("official") ? (a.optBoolean("official") ? -1 : 1)
                    : a.optString("published").compareTo(b.optString("published")));
            return out;
        } catch (IOException e) {
            throw new PluginException(PluginException.OFFLINE, "TMDb couldn't be reached");
        } finally {
            c.disconnect();
        }
    }

    /**
     * Drops videos that were removed or made private on YouTube (old trailers often are): YouTube's
     * oEmbed answers 401/403/404 for those. Checked a few at a time; kept if YouTube can't be asked.
     */
    private static List<JSONObject> available(List<JSONObject> in) {
        if (in.isEmpty()) return in;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, in.size()));
        List<Future<Boolean>> ok = new ArrayList<>();
        for (JSONObject v : in) ok.add(pool.submit(() -> alive(v.optString("youtubeId"))));
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            try {
                if (ok.get(i).get(6, TimeUnit.SECONDS)) out.add(in.get(i));
            } catch (Exception e) {
                out.add(in.get(i));
            }
        }
        pool.shutdownNow();
        return out;
    }

    private static boolean alive(String id) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode("https://www.youtube.com/watch?v=" + id, "UTF-8")).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            int code = c.getResponseCode();
            InputStream body = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (body != null) body.close();
            c.disconnect();
            return code != 401 && code != 403 && code != 404;
        } catch (IOException e) {
            return true;
        }
    }

    // ---- TMDb ----------------------------------------------------------------------------------

    /** Null if TMDb accepts the key (or read access token), else what went wrong, said for people. */
    private static String problem(String k) {
        try {
            HttpURLConnection c = open("/configuration", k);
            int code = c.getResponseCode();
            c.disconnect();
            if (code == 200) return null;
            if (code == 401 || code == 403) return "TMDb didn't accept it. Check you copied all of it.";
            return "TMDb answered " + code + ". Try again in a moment.";
        } catch (IOException e) {
            return "The TV couldn't reach TMDb. Is it online?";
        }
    }

    private static HttpURLConnection open(String path, String k) throws IOException {
        boolean bearer = k.startsWith("eyJ"); // a v4 read access token (JWT) rather than a v3 key
        String url = "https://api.themoviedb.org/3" + path + (path.contains("?") ? "&" : "?")
                + "language=en-US&include_video_language=en,null" + (bearer ? "" : "&api_key=" + URLEncoder.encode(k, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "HearthTV-Trailers/1.0 (Android TV)");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + k);
        return c;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
