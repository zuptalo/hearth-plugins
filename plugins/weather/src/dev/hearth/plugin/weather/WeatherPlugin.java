package dev.hearth.plugin.weather;

import android.content.Context;
import android.content.SharedPreferences;
import dev.hearth.sdk.HearthLog;
import dev.hearth.sdk.Http;
import dev.hearth.sdk.PluginException;
import dev.hearth.sdk.PluginProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The weather for Hearth TV: now, the next hours and the next ten days.
 *
 * In the Nordic area it comes from SMHI, the Swedish Meteorological and Hydrological Institute
 * (open data, credit "Source: SMHI"). Elsewhere it comes from Open-Meteo (open source, no account
 * or key).
 *
 * The place is the one chosen in Hearth TV's settings (searched with Open-Meteo's place search),
 * else where the TV is, from its internet address (ipapi.co, else GeoJS; asked at most once a day),
 * else the city its time zone is named after (Europe/Stockholm → Stockholm).
 *
 * Methods:
 * - {@code weather.forecast} {@code {timezone}} →
 *   {@code {place, source, hours[{time, temp, rainMm, rainChance, wind, gust, condition, day, words}],
 *   days[{date, min, max, rainMm, rainChance, wind, condition, words}]}}.
 * - {@code settings.get} → {@code {fields[{id:"place", kind:"search", label, value, auto}]}}.
 * - {@code settings.search} {@code {field:"place", query}} → {@code {choices[{value, label, detail}]}};
 *   the first is always "where the TV is" (value "").
 * - {@code settings.set} {@code {values:{place}}}: a value from the search, or "" for where the TV is.
 * - {@code actions.run} {@code {id:"forecast"}} → {@code {open:"weather"}}: its button (suggested: Guide).
 */
public final class WeatherPlugin extends PluginProvider {
    private static final String TAG = "Weather";
    private static final String UA = "HearthTV-Weather/1.0 (Android TV)";
    private static final long FRESH_MS = 30 * 60 * 1000;
    private static final long WHERE_MS = 24 * 3600 * 1000; // where the TV is: asked once a day at most

    // Conditions, as the contract names them. Index = the order of severity for a day's look.
    private static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, RAIN = 4, SLEET = 5, SNOW = 6, STORM = 7;
    private static final String[] CONDITION = {"clear", "partly-cloudy", "cloudy", "fog", "rain", "sleet", "snow", "storm"};
    private static final int[] SEVERITY = {0, 0, 1, 2, 3, 4, 5, 6};

    private static final class Hour {
        long time;
        double temp, rainMm, wind, gust;
        int rainChance = -1, condition;
        boolean day;
        String words;
    }

    private static final class Day {
        long date;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, rainMm, wind;
        int rainChance = -1, condition;
        String words;
    }

    private static final class Forecast {
        String place, source;
        final List<Hour> hours = new ArrayList<>();
        final List<Day> days = new ArrayList<>();
    }

    @Override
    protected JSONObject handle(String method, JSONObject request) throws Exception {
        switch (method) {
            case "describe":
                return new JSONObject().put("name", "Weather").put("api", API).put("provides", new JSONArray().put("weather").put("settings").put("actions"));
            case "weather.forecast":
                return forecast(request.optString("timezone", TimeZone.getDefault().getID()));
            case "settings.get":
                return settings(request.optString("timezone", TimeZone.getDefault().getID()));
            case "settings.search":
                return search(request.optString("query", ""), request.optString("locale", "en"));
            case "settings.set":
                return choose(request.optJSONObject("values"));
            case "actions.run": // its button (res/xml/hearth_plugin.xml): Hearth TV's forecast screen
                if ("forecast".equals(request.optString("id"))) return new JSONObject().put("open", "weather");
                throw new PluginException(PluginException.NOT_FOUND, "No such action");
            default:
                return null;
        }
    }

