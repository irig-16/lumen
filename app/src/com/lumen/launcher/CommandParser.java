package com.lumen.launcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a spoken or typed sentence into a launcher command.
 * Pure Java (no Android imports) so it can be unit-tested on a desktop JVM.
 *
 * Understands loose phrasing ("hey lumen can you open kodi please", "play lofi music",
 * "turn the volume to fifty"), recognition mistakes ("cody" → Kodi) and a few
 * Hinglish verbs ("youtube kholo", "awaaz badhao").
 */
final class CommandParser {

    enum Type {
        OPEN, SEARCH_YOUTUBE, SEARCH_WEB,
        PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS, STOP,
        VOLUME_UP, VOLUME_DOWN, VOLUME_SET, MUTE, UNMUTE,
        HOME, SETTINGS, WIFI, BLUETOOTH, DISPLAY, FREE_RAM,
        TIME, DATE, MEMORY, TEMPERATURE,
        PIN_APP, UNPIN_APP, HIDE_APP, UNINSTALL_APP,
        MOTION_CALM, MOTION_FULL, BG_BLACK, BG_AMBIENT,
        SOUND_PRESET, SOUND_ADJUST, SOUND_STUDIO, SOUND_AB, PICTURE_LAB, INTRO, TRENDING,
        SCREEN, SCREEN_PRESET, SOUND_OUTPUT, UPDATE, CAST, MIRROR,
        SEARCH_IN_APP, WHERE_TO_WATCH, WATCH, SEEK, WINDOW,
        DETAIL, AUTO_PICTURE, COMPARE_PICTURE, CONTINUE_WATCHING,
        HELP, UNKNOWN_APP, UNKNOWN
    }

    static final class AppRef {
        final String pkg, label;
        final String norm, key;

        AppRef(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
            this.norm = normalize(label);
            this.key = phonetic(norm);
        }
    }

    static final class Command {
        Type type = Type.UNKNOWN;
        AppRef app;          // OPEN / PIN / HIDE …
        String query = "";   // search text, or the app name we couldn't find
        int value = -1;      // volume percent
        String heard = "";
        String target = "";  // SEARCH_IN_APP: the service asked for (when it isn't installed); WHERE_TO_WATCH: "is it on <this>"

        @Override
        public String toString() {
            return type + (app != null ? " app=" + app.label : "") + (query.length() > 0 ? " q=" + query : "")
                    + (value >= 0 ? " v=" + value : "");
        }
    }

    /** Spoken names → likely package names, tried in order. */
    private static final Map<String, String[]> ALIASES = new HashMap<String, String[]>();

    static {
        String[] yt = {"com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta",
                "com.google.android.youtube.tv", "com.google.android.youtube"};
        ALIASES.put("youtube", yt);
        ALIASES.put("you tube", yt);
        ALIASES.put("smart tube", yt);
        ALIASES.put("smarttube", yt);
        ALIASES.put("netflix", new String[]{"com.netflix.ninja", "com.netflix.mediaclient"});
        String[] prime = {"com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"};
        ALIASES.put("prime", prime);
        ALIASES.put("prime video", prime);
        ALIASES.put("amazon prime", prime);
        ALIASES.put("amazon", prime);
        String[] store = {"com.android.vending"};
        ALIASES.put("play store", store);
        ALIASES.put("google play", store);
        ALIASES.put("app store", store);
        ALIASES.put("store", store);
        ALIASES.put("kodi", new String[]{"org.xbmc.kodi"});
        ALIASES.put("vlc", new String[]{"org.videolan.vlc"});
        ALIASES.put("spotify", new String[]{"com.spotify.tv.android", "com.spotify.music"});
        ALIASES.put("hotstar", new String[]{"in.startv.hotstar", "in.startv.hotstar.dplus.tv"});
        ALIASES.put("jio cinema", new String[]{"com.jio.media.ondemand", "com.jio.media.stb.ondemand"});
        String[] disney = {"com.disney.disneyplus", "in.startv.hotstar.dplus.tv", "in.startv.hotstar"};
        ALIASES.put("disney plus", disney);
        ALIASES.put("disney", disney);
        ALIASES.put("disney hotstar", disney);
        ALIASES.put("jiohotstar", new String[]{"in.startv.hotstar", "in.startv.hotstar.dplus.tv"});
        ALIASES.put("jio hotstar", new String[]{"in.startv.hotstar", "in.startv.hotstar.dplus.tv"});
        String[] apple = {"com.apple.atve.androidtv.appletv", "com.apple.atve.amazon.appletv"};
        ALIASES.put("apple tv", apple);
        ALIASES.put("apple tv plus", apple);
        ALIASES.put("youtube music", new String[]{"com.google.android.apps.youtube.music"});
        ALIASES.put("plex", new String[]{"com.plexapp.android"});
        ALIASES.put("anghami", new String[]{"com.anghami"});
        ALIASES.put("shahid", new String[]{"net.mbc.shahid", "net.mbc.shahidTV"});
    }

