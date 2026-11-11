package dev.hearth.plugin.weather;

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
 * or key). The place is the city in the TV's time zone (e.g. Europe/Stockholm), found with
 * Open-Meteo's place search, so no location or IP lookup is needed.
 *
 * Method {@code weather.forecast}: {@code {timezone}} →
 * {@code {place, source, hours[{time, temp, rainMm, rainChance, wind, gust, condition, day, words}],
 * days[{date, min, max, rainMm, rainChance, wind, condition, words}]}}.
 */
public final class WeatherPlugin extends PluginProvider {
    private static final String TAG = "Weather";
    private static final String UA = "HearthTV-Weather/1.0 (Android TV)";
    private static final long FRESH_MS = 30 * 60 * 1000;

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
                return new JSONObject().put("name", "Weather").put("api", API).put("provides", new JSONArray().put("weather"));
            case "weather.forecast":
                return forecast(request.optString("timezone", TimeZone.getDefault().getID()));
            default:
                return null;
        }
    }

    private JSONObject forecast(String zone) throws Exception {
        Forecast f = load(TimeZone.getTimeZone(zone), zone);
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

    private Forecast load(TimeZone tz, String zone) throws Exception {
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
        double lat = Double.parseDouble(ll[0]), lon = Double.parseDouble(ll[1]);
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
        if (f != null) f.place = city;
        return f;
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