    // The last answer, kept in memory: reading and decoding SMHI's forecast takes ~100 ms on a TV.
    private String keptFor;
    private JSONObject kept;
    private long keptAt;

    private synchronized JSONObject forecast(String zone) throws Exception {
        long now = System.currentTimeMillis();
        Place where = where(zone);
        String key = zone + "|" + where.lat + "," + where.lon;
        // Reused within the same hour (the list starts at the current hour) and while fresh.
        if (kept != null && key.equals(keptFor) && now - keptAt < FRESH_MS && now / 3_600_000 == keptAt / 3_600_000) return kept;
        JSONObject out = build(zone, where);
        keptFor = key;
        kept = out;
        keptAt = now;
        return out;
    }

    private JSONObject build(String zone, Place where) throws Exception {
        Forecast f = load(TimeZone.getTimeZone(zone), where);
        if (f == null || f.hours.isEmpty()) throw new PluginException(PluginException.OFFLINE, "No forecast");
        JSONArray hours = new JSONArray(), days = new JSONArray();
        for (Hour h : f.hours) {
            hours.put(new JSONObject().put("time", h.time).put("temp", h.temp).put("rainMm", h.rainMm)
                    .put("rainChance", h.rainChance).put("wind", h.wind).put("gust", h.gust)
                    .put("condition", CONDITION[h.condition]).put("day", h.day).put("words", h.words));
        }
        for (Day d : f.days) {
            days.put(new JSONObject().put("date", d.date).put("min", d.min).put("max", d.max).put("rainMm", d.rainMm)
                    .put("rainChance", d.rainChance).put("wind", d.wind)
                    .put("condition", CONDITION[d.condition]).put("words", d.words));
        }
        return new JSONObject().put("place", f.place).put("source", f.source).put("hours", hours).put("days", days);
    }

    private Forecast load(TimeZone tz, Place where) throws Exception {
        double lat = where.lat, lon = where.lon;
        String place = round(lat) + "," + round(lon);
        String key = place.replace(',', '_');

        Forecast f = null;
        if (nordic(lat, lon)) {
            try {
                f = smhi(fetch("fc-smhi-" + key, "https://opendata-download-metfcst.smhi.se/api/category/snow1g/version/1/geotype/point/lon/"
                        + round(lon) + "/lat/" + round(lat) + "/data.json"), lat, lon, tz);
            } catch (Exception e) {
                HearthLog.w(TAG, "SMHI: " + e);
            }
        }
        if (f == null) {
            f = openMeteo(fetch("fc-om-" + key, "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&hourly=temperature_2m,weather_code,is_day,precipitation,precipitation_probability,wind_speed_10m,wind_gusts_10m"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max"
                    + "&wind_speed_unit=ms&forecast_days=11&timezone=UTC"), tz);
        }
        if (f != null) f.place = where.name;
        return f;
    }

    // ---- the place ---------------------------------------------------------------------------

    private static final class Place {
        double lat, lon;
        String name;
        boolean chosen;      // picked in the settings (else found by itself)
        String country = ""; // two letters, for ranking search results ("" unknown)
        String languages = "";
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** The chosen place, else where the TV is, else the time zone's city. */
    private Place where(String zone) throws Exception {
        Place p = chosen();
        if (p != null) return p;
        p = located();
        if (p != null) return p;
        return zoneCity(zone);
    }