    /** Streaming services people name in commands, for "<service> isn't installed" replies. */
    static final String[] SERVICES = {"netflix", "prime video", "prime", "amazon prime", "amazon", "youtube", "you tube",
            "disney plus", "disney", "hotstar", "jiohotstar", "jio hotstar", "jio cinema", "apple tv", "apple tv plus", "shahid",
            "osn", "osn plus", "spotify", "anghami", "youtube music", "plex", "crunchyroll", "starzplay", "starz play", "mubi",
            "max", "hbo max", "tod", "zee5", "sonyliv", "sony liv", "voot", "twitch", "kodi", "vlc"};

    private static final String SEARCH_VERBS = "search|find|look up|look for|search for|show me|show";
    private static final Pattern P_IN_APP_1 = Pattern.compile("^(?:" + SEARCH_VERBS + ")(?: for)? (.+?) (?:on|in) (.+)$");
    private static final Pattern P_IN_APP_PLAY = Pattern.compile("^(?:play|watch|stream|put on|start|listen to) (.+?) (?:on|in|from) (.+)$");
    private static final Pattern P_IN_APP_2 = Pattern.compile("^(?:open |go to |launch |start )?(.+?) (?:and )?(?:search|find|look up|look for)(?: for)? (.+)$");
    private static final Pattern P_IN_APP_3 = Pattern.compile("^(?:search|find)(?: on| in)? (.+?) for (.+)$");
    private static final Pattern P_IN_APP_4 = Pattern.compile("^(?:on|in) (.+?) (?:search|find|play|watch)(?: for)? (.+)$");
    private static final Pattern P_WHERE_1 = Pattern.compile("^where (?:can|do|could|should|would) (?:i|we) (?:watch|stream|see|find|get) (.+)$");
    private static final Pattern P_WHERE_2 = Pattern.compile("^where (?:is|are) (.+?) (?:streaming|available|playing|showing|on)$");
    private static final Pattern P_WHERE_3 = Pattern.compile("^(?:which|what) (?:app|apps|service|services|streaming service|platform|channel) (?:has|have|is streaming|streams|shows|is showing) (.+)$");
    private static final Pattern P_WHERE_4 = Pattern.compile("^where (?:is|are) (.+?) (?:streaming|available)(?: now)?(?: in .+)?$");
    private static final Pattern P_IS_ON = Pattern.compile("^(?:is|are) (.+?) (?:on|in|streaming on|available on) (netflix|prime video|prime|amazon prime|amazon|disney plus|disney|hotstar|jiohotstar|apple tv plus|apple tv|shahid|osn plus|osn|starzplay|mubi)$");
    private static final Pattern P_POPULAR_ON = Pattern.compile(
            "^(?:what s |whats |what is |show me |show |open )?(?:the )?(?:popular|trending|new|hot|top|good)(?: shows| movies| right now| today)? (?:on|in) (netflix|prime video|prime|amazon prime|amazon|youtube|you tube)$"
                    + "|^(netflix|prime video|prime|amazon prime|youtube) (?:trending|popular|top)$");

    private static final String OPEN_VERBS =
            "open|launch|start|run|go to|goto|show me|show|switch to|take me to|bring up|load";
    private static final String HI_OPEN = "kholo|khol do|khol|chalao|chala do|lagao|laga do";

    private static final Pattern P_SEARCH_YT_1 = Pattern.compile(
            "^(?:search|find|look up|look for)(?: for)? (.+?) (?:on|in) (?:youtube|you tube|smart tube)$");
    private static final Pattern P_SEARCH_YT_2 = Pattern.compile(
            "^(?:search|find)(?: on)? (?:youtube|you tube)(?: for)? (.+)$");
    private static final Pattern P_PLAY_ON_YT = Pattern.compile(
            "^(?:play|watch|put on) (.+?) (?:on|in) (?:youtube|you tube|smart tube)$");
    private static final Pattern P_SEARCH_WEB_1 = Pattern.compile(
            "^(?:search|look up)(?: for)? (.+?) (?:on|in) (?:google|the web|web|internet|the internet|browser)$");
    private static final Pattern P_SEARCH_WEB_2 = Pattern.compile(
            "^(?:google|search (?:google|the web|web|internet)(?: for)?) (.+)$");
    private static final Pattern P_SEARCH_GENERIC = Pattern.compile(
            "^(?:search|find|look up|look for)(?: for)? (.+)$");
    private static final Pattern P_PLAY = Pattern.compile("^(?:play|watch|put on) (.+)$");
    private static final Pattern P_OPEN = Pattern.compile("^(?:" + OPEN_VERBS + ") (.+)$");
    private static final Pattern P_HI_OPEN = Pattern.compile("^(.+?) (?:" + HI_OPEN + ")$");
    private static final Pattern P_PIN = Pattern.compile(
            "^(?:add|pin|put) (.+?)(?: to (?:the )?(?:top row|favourites|favorites|dock|top))?$");
    private static final Pattern P_UNPIN = Pattern.compile(
            "^(?:remove|unpin|take) (.+?)(?: (?:from|off) (?:the )?(?:top row|favourites|favorites|dock|top))?$");
    private static final Pattern P_HIDE = Pattern.compile("^hide (.+)$");
    private static final Pattern P_UNINSTALL = Pattern.compile("^(?:uninstall|delete) (.+)$");
    private static final Pattern P_VOL_SET = Pattern.compile(
            "(?:volume|sound|awaaz|awaz)(?: level)?(?: to| at)? (\\d{1,3})(?: percent| %)?$");
    private static final Pattern P_VOL_SET2 = Pattern.compile(
            "^(?:set|turn|put|make)(?: the)? (?:volume|sound)(?: level)? (?:to|at) (\\d{1,3})(?: percent| %)?$");

