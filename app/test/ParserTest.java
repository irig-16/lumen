package com.lumen.launcher;
import java.util.*;
public class ParserTest {
  public static void main(String[] a) {
    List<CommandParser.AppRef> apps = new ArrayList<>();
    String[][] list = {{"com.teamsmart.videomanager.tv","SmartTube"},{"org.xbmc.kodi","Kodi"},{"org.videolan.vlc","VLC"},
      {"com.netflix.mediaclient","Netflix"},{"com.android.vending","Google Play Store"},{"com.mixplorer","MiXplorer"},
      {"com.android.chrome","Chrome"},{"com.android.settings","Settings"},{"x.files","File Manager"},{"x.music","Music Player"},
      {"com.spotify.tv.android","Spotify"},{"in.startv.hotstar","Hotstar"},{"x.photos","Photos"}};
    for (String[] s : list) apps.add(new CommandParser.AppRef(s[0], s[1]));
    CommandParser p = new CommandParser(apps);
    String[][] cases = {
      {"open kodi","OPEN Kodi"},{"Hey Lumen, can you open Kodi please","OPEN Kodi"},{"launch cody","OPEN Kodi"},
      {"open youtube","OPEN SmartTube"},{"YouTube kholo","OPEN SmartTube"},{"netflix","OPEN Netflix"},{"open nett flix","OPEN Netflix"},
      {"start the play store","OPEN Google Play Store"},{"go to file manager","OPEN File Manager"},{"open mixplorer","OPEN MiXplorer"},
      {"open music","OPEN Music Player"},{"play spotify","OPEN Spotify"},{"take me to hotstar","OPEN Hotstar"},
      {"play lofi hip hop","SEARCH_YOUTUBE lofi hip hop"},{"search for cat videos on youtube","SEARCH_YOUTUBE cat videos"},
      {"play interstellar trailer on youtube","SEARCH_YOUTUBE interstellar trailer"},{"search youtube for arijit singh songs","SEARCH_YOUTUBE arijit singh songs"},
      {"google weather in dubai","SEARCH_WEB weather in dubai"},{"search best pizza on google","SEARCH_WEB best pizza"},
      {"search tom and jerry","SEARCH_YOUTUBE tom and jerry"},
      {"pause","PAUSE"},{"pause the video","PAUSE"},{"resume","PLAY"},{"play","PLAY"},{"next","NEXT"},{"skip this","NEXT"},{"next one","NEXT"},
      {"previous song","PREVIOUS"},{"stop","STOP"},{"ruko","PAUSE"},
      {"volume up","VOLUME_UP"},{"turn it up","VOLUME_UP"},{"make it louder","VOLUME_UP"},{"awaaz badhao","VOLUME_UP"},
      {"turn the volume down","VOLUME_DOWN"},{"quieter please","VOLUME_DOWN"},{"awaaz kam karo","VOLUME_DOWN"},
      {"set the volume to fifty","VOLUME_SET 50"},{"volume 30","VOLUME_SET 30"},{"volume to seventy five percent","VOLUME_SET 75"},
      {"mute","MUTE"},{"unmute","UNMUTE"},
      {"screen presets","SCREEN"},{"screen settings","SCREEN"},{"picture mode","SCREEN"},{"hdr","SCREEN_PRESET hdr"},{"turn on hdr mode","SCREEN_PRESET hdr"},
      {"dolby vision","SCREEN_PRESET hdr"},{"vivid picture","SCREEN_PRESET vivid"},{"cinema picture","SCREEN_PRESET cinema"},{"movie screen mode","SCREEN_PRESET cinema"},
      {"bright room mode","SCREEN_PRESET bright"},{"standard picture","SCREEN_PRESET standard"},
      {"cinema mode","SOUND_PRESET cinema"},
      {"picture settings","DISPLAY"},{"play interstellar","SEARCH_YOUTUBE interstellar"},
      {"home theatre sound","SOUND_OUTPUT hometheatre"},{"I'm using a soundbar","SOUND_OUTPUT hometheatre"},{"home theater","SOUND_OUTPUT hometheatre"},
      {"tune for my receiver","SOUND_OUTPUT hometheatre"},{"bluetooth speaker mode","SOUND_OUTPUT portable"},{"headphones mode","SOUND_OUTPUT headphones"},
      {"projector speaker","SOUND_OUTPUT speaker"},{"detect the speakers","SOUND_OUTPUT auto"},{"open sound settings","SOUND_STUDIO"},
      {"check for updates","UPDATE"},{"update lumen","UPDATE"},{"install the update","UPDATE"},{"is there a new version","UPDATE"},
      {"software update","UPDATE"},{"update","UPDATE"},{"open updates","UNKNOWN_APP updates"},
      {"screen share","CAST"},{"share my screen","CAST"},{"share my laptop","CAST"},{"cast","CAST"},{"cast to the tv","CAST"},
      {"show my photos","CAST"},{"screen sharing","CAST"},{"mirror my phone","MIRROR"},{"screen mirroring","MIRROR"},{"airplay","MIRROR"},
      {"open miracast","MIRROR"},{"screen settings","SCREEN"},{"vivid picture","SCREEN_PRESET vivid"},
      {"sharper","DETAIL up"},{"more detail","DETAIL up"},{"make the picture sharper","DETAIL up"},{"increase sharpness","DETAIL up"},
      {"enhance the picture","DETAIL up"},{"less sharp","DETAIL down"},{"too sharp","DETAIL down"},{"reduce sharpness","DETAIL down"},
      {"detail max","DETAIL 4"},{"maximum detail","DETAIL 4"},{"crisp detail","DETAIL 2"},{"detail plus","DETAIL 3"},
      {"set sharpness to natural","DETAIL 1"},{"soft detail","DETAIL 0"},
      {"auto picture on","AUTO_PICTURE 1"},{"turn off auto picture","AUTO_PICTURE 0"},{"smart picture","AUTO_PICTURE 1"},
      {"turn on automatic picture","AUTO_PICTURE 1"},{"disable auto picture","AUTO_PICTURE 0"},
      {"compare the picture","COMPARE_PICTURE"},{"before and after","COMPARE_PICTURE"},{"show me the original picture","COMPARE_PICTURE"},
      {"continue watching","CONTINUE_WATCHING"},{"what was i watching","CONTINUE_WATCHING"},{"pick up where i left off","CONTINUE_WATCHING"},
      {"resume my show","CONTINUE_WATCHING"},{"continue","PLAY"},
      {"game mode","SCREEN_PRESET game"},{"gaming picture","SCREEN_PRESET game"},{"hdr picture","SCREEN_PRESET hdr"},
      {"natural picture","SCREEN_PRESET standard"},{"lumen imaging","SCREEN"},{"punchy picture","SCREEN_PRESET vivid"},
      {"search stranger things on netflix","SEARCH_IN_APP Netflix | stranger things"},
      {"find the boys on prime","SEARCH_IN_APP ?prime | the boys"},
      {"search for squid game in netflix","SEARCH_IN_APP Netflix | squid game"},
      {"netflix search wednesday","SEARCH_IN_APP Netflix | wednesday"},
      {"open netflix and search for money heist","SEARCH_IN_APP Netflix | money heist"},
      {"search netflix for dark","SEARCH_IN_APP Netflix | dark"},
      {"on netflix play bridgerton","SEARCH_IN_APP Netflix | bridgerton (play)"},
      {"play arijit singh on spotify","SEARCH_IN_APP Spotify | arijit singh (play)"},
      {"listen to coldplay on spotify","SEARCH_IN_APP Spotify | coldplay (play)"},
      {"watch the bear on hotstar","SEARCH_IN_APP Hotstar | the bear (play)"},
      {"watch loki on disney plus","SEARCH_IN_APP Hotstar | loki (play)"},{"watch loki on apple tv","SEARCH_IN_APP ?apple tv | loki (play)"},
      {"kodi search avatar","SEARCH_IN_APP Kodi | avatar"},
      {"youtube search cooking videos","SEARCH_IN_APP SmartTube | cooking videos"},
      {"play songs in hindi","SEARCH_YOUTUBE songs in hindi"},
      {"search tom and jerry","SEARCH_YOUTUBE tom and jerry"},
      {"play lofi hip hop","SEARCH_YOUTUBE lofi hip hop"},
      {"where can i watch interstellar","WHERE interstellar"},
      {"where is succession streaming","WHERE succession"},
      {"which app has the office","WHERE the office"},
      {"is dune on netflix","WHERE dune | netflix"},
      {"is oppenheimer on prime video","WHERE oppenheimer | prime video"},
      {"watch oppenheimer","WATCH oppenheimer"},
      {"watch netflix","OPEN Netflix"},
      {"what's popular on netflix","TRENDING nf"},
      {"what's trending on prime","TRENDING pv"},
      {"netflix trending","TRENDING nf"},
      {"fast forward","SEEK 1"},{"rewind","SEEK 0"},{"skip forward","SEEK 1"},{"go back 10 seconds","SEEK 0"},{"next episode","NEXT"},
      {"window mode","WINDOW"},{"screensaver","WINDOW"},{"start the screen saver","WINDOW"},{"ambient view","WINDOW"},{"ambient mode","BG_AMBIENT"},
      {"show me the beach","WINDOW ocean"},{"take me to the mountains","WINDOW mountains"},{"northern lights","WINDOW aurora"},
      {"coral reef","WINDOW reef"},{"city at night","WINDOW city"},{"open kodi","OPEN Kodi"},
      {"go home","HOME"},{"open wifi settings","WIFI"},{"bluetooth","BLUETOOTH"},{"pair a speaker","BLUETOOTH"},{"open settings","SETTINGS"},
      {"display settings","DISPLAY"},{"free up memory","FREE_RAM"},{"boost","FREE_RAM"},
      {"what time is it","TIME"},{"what's the date today","DATE"},{"how much ram is free","MEMORY"},{"how hot is the projector","TEMPERATURE"},{"projector temperature","TEMPERATURE"},{"is it overheating","TEMPERATURE"},{"what is the temperature","TEMPERATURE"},{"heat guard","TEMPERATURE"},
      {"add kodi to the top row","PIN_APP Kodi"},{"pin vlc","PIN_APP VLC"},{"remove netflix from favourites","UNPIN_APP Netflix"},
      {"hide photos","HIDE_APP Photos"},{"uninstall hotstar","UNINSTALL_APP Hotstar"},
      {"calm mode","MOTION_CALM"},{"turn on animations","MOTION_FULL"},{"pure black background","BG_BLACK"},{"ambient light","BG_AMBIENT"},
      {"what can i say","HELP"},
      {"cinema sound","SOUND_PRESET cinema"},{"switch to movie mode","SOUND_PRESET cinema"},{"dolby mode","SOUND_PRESET cinema"},
      {"dialogue mode","SOUND_PRESET dialogue"},{"clear voices","SOUND_PRESET dialogue"},{"music mode","SOUND_PRESET music"},
      {"turn on night mode","SOUND_PRESET night"},{"sound effects off","SOUND_PRESET off"},{"flat sound","SOUND_PRESET off"},
      {"more bass","SOUND_ADJUST bass +15"},{"boost the bass","SOUND_ADJUST bass +15"},{"less bass please","SOUND_ADJUST bass -15"},
      {"make the voices clearer","SOUND_ADJUST clarity +20"},{"I can't hear the dialogue","SOUND_ADJUST clarity +20"},
      {"wider sound","SOUND_ADJUST surround +20"},{"3D sound","SOUND_ADJUST surround +20"},{"volume boost","SOUND_ADJUST loudness +20"},
      {"open sound settings","SOUND_STUDIO"},{"equalizer","SOUND_STUDIO"},
      {"picture lab","PICTURE_LAB 0"},{"calibrate the picture","PICTURE_LAB 0"},{"help me focus","PICTURE_LAB 0"},
      {"keystone grid","PICTURE_LAB 1"},{"adjust the brightness","PICTURE_LAB 2"},{"set contrast","PICTURE_LAB 3"},
      {"sharpness test","PICTURE_LAB 4"},{"color bars","PICTURE_LAB 5"},
      {"picture settings","DISPLAY"},{"lumen sound","SOUND_PRESET lumen"},{"best sound","SOUND_PRESET lumen"},
      {"compare the sound","SOUND_AB"},{"play the intro again","INTRO"},{"what's trending","TRENDING"},{"show trending on youtube","TRENDING yt"},{"projector settings","DISPLAY"},{"sound off","MUTE"},{"open prime video","UNKNOWN_APP prime video"},{"open zxqw","UNKNOWN_APP zxqw"},{"blah blah banana","UNKNOWN"},
    };
    int pass = 0;
    for (String[] c : cases) {
      CommandParser.Command r = p.parse(c[0]);
      String got;
      if (r.type == CommandParser.Type.SEARCH_IN_APP)
        got = "SEARCH_IN_APP " + (r.app != null ? r.app.label : "?" + r.target) + " | " + r.query + (r.value == 1 ? " (play)" : "");
      else if (r.type == CommandParser.Type.WHERE_TO_WATCH)
        got = "WHERE " + r.query + (r.target.length() > 0 ? " | " + r.target : "");
      else
        got = r.type + (r.app != null ? " " + r.app.label : "") + (r.query.length()>0 && r.app==null ? " " + r.query : "")
        + (r.type == CommandParser.Type.SOUND_ADJUST ? String.format(" %+d", r.value) : (r.value>=0 ? " " + r.value : ""));
      boolean ok = got.equals(c[1]);
      if (ok) pass++; else System.out.println("FAIL  \"" + c[0] + "\"  expected [" + c[1] + "]  got [" + got + "]");
    }
    System.out.println(pass + "/" + cases.length + " passed");
  }
}