    /** "lat,lon,name": the value a search choice carries. */
    private static Place parse(String v) {
        if (v == null) return null;
        String[] x = v.split(",", 3);
        if (x.length < 3 || x[2].trim().isEmpty()) return null;
        try {
            Place p = new Place();
            p.lat = Double.parseDouble(x[0]);
            p.lon = Double.parseDouble(x[1]);
            p.name = x[2].trim();
            if (Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180 || p.name.length() > 80) return null;
            return p;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Place chosen() {
        Place p = parse(prefs().getString("place", null));
        if (p != null) p.chosen = true;
        return p;
    }

    /**
     * Where the TV is, from its internet address: kept a day, and the last answer kept when the
     * lookup fails. Null if never found.
     */
    private Place located() {
        File f = file("where");
        String kept = read("where");
        if (kept == null || System.currentTimeMillis() - f.lastModified() > WHERE_MS) {
            String found = lookUp();
            if (found != null) write("where", kept = found);
            else if (kept != null) f.setLastModified(System.currentTimeMillis() - WHERE_MS + 3600_000); // again in an hour
        }
        if (kept == null) return null;
        try {
            JSONObject o = new JSONObject(kept);
            Place p = new Place();
            p.lat = o.getDouble("lat");
            p.lon = o.getDouble("lon");
            p.name = o.getString("city");
            p.country = o.optString("country", "");
            p.languages = o.optString("languages", "");
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** The city of the TV's internet address, as {lat, lon, city, country, languages}, or null. */
    private static String lookUp() {
        try {
            String s = Http.get("https://ipapi.co/json/", UA);
            if (s != null) {
                JSONObject o = new JSONObject(s);
                if (!o.optBoolean("error") && o.has("latitude") && !o.optString("city").isEmpty())
                    return located(o.getDouble("latitude"), o.getDouble("longitude"), o.getString("city"),
                            o.optString("country_code", ""), o.optString("languages", ""));
            }
            s = Http.get("https://get.geojs.io/v1/ip/geo.json", UA);
            if (s != null) {
                JSONObject o = new JSONObject(s);
                if (o.has("latitude") && !o.optString("city").isEmpty())
                    return located(Double.parseDouble(o.getString("latitude")), Double.parseDouble(o.getString("longitude")),
                            o.getString("city"), o.optString("country_code", ""), "");
            }
        } catch (Exception e) {
            HearthLog.w(TAG, "Where the TV is: " + e.getClass().getSimpleName());
        }
        return null;
    }

    private static String located(double lat, double lon, String city, String country, String languages) throws Exception {
        // Two decimals (about a kilometre) is plenty for the weather.
        return new JSONObject().put("lat", Math.round(lat * 100) / 100.0).put("lon", Math.round(lon * 100) / 100.0)
                .put("city", city).put("country", country).put("languages", languages).toString();
    }

    /** The city the time zone is named after. */
    private Place zoneCity(String zone) throws Exception {
        int slash = zone.lastIndexOf('/');
        if (slash < 0) throw new PluginException(PluginException.NOT_FOUND, "The time zone names no place");
        String city = zone.substring(slash + 1).replace('_', ' ');
        String placeKey = "place-" + zone.replace('/', '_'), place = read(placeKey);
        if (place == null) {
            String g = Http.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city, "UTF-8"), UA);
            if (g == null) throw new PluginException(PluginException.OFFLINE, "No place search");
            JSONArray res = new JSONObject(g).optJSONArray("results");
            if (res == null || res.length() == 0) throw new PluginException(PluginException.NOT_FOUND, "Unknown place");
            JSONObject p = res.getJSONObject(0);
            place = p.getDouble("latitude") + "," + p.getDouble("longitude");
            write(placeKey, place);
        }
        String[] ll = place.split(",");
        Place p = new Place();
        p.lat = Double.parseDouble(ll[0]);
        p.lon = Double.parseDouble(ll[1]);
        p.name = city;
        return p;
    }

    // ---- settings ----------------------------------------------------------------------------

    private JSONObject settings(String zone) throws Exception {
        Place p = chosen();
        String auto = null;
        if (p == null) {
            Place here = located();
            auto = here != null ? here.name : null;
        }
        JSONObject field = new JSONObject().put("id", "place").put("kind", "search").put("label", "Place")
                .put("auto", p == null)
                .put("value", p != null ? p.name : auto != null ? auto : zone.substring(zone.lastIndexOf('/') + 1).replace('_', ' '));
        return new JSONObject().put("fields", new JSONArray().put(field));
    }

    private JSONObject choose(JSONObject values) throws Exception {
        if (values == null || !values.has("place")) return new JSONObject();
        String v = values.optString("place", "").trim();
        if (v.isEmpty()) {
            prefs().edit().remove("place").apply();
            HearthLog.i(TAG, "Place: where the TV is");
        } else {
            if (parse(v) == null) throw new PluginException(PluginException.REFUSED, "That isn't a place.");
            prefs().edit().putString("place", v).apply();
            HearthLog.i(TAG, "Place chosen");
        }
        synchronized (this) {
            kept = null; // the next forecast is for the new place
        }
        return new JSONObject();
    }

    /**
     * Places whose name starts like the query (towns and cities, not airports or parks), in the TV's
     * language and the home country's: the home country first, then the biggest.
     */
    private JSONObject search(String query, String locale) throws Exception {
        JSONArray out = new JSONArray();
        Place here = located();
        out.put(new JSONObject().put("value", "").put("label", "Where the TV is")
                .put("detail", here != null ? here.name : "From the time zone"));
        String q = query.trim();
        if (q.length() < 2) return new JSONObject().put("choices", out);
        String home = here != null ? here.country : "";
        List<String> langs = new ArrayList<>();
        langs.add(locale.split("[-_]")[0].toLowerCase(Locale.ROOT));
        if (here != null) for (String l : here.languages.split(",")) {
            String two = l.trim().split("[-_]")[0].toLowerCase(Locale.ROOT);
            if (two.length() == 2 && !langs.contains(two)) {
                langs.add(two);
                break; // the country's main language is enough
            }
        }
        Map<Long, JSONObject> found = new LinkedHashMap<>();
        boolean answered = false;
        for (String lang : langs) {
            String g = Http.get("https://geocoding-api.open-meteo.com/v1/search?count=20&format=json&language=" + lang
                    + "&name=" + URLEncoder.encode(q, "UTF-8"), UA);
            if (g == null) continue;
            answered = true;
            JSONArray res = new JSONObject(g).optJSONArray("results");
            for (int i = 0; res != null && i < res.length(); i++) {
                JSONObject r = res.getJSONObject(i);
                if (!r.optString("feature_code", "PPL").startsWith("PPL")) continue; // towns and cities only
                long id = r.optLong("id", i);
                if (!found.containsKey(id)) found.put(id, r); // the TV's language first: its names win
            }
        }
        if (!answered)
            throw new PluginException(PluginException.OFFLINE, "No place search");
        String nq = plain(q);
        List<JSONObject> list = new ArrayList<>(found.values());
        list.sort((a, b) -> {
            int pa = plain(a.optString("name")).startsWith(nq) ? 0 : 1, pb = plain(b.optString("name")).startsWith(nq) ? 0 : 1;
            if (pa != pb) return pa - pb;
            int ha = home.equalsIgnoreCase(a.optString("country_code")) ? 0 : 1, hb = home.equalsIgnoreCase(b.optString("country_code")) ? 0 : 1;
            if (ha != hb) return ha - hb;
            return Long.compare(b.optLong("population", 0), a.optLong("population", 0));
        });
        List<String> seen = new ArrayList<>();
        for (JSONObject r : list) {
            String name = r.optString("name"), country = r.optString("country", "");
            // "Västra Götalands län" → "Västra Götaland", "Västra Götaland County" → "Västra Götaland".
            String region = r.optString("admin1", "").replaceAll("s? län$", "").replaceAll(" (County|kommun|fylke|maakunta|Region|Province)$", "");
            // Short enough for the list beside the keys: the region at home, the country elsewhere.
            boolean atHome = !home.isEmpty() && home.equalsIgnoreCase(r.optString("country_code"));
            String detail = atHome && !region.isEmpty() && !region.equals(name) ? region : country;
            if (seen.contains(name + "|" + detail)) continue;
            seen.add(name + "|" + detail);
            out.put(new JSONObject().put("value", round(r.getDouble("latitude")) + "," + round(r.getDouble("longitude")) + "," + name)
                    .put("label", name).put("detail", detail));
            if (out.length() > 12) break;
        }
        return new JSONObject().put("choices", out);
    }

    /** Lower case without accents: "Göteborg" → "goteborg". */
    private static String plain(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    // ---- kept in the plugin's own files ------------------------------------------------------

    /** The stored answer when fresh, else a new one (or the stored one if the new one fails). */
    private String fetch(String key, String url) throws Exception {
        File file = file(key);
        String kept = read(key);
        if (kept != null && System.currentTimeMillis() - file.lastModified() < FRESH_MS) return kept;
        String s = Http.get(url, UA);
        if (s != null && s.trim().startsWith("{")) {
            write(key, s);
            return s;
        }
        if (kept != null) return kept;
        throw new PluginException(PluginException.OFFLINE, "No answer");
    }

    private File file(String key) { return new File(getContext().getCacheDir(), key.replaceAll("[^A-Za-z0-9._-]", "_")); }

    private String read(String key) {
        try {
            File f = file(key);
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void write(String key, String text) {
        File f = file(key), tmp = new File(f.getPath() + ".new");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        tmp.renameTo(f);
    }

    /** SMHI's forecast area: the Nordic countries and their seas. */
    private static boolean nordic(double lat, double lon) {
        return lat >= 52.5 && lat <= 72 && lon >= 2 && lon <= 38;
    }

    private static String round(double v) { return String.format(Locale.ROOT, "%.4f", v); }

    // ---- SMHI ------------------------------------------------------------------------------------

    private static Forecast smhi(String json, double lat, double lon, TimeZone tz) throws Exception {
        JSONArray ts = new JSONObject(json).getJSONArray("timeSeries");
        Forecast f = new Forecast();
        f.source = "SMHI";
        long hourStart = System.currentTimeMillis() / 3_600_000 * 3_600_000;
        for (int i = 0; i < ts.length(); i++) {
            JSONObject o = ts.getJSONObject(i), d = o.getJSONObject("data");
            long time = iso(o.getString("time"));
            if (time < hourStart) continue;
            Hour h = new Hour();
            h.time = time;
            h.temp = d.optDouble("air_temperature", Double.NaN);
            // The amount for the whole step (an hour near now, up to 12 h later on), averaged over
            // SMHI's many runs of the forecast: a dry day still gets a few tenths.
            h.rainMm = d.optDouble("precipitation_amount_mean", 0);
            h.rainChance = d.optInt("probability_of_precipitation", -1);
            h.wind = d.optDouble("wind_speed", 0);
            h.gust = d.optDouble("wind_speed_of_gust", 0);
            int sym = d.optInt("symbol_code", 0);
            h.condition = conditionOfSmhi(sym);
            h.day = daylight(lat, lon, time);
            h.words = sym >= 1 && sym < SMHI_WORDS.length ? SMHI_WORDS[sym] : "";
            if (!Double.isNaN(h.temp) && sym > 0) f.hours.add(h);
        }
        days(f, tz);
        return f.hours.isEmpty() ? null : f;
    }

    /** SMHI weather symbols ("symbol_code", 1-27). */
    private static int conditionOfSmhi(int s) {
        if (s <= 2) return CLEAR;
        if (s <= 4) return PARTLY;
        if (s <= 6) return CLOUDY;
        if (s == 7) return FOG;
        if (s == 11 || s == 21) return STORM;
        if (s >= 12 && s <= 14 || s >= 22 && s <= 24) return SLEET;
        if (s >= 15 && s <= 17 || s >= 25) return SNOW;
        return RAIN; // 8-10 showers, 18-20 rain
    }

    private static final String[] SMHI_WORDS = {"", "Clear", "Nearly clear", "Variable clouds", "Partly cloudy", "Cloudy",
            "Overcast", "Fog", "Light showers", "Showers", "Heavy showers", "Thunderstorm", "Light sleet showers",
            "Sleet showers", "Heavy sleet showers", "Light snow showers", "Snow showers", "Heavy snow showers",
            "Light rain", "Rain", "Heavy rain", "Thunder", "Light sleet", "Sleet", "Heavy sleet", "Light snow", "Snow",
            "Heavy snow"};

    // ---- Open-Meteo ------------------------------------------------------------------------------

    private static Forecast openMeteo(String json, TimeZone tz) throws Exception {
        JSONObject o = new JSONObject(json);
        Forecast f = new Forecast();
        f.source = "Open-Meteo";
        JSONObject h = o.getJSONObject("hourly");
        JSONArray time = h.getJSONArray("time");
        long hourStart = System.currentTimeMillis() / 3_600_000 * 3_600_000;
        for (int i = 0; i < time.length(); i++) {
            long t = isoLocal(time.getString(i), TimeZone.getTimeZone("UTC"));
            if (t < hourStart) continue;
            Hour x = new Hour();
            x.time = t;
            x.temp = h.getJSONArray("temperature_2m").optDouble(i, Double.NaN);
            int code = h.getJSONArray("weather_code").optInt(i, -1);
            x.condition = conditionOfWmo(code);
            x.day = h.getJSONArray("is_day").optInt(i, 1) == 1;
            x.words = wordsOfWmo(code);
            x.rainMm = h.getJSONArray("precipitation").optDouble(i, 0);
            x.rainChance = h.optJSONArray("precipitation_probability") == null ? -1 : h.getJSONArray("precipitation_probability").optInt(i, -1);
            x.wind = h.getJSONArray("wind_speed_10m").optDouble(i, 0);
            x.gust = h.getJSONArray("wind_gusts_10m").optDouble(i, 0);
            if (!Double.isNaN(x.temp) && code >= 0) f.hours.add(x);
        }
        JSONObject d = o.getJSONObject("daily");
        JSONArray dates = d.getJSONArray("time");
        long today = startOfToday(tz);
        for (int i = 0; i < dates.length(); i++) {
            Day day = new Day();
            day.date = isoLocal(dates.getString(i) + "T00:00", tz);
            if (day.date < today) continue;
            int code = d.getJSONArray("weather_code").optInt(i, 3);
            day.condition = conditionOfWmo(code);
            day.words = wordsOfWmo(code);
            day.max = d.getJSONArray("temperature_2m_max").optDouble(i);
            day.min = d.getJSONArray("temperature_2m_min").optDouble(i);
            day.rainMm = d.getJSONArray("precipitation_sum").optDouble(i, 0);
            day.rainChance = d.getJSONArray("precipitation_probability_max").optInt(i, -1);
            day.wind = d.getJSONArray("wind_speed_10m_max").optDouble(i, 0);
            f.days.add(day);
        }
        return f.hours.isEmpty() ? null : f;
    }

    /** WMO weather codes, as Open-Meteo gives them. */
    private static int conditionOfWmo(int c) {
        if (c == 0 || c == 1) return CLEAR;
        if (c == 2) return PARTLY;
        if (c == 3) return CLOUDY;
        if (c == 45 || c == 48) return FOG;
        if (c == 56 || c == 57 || c == 66 || c == 67) return SLEET;
        if (c >= 71 && c <= 77 || c == 85 || c == 86) return SNOW;
        if (c >= 95) return STORM;
        if (c >= 51) return RAIN;
        return CLOUDY;
    }

    private static String wordsOfWmo(int c) {
        if (c == 0) return "Clear";
        if (c == 1) return "Nearly clear";
        if (c == 2) return "Partly cloudy";
        if (c == 3) return "Cloudy";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 55) return "Drizzle";
        if (c == 56 || c == 57 || c == 66 || c == 67) return "Freezing rain";
        if (c >= 61 && c <= 65) return c == 61 ? "Light rain" : c == 65 ? "Heavy rain" : "Rain";
        if (c >= 71 && c <= 77) return c == 71 ? "Light snow" : c == 75 ? "Heavy snow" : "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c == 85 || c == 86) return "Snow showers";
        if (c >= 95) return "Thunderstorm";
        return "";
    }

    // ---- days ----------------------------------------------------------------------------------

    /** Days from the hours: lowest and highest, rain added up, and the weather of the daytime. */
    private static void days(Forecast f, TimeZone tz) {
        Map<Long, List<Hour>> byDay = new LinkedHashMap<>();
        Calendar cal = Calendar.getInstance(tz);
        for (Hour h : f.hours) {
            cal.setTimeInMillis(h.time - 1); // a step ending at midnight belongs to the day before
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            byDay.computeIfAbsent(cal.getTimeInMillis(), k -> new ArrayList<>()).add(h);
        }
        for (Map.Entry<Long, List<Hour>> e : byDay.entrySet()) {
            Day d = new Day();
            d.date = e.getKey();
            int[] seen = new int[CONDITION.length];
            Map<Integer, String> words = new LinkedHashMap<>();
            for (Hour h : e.getValue()) {
                d.min = Math.min(d.min, h.temp);
                d.max = Math.max(d.max, h.temp);
                d.rainMm += h.rainMm;
                d.wind = Math.max(d.wind, h.wind);
                d.rainChance = Math.max(d.rainChance, h.rainChance);
                cal.setTimeInMillis(h.time);
                int hr = cal.get(Calendar.HOUR_OF_DAY);
                if (hr >= 7 && hr <= 20) { // the day's look comes from its daytime
                    seen[h.condition]++;
                    words.putIfAbsent(h.condition, h.words);
                }
            }
            if (e.getValue().size() < 2 && !f.days.isEmpty()) continue; // a sliver of a day at the end
            int best = -1;
            for (int k = 0; k < seen.length; k++) {
                if (seen[k] == 0) continue;
                if (best < 0 || seen[k] > seen[best] || seen[k] == seen[best] && SEVERITY[k] > SEVERITY[best]) best = k;
            }
            if (best < 0) best = e.getValue().get(0).condition;
            d.condition = best;
            d.words = words.containsKey(best) ? words.get(best) : e.getValue().get(0).words;
            f.days.add(d);
        }
        while (f.days.size() > 11) f.days.remove(f.days.size() - 1);
    }

    // ---- time ----------------------------------------------------------------------------------

    private static long iso(String s) throws Exception { // "2026-11-07T11:00:00Z"
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(s).getTime();
    }

    private static long isoLocal(String s, TimeZone tz) throws Exception { // "2026-11-07T11:00"
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT);
        f.setTimeZone(tz);
        return f.parse(s).getTime();
    }

    private static long startOfToday(TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Whether the sun is up at {@code time} (UTC millis) at lat, lon. */
    private static boolean daylight(double lat, double lon, long time) {
        double days = time / 86_400_000.0 + 2440587.5 - 2451545.0; // days since J2000
        double g = Math.toRadians((357.529 + 0.98560028 * days) % 360);
        double q = (280.459 + 0.98564736 * days) % 360;
        double lambda = Math.toRadians(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double eps = Math.toRadians(23.439 - 0.00000036 * days);
        double decl = Math.asin(Math.sin(eps) * Math.sin(lambda));
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
        double gmst = (18.697374558 + 24.06570982441908 * days) % 24;
        double hourAngle = Math.toRadians(gmst * 15 + lon) - ra;
        double phi = Math.toRadians(lat);
        double alt = Math.asin(Math.sin(phi) * Math.sin(decl) + Math.cos(phi) * Math.cos(decl) * Math.cos(hourAngle));
        return Math.toDegrees(alt) > -0.833;
    }
}