    private final List<AppRef> apps;

    CommandParser(List<AppRef> apps) {
        this.apps = apps;
    }

    // ================================================================== parse

    Command parse(String raw) {
        Command c = new Command();
        c.heard = raw == null ? "" : raw.trim();
        String t = clean(raw);
        if (t.length() == 0) return c;

        // ---- help / info
        if (t.matches("help|what can (?:i|you) (?:say|do)|commands|show commands|kya bol sakte")) return set(c, Type.HELP);
        if (t.matches("(?:what(?:s| is) the )?time(?: is it)?(?: now)?|what time is it|kitne baje hai|time kya hai"))
            return set(c, Type.TIME);
        if (t.matches("(?:what(?:s| is) )?(?:the |today(?:s)? )?date(?: today)?|what day is it(?: today)?|aaj kya date hai"))
            return set(c, Type.DATE);
        if (t.matches("(?:how much )?(?:free )?(?:memory|ram)(?: is)?(?: free| left| available| status| usage)?|memory status"))
            return set(c, Type.MEMORY);
        if (t.matches("(?:what(?:s| is) )?(?:the )?(?:projector(?:s)? |cpu |device |system )?(?:temperature|temp|heat)(?: of the projector| now| status)?"
                + "|how (?:hot|warm) is (?:it|the projector|my projector|the cpu)(?: now)?|is (?:it|the projector) (?:too )?(?:hot|overheating)"
                + "|heat guard|projector garam hai"))
            return set(c, Type.TEMPERATURE);

        // ---- where to watch, popular on a service
        Matcher w;
        if ((w = P_IS_ON.matcher(t)).matches()) {
            c.query = w.group(1);
            c.target = w.group(2);
            return set(c, Type.WHERE_TO_WATCH);
        }
        if ((w = P_WHERE_1.matcher(t)).matches() || (w = P_WHERE_2.matcher(t)).matches() || (w = P_WHERE_3.matcher(t)).matches()
                || (w = P_WHERE_4.matcher(t)).matches()) {
            c.query = w.group(1).replaceFirst(" (?:on|in) (?:tv|the tv|the projector)$", "");
            return set(c, Type.WHERE_TO_WATCH);
        }
        if ((w = P_POPULAR_ON.matcher(t)).matches()) {
            String svc = w.group(1) != null ? w.group(1) : w.group(2);
            c.query = svc.startsWith("net") ? "nf" : svc.startsWith("you") ? "yt" : "pv";
            return set(c, Type.TRENDING);
        }
        // ---- seek
        if (t.matches("(?:fast )?forward|fast forward(?: it)?|skip (?:forward|ahead \\d+ seconds?)|go forward|(?:skip |go )?forward \\d+ (?:seconds?|minutes?)|jump ahead"))
            return seek(c, 1);
        if (t.matches("rewind(?: it)?|skip back(?:ward)?|go back \\d+ (?:seconds?|minutes?)|back \\d+ (?:seconds?|minutes?)|rewind \\d+ (?:seconds?|minutes?)|jump back"))
            return seek(c, -1);

        // ---- media
        if (t.matches("pause|pause it|pause (?:the )?(?:video|music|song|movie)|hold on|ruko|roko|rok do")) return set(c, Type.PAUSE);
        if (t.matches("play|resume|continue|play it|resume (?:the )?(?:video|music|song|movie)|chalu karo|phir se chalao"))
            return set(c, Type.PLAY);
        if (t.matches("stop|stop (?:it|the video|the music|playback)")) return set(c, Type.STOP);
        if (t.matches("play pause|toggle play(?:back)?|play or pause")) return set(c, Type.PLAY_PAUSE);
        if (t.matches("next|skip|next (?:video|song|track|episode|one|1)|skip (?:this|it|ahead)|agla|agla gaana"))
            return set(c, Type.NEXT);
        if (t.matches("previous|back a track|previous (?:video|song|track|episode)|last (?:video|song|track)|pichla"))
            return set(c, Type.PREVIOUS);

        // ---- volume
        Matcher m = P_VOL_SET2.matcher(t);
        if (!m.find()) m = P_VOL_SET.matcher(t);
        else m.reset();
        if (m.find()) {
            c.value = Math.max(0, Math.min(100, Integer.parseInt(m.group(1))));
            return set(c, Type.VOLUME_SET);
        }
        if (t.matches("mute|mute (?:it|the sound|the volume|audio)|sound off|silence|chup|awaaz band karo|awaz band karo"))
            return set(c, Type.MUTE);
        if (t.matches("unmute|unmute (?:it|the sound|audio)|sound on|turn (?:the )?sound (?:back )?on"))
            return set(c, Type.UNMUTE);
        if (t.matches("(?:turn (?:it |the volume |the sound )?up|(?:volume|sound) up|increase (?:the )?(?:volume|sound)"
                + "|raise (?:the )?(?:volume|sound)|louder|make it louder|more volume|(?:awaaz|awaz) (?:badhao|tez karo|zyada karo))"))
            return set(c, Type.VOLUME_UP);
        if (t.matches("(?:turn (?:it |the volume |the sound )?down|(?:volume|sound) down|decrease (?:the )?(?:volume|sound)"
                + "|lower (?:the )?(?:volume|sound)|reduce (?:the )?(?:volume|sound)|quieter|softer|make it quieter|less volume"
                + "|(?:awaaz|awaz) (?:kam karo|dheere karo|ghatao))"))
            return set(c, Type.VOLUME_DOWN);

        // ---- launcher look
        if (t.matches("(?:turn on |enable |switch to )?calm(?: mode| motion)?|reduce motion|(?:turn off|disable) (?:animations|motion)"))
            return set(c, Type.MOTION_CALM);
        if (t.matches("(?:turn on |enable |switch to )?full motion|(?:turn on|enable) (?:animations|motion)|animations on"))
            return set(c, Type.MOTION_FULL);
        if (t.matches("(?:switch to |use |turn on )?(?:pure )?black(?: background| mode)?|dark mode"))
            return set(c, Type.BG_BLACK);
        if (t.matches("(?:switch to |use |turn on )?(?:ambient|colou?r(?:ful)?)(?: light| background| mode)?"))
            return set(c, Type.BG_AMBIENT);

        // ---- Continue Watching
        if (t.matches("(?:continue|resume|keep) watching|(?:carry on|pick up) where i left(?: off)?|what was i watching"
                + "|resume (?:my |the )?(?:show|movie|series|episode|film)|continue (?:my |the )?(?:show|movie|series|episode|film)"))
            return set(c, Type.CONTINUE_WATCHING);

        // ---- Lumen Imaging: detail, auto picture, compare (before presets and Picture Lab's "sharpness test")
        if (t.matches("(?:make (?:it|the picture|the image) )?(?:sharper|crisper|clearer)|(?:more|increase(?: the)?|boost(?: the)?) (?:detail|sharpness|clarity)"
                + "|sharpen(?: the)?(?: picture| image)?|(?:enhance|improve) (?:the )?(?:detail|picture|image|clarity)|detail up")) {
            c.query = "up";
            return set(c, Type.DETAIL);
        }
        if (t.matches("(?:make (?:it|the picture|the image) )?(?:softer|smoother|less sharp)|(?:less|reduce(?: the)?|lower(?: the)?|decrease(?: the)?) (?:detail|sharpness)"
                + "|too sharp|detail down")) {
            c.query = "down";
            return set(c, Type.DETAIL);
        }
        Matcher dm = Pattern.compile("^(?:set |switch to |use )?(?:the )?(?:detail|sharpness|clarity)(?: level| mode)?(?: to)? (soft|natural|normal|crisp|detail plus|plus|high|max|maximum|off|default)$"
                + "|^(soft|natural|crisp|maximum|max) (?:detail|sharpness)$|^detail (plus|\\+)$").matcher(t);
        if (dm.matches()) {
            String dw = dm.group(1) != null ? dm.group(1) : dm.group(2) != null ? dm.group(2) : dm.group(3);
            c.value = dw.startsWith("soft") ? 0 : dw.startsWith("crisp") ? 2 : dw.startsWith("max") ? 4
                    : (dw.contains("plus") || dw.equals("high") || dw.equals("+")) ? 3 : 1;
            return set(c, Type.DETAIL);
        }
        if (t.matches("(?:turn |switch )?(?:on |off )?(?:the )?(?:auto|automatic|smart) picture(?: mode)?(?: on| off)?|(?:enable|disable) (?:the )?(?:auto|automatic|smart) picture")) {
            c.value = t.matches(".*\\boff\\b.*|disable.*") ? 0 : 1;
            return set(c, Type.AUTO_PICTURE);
        }
        if (t.matches("compare(?: the)? (?:picture|image|detail|before and after)|before and after|show (?:me )?(?:the )?original picture"))
            return set(c, Type.COMPARE_PICTURE);

        // ---- Screen presets (before sound: "cinema picture" is not "cinema sound")
        String en = "(?:switch to |use |turn on |enable |put on )?";
        String pic = "(?: picture| screen| image| display| colou?rs?| look)(?: mode| preset)?";
        if (t.matches("(?:open |show |go to )?(?:the )?(?:screen|picture|display) (?:presets?|modes?)|screen settings|screen options|lumen imaging|imaging|(?:the )?picture engine|(?:open )?imaging settings"))
            return set(c, Type.SCREEN);
        if (t.matches(en + "(?:hdr|dolby vision|hdr style|hdr like)(?: mode| look| picture| preset)?|" + en + "(?:hdr|hdr style)" + pic))
            return screen(c, "hdr");
        if (t.matches(en + "(?:vivid|dynamic|punchy)(?: mode| look| picture| preset)?|" + en + "(?:vivid|vibrant|dynamic)" + pic))
            return screen(c, "vivid");
        if (t.matches(en + "(?:game|gaming)(?: mode| picture| preset)|" + en + "(?:game|gaming)" + pic)) return screen(c, "game");
        if (t.matches(en + "(?:cinema|movie|theater|theatre|cinematic|filmmaker|dark room)" + pic)) return screen(c, "cinema");
        if (t.matches(en + "(?:bright room|daylight|day time|daytime)(?: mode| picture| preset)?|" + en + "(?:bright|brighter|brightest)" + pic))
            return screen(c, "bright");
        if (t.matches(en + "(?:standard|normal|default|reset|natural)" + pic)) return screen(c, "standard");

        // ---- Lumen Window (living scenes)
        if (t.matches("(?:open |start |turn on |show |go to )?(?:the |my )?(?:window mode|live window|lumen window|living window|window view"
                + "|screen ?saver|ambient view|scenery|relaxing (?:view|scenes?)|nature (?:view|scenes?)|window)"))
            return set(c, Type.WINDOW);
        if (t.matches("(?:show(?: me)?|take me to|go to|open|put on|switch to)? ?(?:the |a )?(?:beach|ocean|sea|mountains?|snow|rain(?:y forest)?|city(?: at night| skyline)?"
                + "|skyline|waterfall|northern lights|aurora|desert|dunes|autumn(?: forest)?|forest|coral reef|reef|underwater|aquarium|lake|sunrise)"
                + "(?: view| scene| window| scenery)?")) {
            c.query = SceneLibrary.sceneFor(t);
            if (c.query == null) c.query = "";
            return set(c, Type.WINDOW);
        }

        // ---- casting (before the picture rules: "screen share" is not a screen setting)
        if (t.matches("(?:open |start |turn on |show )?(?:the )?(?:phone |screen |iphone |android )?(?:mirroring|mirror)(?: my| the)?(?: phone| iphone| screen| mobile)?"
                + "|mirror (?:my |the )?(?:phone|iphone|mobile|screen)|(?:open |start )?(?:airplay|miracast|smart view)(?: receiver)?"))
            return set(c, Type.MIRROR);
        if (t.matches("(?:open |start |turn on |show )?(?:the )?(?:screen ?share|share (?:my |the |a )?(?:screen|laptop|computer|desktop|pc|mac))"
                + "|(?:start |open )?screen sharing|(?:open |start )?(?:lumen )?cast(?: to (?:the )?(?:tv|projector))?|(?:show|put) my (?:laptop|computer|pc|mac)(?: on the (?:tv|projector|screen))?"
                + "|(?:send|show) (?:my )?(?:photos|pictures|videos)(?: from my phone)?(?: on the (?:tv|projector))?"))
            return set(c, Type.CAST);

        // ---- updating Lumen itself
        if (t.matches("(?:check for |look for |get |install |download )?(?:the |an |any )?(?:lumen |launcher |software |app )?updates?"
                + "|update (?:lumen|the launcher|yourself|the app|now)|(?:is there|any) (?:an )?(?:new|newer) (?:version|lumen)(?: available)?"
                + "|(?:is there )?(?:a |any )?(?:new|newer) (?:version|lumen)(?: of lumen)?(?: available)?|software update")) return set(c, Type.UPDATE);

        // ---- where the sound goes (decides Sound Studio's tuning)
        String to = "(?:i m using |im using |i use |use |switch to |tune for |set (?:the )?(?:sound|output) to |sound (?:is )?(?:on|through|to) |output (?:to )?)?(?:the |my |a )?";
        if (t.matches(to + "(?:home ?theat(?:re|er)|receiver|av receiver|surround system|soundbar|sound bar|subwoofer|external speakers?|big speakers?)"
                + "(?: sound| mode| audio| output)?")) return output(c, "hometheatre");
        if (t.matches(to + "(?:bluetooth|portable|small|wireless) speaker(?: sound| mode| output)?")) return output(c, "portable");
        if (t.matches(to + "headphones?(?: sound| mode| output)?|" + to + "earphones?(?: mode)?")) return output(c, "headphones");
        if (t.matches(to + "(?:projector|built in|internal) speakers?(?: sound| mode| output)?")) return output(c, "speaker");
        if (t.matches("(?:detect|auto(?:matic)?(?: detect)?) (?:the )?(?:sound )?(?:output|speakers?)|sound output auto")) return output(c, "auto");

        // ---- Sound Studio
        if (t.matches("(?:open |show |go to )?(?:the )?(?:sound|audio) (?:studio|settings|options|menu)|equali[sz]er|open (?:the )?equali[sz]er"))
            return set(c, Type.SOUND_STUDIO);
        // ("lumen sound" arrives here as "sound": the wake-word filter already removed "lumen")
        if (t.matches(en + "(?:(?:lumen|best|smart|auto|default|balanced) )?(?:sound|audio)(?: mode)?")) return preset(c, "lumen");
        if (t.matches("compare(?: the)? (?:sound|audio)|a ?b (?:compare|test|check)|hear the difference|compare with off"))
            return set(c, Type.SOUND_AB);
        if (t.matches(en + "(?:cinema|movie|theater|theatre|cinematic|dolby)(?: sound| audio| mode| sound mode)?"))
            return preset(c, "cinema");
        if (t.matches(en + "(?:dialogue|dialog|voice|speech|news)(?: sound| audio| mode| enhancement| boost)"
                + "|clear (?:voice|voices|dialogue|dialog)|voice clarity"))
            return preset(c, "dialogue");
        if (t.matches(en + "music (?:sound|audio|mode)")) return preset(c, "music");
        if (t.matches(en + "(?:late )?night (?:sound|audio|mode)")) return preset(c, "night");
        if (t.matches("(?:sound|audio) effects? off|(?:turn off|disable|reset|switch off) (?:the )?(?:sound|audio) "
                + "(?:effects|enhancements?|studio)|flat sound|normal sound|sound (?:flat|normal)|turn off sound studio"))
            return preset(c, "off");
        if (t.matches("(?:more|boost|increase|raise|add|pump up)(?: the)? bass|bass (?:up|boost|more)|more boom"))
            return adjust(c, "bass", 15);
        if (t.matches("(?:less|reduce|decrease|lower|cut)(?: the)? bass|bass (?:down|less)|less boom"))
            return adjust(c, "bass", -15);
        if (t.matches("(?:more|boost|increase|raise)(?: the)? treble|treble up|brighter sound"))
            return adjust(c, "treble", 15);
        if (t.matches("(?:less|reduce|decrease|lower|cut)(?: the)? treble|treble down|softer highs"))
            return adjust(c, "treble", -15);
        if (t.matches("(?:more|increase|boost)(?: the)? (?:clarity|dialogue|dialog)|(?:make )?(?:the )?(?:voices|dialogue|dialog|speech) "
                + "(?:clearer|louder)|clearer (?:voices|dialogue|dialog|speech)|(?:i )?cant hear (?:the )?(?:voices|dialogue|dialog|them|what they say)"))
            return adjust(c, "clarity", 20);
        if (t.matches("(?:less|reduce)(?: the)? (?:clarity|dialogue boost)")) return adjust(c, "clarity", -20);
        if (t.matches("(?:more|increase|boost)(?: the)? surround|surround (?:on|up)|wider sound|3 ?d sound|spatial (?:sound|audio)"))
            return adjust(c, "surround", 20);
        if (t.matches("(?:less|reduce)(?: the)? surround|surround (?:off|down)")) return adjust(c, "surround", -20);
        if (t.matches("(?:volume|loudness) boost|boost (?:the )?(?:volume|loudness)|loudness (?:on|up)"))
            return adjust(c, "loudness", 20);
        if (t.matches("loudness (?:off|down)|(?:less|reduce)(?: the)? loudness")) return adjust(c, "loudness", -20);

        // ---- intro and trending
        if (t.matches("(?:play|show|replay) (?:the )?(?:intro|welcome|startup animation|party)(?: again)?")) return set(c, Type.INTRO);
        if (t.matches("(?:show |open |what s |whats |what is )?(?:the )?trending(?: on youtube| videos| now)?|(?:show )?popular (?:videos|on youtube)"
                + "|what s trending|whats trending|refresh trending")) return set(c, Type.TRENDING);

        // ---- Picture Lab
        if (t.matches("(?:open |show |start )?(?:the )?picture lab|calibrate(?: the)? (?:picture|image|screen|projector)"
                + "|(?:picture|image) calibration|(?:show )?test patterns?")) return lab(c, 0);
        if (t.matches("(?:show |open )?(?:the )?focus (?:test|chart|pattern|screen)|help me focus|adjust (?:the )?focus"))
            return lab(c, 0);
        if (t.matches("(?:show |open )?(?:the )?(?:keystone|geometry|alignment) (?:test|chart|pattern|grid)"
                + "|align (?:the )?(?:picture|image|screen)")) return lab(c, 1);
        if (t.matches("(?:show )?black level(?: test| pattern)?|(?:adjust|set|calibrate) (?:the )?brightness")) return lab(c, 2);
        if (t.matches("(?:show )?white level(?: test| pattern)?|(?:adjust|set|calibrate) (?:the )?contrast")) return lab(c, 3);
        if (t.matches("sharpness (?:test|pattern)|(?:adjust|set|calibrate) (?:the )?sharpness")) return lab(c, 4);
        if (t.matches("colou?r (?:test|bars|pattern)|gradient (?:test|pattern)")) return lab(c, 5);

        // ---- system places
        if (t.matches("(?:go )?home|go back home|home screen|main (?:menu|screen)|ghar")) return set(c, Type.HOME);
        if (t.matches("(?:open |show |go to )?(?:the )?(?:wifi|wi fi|wireless|network|internet)(?: settings)?")) return set(c, Type.WIFI);
        if (t.matches("(?:open |show |go to )?(?:the )?bluetooth(?: settings)?|pair (?:a )?(?:speaker|device|headphones|controller)"))
            return set(c, Type.BLUETOOTH);
        if (t.matches("(?:open |show |go to )?(?:the )?(?:display|picture|screen|image|projector)(?: settings| menu)?"))
            return set(c, Type.DISPLAY);
        if (t.matches("(?:open |show |go to )?(?:the )?(?:system )?settings")) return set(c, Type.SETTINGS);
        if (t.matches("(?:free|clean|clear)(?: up)?(?: the)?(?: ram| memory)?|boost(?: it)?|speed (?:it )?up|clean up|free ram"))
            return set(c, Type.FREE_RAM);

        // ---- searches
        if ((m = P_SEARCH_YT_1.matcher(t)).matches() || (m = P_SEARCH_YT_2.matcher(t)).matches()
                || (m = P_PLAY_ON_YT.matcher(t)).matches()) {
            c.query = m.group(1);
            return set(c, Type.SEARCH_YOUTUBE);
        }
        if ((m = P_SEARCH_WEB_1.matcher(t)).matches() || (m = P_SEARCH_WEB_2.matcher(t)).matches()) {
            c.query = m.group(1);
            return set(c, Type.SEARCH_WEB);
        }

        // ---- search inside an app: "search stranger things on netflix", "netflix search squid game"
        Command inApp = inAppSearch(c, t);
        if (inApp != null) return inApp;

        // ---- app management
        if ((m = P_UNINSTALL.matcher(t)).matches()) return appCmd(c, Type.UNINSTALL_APP, m.group(1));
        if ((m = P_HIDE.matcher(t)).matches()) return appCmd(c, Type.HIDE_APP, m.group(1));
        if ((m = P_UNPIN.matcher(t)).matches() && t.matches(".*(?:top row|favou?rites|dock|unpin).*"))
            return appCmd(c, Type.UNPIN_APP, m.group(1));
        if ((m = P_PIN.matcher(t)).matches() && t.matches(".*(?:top row|favou?rites|dock|^pin ).*"))
            return appCmd(c, Type.PIN_APP, m.group(1));

        // ---- open
        if ((m = P_OPEN.matcher(t)).matches()) return appCmd(c, Type.OPEN, m.group(1));
        if ((m = P_HI_OPEN.matcher(t)).matches()) return appCmd(c, Type.OPEN, m.group(1));

        // "play <something>": an app if it is one, otherwise a YouTube search;
        // "watch <title>": the streaming service that has it (looked up), otherwise YouTube
        if ((m = P_PLAY.matcher(t)).matches()) {
            AppRef a = findApp(m.group(1), 0.82);
            if (a != null) {
                c.app = a;
                return set(c, Type.OPEN);
            }
            c.query = m.group(1);
            return set(c, t.startsWith("watch ") ? Type.WATCH : Type.SEARCH_YOUTUBE);
        }
        if ((m = P_SEARCH_GENERIC.matcher(t)).matches()) {
            c.query = m.group(1);
            return set(c, Type.SEARCH_YOUTUBE);
        }

        // bare app name: "kodi", "netflix"
        AppRef a = findApp(t, 0.8);
        if (a != null) {
            c.app = a;
            return set(c, Type.OPEN);
        }
        return c;
    }

