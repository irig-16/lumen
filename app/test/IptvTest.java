package com.lumen.launcher;
import java.util.*;
public class IptvTest {
  static int pass, fail;
  static void eq(Object got, Object want, String what) { if (String.valueOf(got).equals(String.valueOf(want))) pass++; else { fail++; System.out.println("FAIL " + what + ": got [" + got + "] want [" + want + "]"); } }
  public static void main(String[] a) throws Exception {
    String m3u = "#EXTM3U x-tvg-url=\"https://x\"\n"
      + "#EXTINF:-1 tvg-id=\"AjmanTV.ae@SD\" tvg-logo=\"https://i.imgur.com/a.png\" group-title=\"General\",Ajman TV (1080p)\n"
      + "https://cdn1.example/ajman/playlist.m3u8\n"
      + "#EXTINF:-1 tvg-id=\"Alarabiya.ae@SD\" tvg-logo=\"https://i.imgur.com/b.png\" group-title=\"News\",Al Arabiya (1080p)\n"
      + "https://live.example/alarabiya.m3u8\n"
      + "#EXTINF:-1 tvg-id=\"Alarabiya.ae@SD\" tvg-logo=\"\" group-title=\"News\",Al Arabiya (720p)\n"
      + "http://185.9.2.18/chid_146/index.m3u8\n"
      + "#EXTINF:-1 tvg-id=\"ZeeNews.in@SD\" tvg-logo=\"https://z.png\" group-title=\"News;General\",Zee News (576p) [Geo-blocked]\n"
      + "#EXTVLCOPT:http-referrer=https://zee.example/\n"
      + "#EXTVLCOPT:http-user-agent=Mozilla/5.0\n"
      + "https://zee.example/live.m3u8\n"
      + "#EXTINF:-1 tvg-id=\"\" group-title=\"XXX\",Adult thing\n"
      + "https://bad.example/x.m3u8\n"
      + "#EXTINF:-1 tvg-id=\"SunTV.in@HD\" group-title=\"Entertainment\",Sun TV, Tamil [Not 24/7]\n"
      + "https://sun.example/live.m3u8\n"
      + "#EXTINF:-1 tvg-id=\"Weird\" group-title=\"Undefined\",Weird Channel\n"
      + "ftp://nope/x\n"
      + "#EXTINF:-1 tvg-id=\"CNN.us@SD\" group-title=\"News\",CNN International\n"
      + "https://cnn.example/i.m3u8\n";
    List<IptvFeed.Channel> l = IptvFeed.parse(m3u);
    eq(l.size(), 5, "count (adult + ftp dropped, duplicate merged)");
    IptvFeed.Channel aj = l.get(0);
    eq(aj.name, "Ajman TV", "name"); eq(aj.quality, "1080p", "quality"); eq(aj.country, "ae", "country"); eq(aj.logo, "https://i.imgur.com/a.png", "logo"); eq(aj.groups, "[General]", "groups");
    IptvFeed.Channel ar = l.get(1);
    eq(ar.alts.size(), 1, "alternate link merged"); eq(ar.alts.get(0).url, "http://185.9.2.18/chid_146/index.m3u8", "alt url");
    IptvFeed.Channel z = l.get(2);
    eq(z.name, "Zee News", "zee name"); eq(z.geoBlocked, true, "geo"); eq(z.referrer, "https://zee.example/", "referrer"); eq(z.userAgent, "Mozilla/5.0", "ua"); eq(z.groups, "[News, General]", "two groups"); eq(z.country, "in", "in");
    IptvFeed.Channel sun = l.get(3);
    eq(sun.name, "Sun TV, Tamil", "comma in name"); eq(sun.notAlways, true, "not 24/7"); eq(sun.feed, "HD", "feed");
    eq(IptvFeed.categories(l).get(0), "News", "top category");
    eq(IptvFeed.countries(l).keySet().iterator().next(), "ae", "top country");
    eq(IptvFeed.inCountry(l, "in").size(), 2, "india");
    eq(IptvFeed.search(l, "cnn").get(0).name, "CNN International", "search cnn");
    eq(IptvFeed.search(l, "zee news").get(0).name, "Zee News", "search zee");
    eq(IptvFeed.search(l, "sun").get(0).name, "Sun TV, Tamil", "search sun");
    eq(IptvFeed.countryOf("Foo.bar.in@SD"), "in", "dotted id"); eq(IptvFeed.countryOf("NoCountry"), "", "no cc");
    System.out.println(pass + "/" + (pass + fail) + " passed");
  }
}
