package local.mr.videohelper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AppState {
    private AppState() {}
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("video_helper", Context.MODE_PRIVATE); }
    public static QuizCache cache(Context c) {
        SharedPreferences store=c.getSharedPreferences("quiz_cache",Context.MODE_PRIVATE);
        return new QuizCache(new QuizCache.Storage(){
            public String read(String key){return store.getString(key,"");}
            public void write(String key,String value){store.edit().putString(key,value).apply();}
            public void remove(String key){store.edit().remove(key).apply();}
            public java.util.Set<String> keys(){return new java.util.HashSet<>(store.getAll().keySet());}
        });
    }
    public static void setCourse(Context c,String name) {
        String value=name.trim();
        if(value.equals(prefs(c).getString("course_name","")))return;
        if(PlaybackService.instance!=null)PlaybackService.instance.cancelQuizWork();
        prefs(c).edit().putString("course_name",value).remove("completed_title").remove("completed_at").remove("playing_chapter").apply();
        log(c,value.isEmpty()?"已清除课程分组，等待识别课程首页":"当前课程："+value);
    }
    public static void start(Context c) {
        if(PlaybackService.instance!=null)PlaybackService.instance.cancelQuizWork();
        prefs(c).edit().putBoolean("running", true).putLong("session", SystemClock.elapsedRealtime())
            .putLong("started", SystemClock.elapsedRealtime()).putInt("quiz_requests",0).remove("quiz_submitted")
            .putString("status", "已开始，等待学习通视频页").apply();
        log(c, "开始播放辅助");
    }
    public static void pause(Context c) {
        if(PlaybackService.instance!=null)PlaybackService.instance.cancelQuizWork();
        prefs(c).edit().putBoolean("running", false).putString("status", "已暂停自动操作").apply();
        log(c, "已暂停；视频按播放器当前状态继续");
    }
    public static void stop(Context c) {
        if(PlaybackService.instance!=null)PlaybackService.instance.cancelQuizWork();
        prefs(c).edit().putBoolean("running", false).putString("status", "已停止")
            .remove("completed_title").remove("completed_at").remove("playing_chapter").apply();
        log(c, "已停止自动操作");
    }
    public static void status(Context c, String s) {
        if (!s.equals(prefs(c).getString("status", ""))) prefs(c).edit().putString("status", s).apply();
    }
    public static void log(Context c, String s) {
        String previous = prefs(c).getString("log", "");
        String entry = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date()) + "  " + s;
        String value = entry + (previous.isEmpty() ? "" : "\n" + previous);
        String[] lines = value.split("\n");
        StringBuilder trimmed = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 18); i++) {
            if (i > 0) trimmed.append('\n');
            trimmed.append(lines[i]);
        }
        prefs(c).edit().putString("log", trimmed.toString()).apply();
    }
}