    private Command appCmd(Command c, Type type, String name) {
        AppRef a = findApp(name, 0.6);
        if (a == null) {
            c.query = name;
            return set(c, Type.UNKNOWN_APP);
        }
        c.app = a;
        return set(c, type);
    }

    private static Command preset(Command c, String key) {
        c.query = key;
        return set(c, Type.SOUND_PRESET);
    }

    private static Command adjust(Command c, String param, int delta) {
        c.query = param;
        c.value = delta;
        return set(c, Type.SOUND_ADJUST);
    }

    private static Command seek(Command c, int dir) {
        c.value = dir > 0 ? 1 : 0;
        return set(c, Type.SEEK);
    }

    /** In-app search phrasings; null if none applies (or the "app" isn't an app or a known service). */
    private Command inAppSearch(Command c, String t) {
        Matcher m;
        String q = null, app = null;
        boolean play = false;
        if ((m = P_IN_APP_PLAY.matcher(t)).matches()) {
            q = m.group(1);
            app = m.group(2);
            play = true;
        } else if ((m = P_IN_APP_1.matcher(t)).matches()) {
            q = m.group(1);
            app = m.group(2);
        } else if ((m = P_IN_APP_4.matcher(t)).matches()) {
            app = m.group(1);
            q = m.group(2);
            play = t.matches("^(?:on|in) .+? (?:play|watch) .+$");
        } else if ((m = P_IN_APP_3.matcher(t)).matches()) {
            app = m.group(1);
            q = m.group(2);
        } else if ((m = P_IN_APP_2.matcher(t)).matches()) {
            app = m.group(1);
            q = m.group(2);
        }
        if (q == null || app == null || q.trim().length() == 0) return null;
        app = app.replaceAll("^(?:the|my) ", "").replaceAll(" (?:app|application)$", "").trim();
        AppRef a = findApp(app, 0.75);
        boolean known = false;
        for (String sv : SERVICES) if (sv.equals(app)) known = true;
        if (a == null && !known) return null;     // "play songs in hindi": not an app
        c.app = a;
        c.target = app;
        c.query = q.trim();
        c.value = play ? 1 : 0;
        return set(c, Type.SEARCH_IN_APP);
    }

