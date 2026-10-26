package com.lumen.launcher;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/**
 * The weather next to the date on the home screen. No keys needed: the location comes from the
 * projector's internet connection (ipwho.is, ipapi.co as a backup) and the forecast from Open-Meteo.
 * Parsing is pure Java (tested on a desktop JVM).
 */
final class WeatherFeed {

    static final class Place {
        double lat, lon;
        String city = "";
    }

    static final class Now {
        int temp, high, low, code;
        boolean day = true;

        /** Glyph for the sky. */
        int glyph() {
            return glyphFor(code, day);
        }

        String sky() {
            return describe(code);
        }
    }

    private WeatherFeed() {
    }

    // ---------------------------------------------------------------- network (background thread)

    static Place locate() throws IOException {
        try {
            Place p = parsePlace(get("https://ipwho.is/?fields=success,latitude,longitude,city"));
            if (p != null) return p;
        } catch (IOException ignored) {
        }
        Place p = parsePlace(get("https://ipapi.co/json/"));
        if (p == null) throw new IOException("Couldn't find the location");
        return p;
    }

    static Now forecast(Place p) throws IOException {
        Now n = parseForecast(get(String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                        + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                        + "&timezone=auto&forecast_days=1", p.lat, p.lon)));
        if (n == null) throw new IOException("No weather data");
        return n;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Lumen-Launcher");
        try {
            if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode());
            return new String(TrendingFeed.readAll(c.getInputStream()), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- parsing (pure)

    static Place parsePlace(String json) {
        Object root = TrendingFeed.Json.parse(json);
        if (!(root instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) root;
        if (Boolean.FALSE.equals(m.get("success")) || m.get("error") != null && !Boolean.FALSE.equals(m.get("error"))) return null;
        Object la = m.get("latitude"), lo = m.get("longitude");
        if (!(la instanceof Number) || !(lo instanceof Number)) return null;
        Place p = new Place();
        p.lat = ((Number) la).doubleValue();
        p.lon = ((Number) lo).doubleValue();
        Object c = m.get("city");
        p.city = c == null ? "" : c.toString();
        return p;
    }

    static Now parseForecast(String json) {
        Object root = TrendingFeed.Json.parse(json);
        Object t = TrendingFeed.Json.path(root, "current", "temperature_2m");
        Object w = TrendingFeed.Json.path(root, "current", "weather_code");
        if (!(t instanceof Number) || !(w instanceof Number)) return null;
        Now n = new Now();
        n.temp = (int) Math.round(((Number) t).doubleValue());
        n.code = ((Number) w).intValue();
        Object d = TrendingFeed.Json.path(root, "current", "is_day");
        n.day = !(d instanceof Number) || ((Number) d).intValue() != 0;
        n.high = first(TrendingFeed.Json.path(root, "daily", "temperature_2m_max"), n.temp);
        n.low = first(TrendingFeed.Json.path(root, "daily", "temperature_2m_min"), n.temp);
        return n;
    }

    private static int first(Object list, int def) {
        if (list instanceof java.util.List && !((java.util.List<?>) list).isEmpty()) {
            Object v = ((java.util.List<?>) list).get(0);
            if (v instanceof Number) return (int) Math.round(((Number) v).doubleValue());
        }
        return def;
    }

    /** WMO weather codes (Open-Meteo). */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    static int glyphFor(int code, boolean day) {
        if (code == 0) return day ? Glyph.SUN : Glyph.MOON;
        if (code <= 2) return day ? Glyph.PARTLY : Glyph.MOON;
        if (code == 3) return Glyph.CLOUD;
        if (code == 45 || code == 48) return Glyph.FOG;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return Glyph.RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Glyph.SNOW;
        if (code >= 95) return Glyph.STORM;
        return Glyph.CLOUD;
    }
}