    private static Command output(Command c, String key) {
        c.query = key;
        return set(c, Type.SOUND_OUTPUT);
    }

    private static Command screen(Command c, String key) {
        c.query = key;
        return set(c, Type.SCREEN_PRESET);
    }

    private static Command lab(Command c, int pattern) {
        c.value = pattern;
        return set(c, Type.PICTURE_LAB);
    }

    private static Command set(Command c, Type t) {
        c.type = t;
        return c;
    }

    // ============================================================= app match

    AppRef findApp(String spoken, double threshold) {
        String q = normalize(spoken).replaceAll("^(?:the|my) ", "").replaceAll(" (?:app|application)$", "").trim();
        if (q.length() == 0) return null;

        String[] pkgs = ALIASES.get(q);
        if (pkgs != null) {
            for (String p : pkgs) {
                for (AppRef a : apps) if (a.pkg.equals(p)) return a;
            }
        }

        String qk = phonetic(q);
        AppRef best = null;
        double bestScore = 0;
        for (AppRef a : apps) {
            double s = score(q, qk, a);
            if (s > bestScore) {
                bestScore = s;
                best = a;
            }
        }
        return bestScore >= threshold ? best : null;
    }

    private static double score(String q, String qk, AppRef a) {
        if (a.norm.equals(q) || a.key.equals(qk)) return 1.0;
        if (a.norm.startsWith(q + " ") || q.startsWith(a.norm + " ")) return 0.93;
        String[] qt = q.split(" "), lt = a.norm.split(" ");
        boolean all = true;
        for (String w : qt) if (!contains(lt, w)) all = false;
        if (all) return 0.9;
        if (q.length() >= 4 && a.norm.contains(q)) return 0.86;

        // fuzzy on phonetic keys, word by word and as a whole
        String[] qkt = qk.split(" "), lkt = a.key.split(" ");
        double sum = 0;
        for (String w : qkt) {
            double b = 0;
            for (String l : lkt) b = Math.max(b, similarity(w, l));
            sum += b;
        }
        double words = sum / qkt.length;
        double whole = similarity(qk.replace(" ", ""), a.key.replace(" ", ""));
        return Math.max(words * 0.88, whole * 0.9);
    }

    private static boolean contains(String[] arr, String w) {
        for (String s : arr) if (s.equals(w)) return true;
        return false;
    }

    static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1.0;
        return 1.0 - (double) levenshtein(a, b) / max;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    // ============================================================ text utils

    /** Lowercase, words only, single spaces, number words → digits. */
    static String normalize(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT)
                .replace("'", "").replace("\u2019", "")
                .replace("%", " percent ")
                .replace("&", " and ")
                .replaceAll("[^a-z0-9 ]+", " ")
                .replaceAll("\\s+", " ").trim();
        return numbersToDigits(t);
    }

    /** normalize + strip wake words and polite filler. */
    static String clean(String s) {
        String t = normalize(s);
        t = t.replaceAll("^(?:hey|hi|ok|okay|yo) (?:lumen|lumin|loomen|human)\\b", "")
                .replaceAll("^(?:lumen|lumin) ", "");
        String[] fillers = {"please", "can you", "could you", "would you", "will you", "i want to", "i wanna",
                "i would like to", "i d like to", "lets", "let s", "for me", "now", "kindly", "just", "zara", "jaldi"};
        for (String f : fillers) t = (" " + t + " ").replace(" " + f + " ", " ").trim();
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Collapses spelling variants that speech engines produce: cody→kodi, nettflix→netflix. */
    static String phonetic(String s) {
        String t = s.replace("ph", "f").replace("ck", "k").replace("qu", "kw")
                .replace('c', 'k').replace('z', 's').replace('q', 'k').replace('w', 'v');
        t = t.replaceAll("y\\b", "i").replaceAll("ee", "i").replaceAll("oo", "u");
        t = t.replaceAll("(.)\\1+", "$1");
        return t;
    }

    private static final String[] ONES = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen",
            "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty",
            "ninety"};

    static String numbersToDigits(String t) {
        String[] w = t.split(" ");
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < w.length; i++) {
            int tens = indexOf(TENS, w[i]);
            int one = indexOf(ONES, w[i]);
            if (w[i].equals("hundred")) {
                out.add("100");
            } else if (tens >= 2) {
                int v = tens * 10;
                if (i + 1 < w.length) {
                    int o = indexOf(ONES, w[i + 1]);
                    if (o >= 1 && o <= 9) {
                        v += o;
                        i++;
                    }
                }
                out.add(String.valueOf(v));
            } else if (one >= 0) {
                out.add(String.valueOf(one));
            } else {
                out.add(w[i]);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : out) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
    }

    private static int indexOf(String[] arr, String s) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(s)) return i;
        return -1;
    }
}
