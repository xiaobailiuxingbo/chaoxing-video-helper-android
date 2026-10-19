package local.mr.videohelper;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

public final class PlaybackService extends AccessibilityService {
    public static PlaybackService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AutomationEngine engine = new AutomationEngine();
    private long session = -1, nextActionAt;
    private boolean gestureBusy;
    private int gestureFailures, unknownCount;
    private final java.util.concurrent.ExecutorService network = java.util.concurrent.Executors.newSingleThreadExecutor();
    private DeepSeekClient quizClient;
    private DeepSeekClient chapterClient;
    private ChapterRun chapterRun=new ChapterRun();
    private DeepSeekClient.Answer quizAnswer;
    private String quizFingerprint = "";
    private long quizStableAt, quizSubmittedAt, quizGeneration;
    private long quizReadFailedAt;
    private boolean quizRequestBusy, quizSelected;
    private VideoQuiz quizQuestion;
    private String quizCourse="";
    private int quizCorrections, quizTouches, quizSignal, quizResumeBaseline=-1, quizReappearances;
    private boolean quizFromCache, quizGone, quizAccepted;
    private final java.util.List<String> quizRejected=new ArrayList<>();
    private final Runnable quizTimeout = () -> {
        if (quizRequestBusy) stopWith("DeepSeek 请求超时，请检查网络后重新开始");
    };
    private final Runnable loop = new Runnable() {
        @Override public void run() {
            tick();
            handler.removeCallbacks(this);
            handler.postDelayed(this, AppState.prefs(PlaybackService.this).getBoolean("running", false) ? 700 : 1500);
        }
    };
    @Override protected void onServiceConnected() {
        instance = this;
        SharedPreferences p = AppState.prefs(this);
        if (p.getLong("started", 0) > SystemClock.elapsedRealtime()) AppState.stop(this);
        AppState.status(this, p.getBoolean("running", false) ? "服务已连接，等待学习通前台" : "服务已就绪，点击开始辅助");
        handler.post(loop);
        refreshNotification();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !"com.chaoxing.mobile".contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            String message = event.getText().toString();
            if(quizQuestion!=null && quizSubmittedAt>0) {
                int feedback=QuizFeedback.text(message);if(feedback!=0)quizSignal=feedback;
            }
            if (message.contains("教师已关闭倍速") || message.contains("任务点完成后可倍速")) {
                engine.speedDenied(AppState.prefs(this).getString("rate", "1"));
                AppState.log(this, "课程关闭了未完成视频的倍速，继续原速播放");
            }
        }
        handler.removeCallbacks(loop);
        handler.postDelayed(loop, Math.max(120, nextActionAt - SystemClock.elapsedRealtime()));
    }
    @Override public void onInterrupt() { AppState.pause(this); }
    @Override public void onDestroy() {
        cancelQuizWork(); network.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        instance = null;
        AppState.prefs(this).edit().putBoolean("running", false).apply();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(41);
        super.onDestroy();
    }
    private AutomationEngine.Config config() {
        SharedPreferences p = AppState.prefs(this);
        AutomationEngine.Config c = new AutomationEngine.Config();
        c.rate = p.getString("rate", "1"); c.consent = p.getBoolean("consent", true); c.next = p.getBoolean("next", true);
        c.chapterQuiz=p.getBoolean("chapter_quiz_enabled",true) && p.getBoolean("quiz_enabled",false);
        return c;
    }
    private void tick() {
        SharedPreferences p = AppState.prefs(this);
        if (!p.getBoolean("running", false)) { cancelQuizWork(); observeCourseWhileIdle(); return; }
        if (gestureBusy || SystemClock.elapsedRealtime() < nextActionAt) return;
        long started = p.getLong("started", 0);
        if (started <= 0 || SystemClock.elapsedRealtime() < started || SystemClock.elapsedRealtime() - started >= 21600000L) {
            stopWith("已达到六小时上限，请重新开始"); return;
        }
        if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()
                || ((KeyguardManager) getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()) {
            AppState.status(this, "等待手机解锁并打开学习通"); return;
        }
        long newSession = p.getLong("session", 0);
        if (newSession != session) {
            cancelQuizWork();
            session = newSession; engine = new AutomationEngine(); unknownCount = 0; gestureFailures = 0;
            engine.completedTitle = p.getString("completed_title", ""); engine.completedAt = p.getLong("completed_at", 0);
            engine.playingChapter=p.getString("playing_chapter","");
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (!"com.chaoxing.mobile".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            root.recycle(); AppState.status(this, "等待学习通视频页进入前台"); return;
        }
        List<AccessibilityNodeInfo> live = new ArrayList<>();
        List<AutomationEngine.Node> snapshot = new ArrayList<>();
        try {
            collect(root, live, snapshot);
            observeCourse(snapshot);
            cacheChapterResult(snapshot);
            AutomationEngine.Config c = config();
            String chapterName="";
            for(AutomationEngine.Node n:snapshot)if(n.visible() && n.id.equals("com.chaoxing.mobile:id/tv_chapter_name"))chapterName=n.text;
            if(!chapterName.isEmpty()) {
                p.edit().putString("active_chapter",chapterName).apply();
                AppState.history(this).observe(p.getString("course_name",""),chapterName);
            }
            String doneKey=p.getString("course_name","")+":"+chapterName;
            if(doneKey.equals(p.getString("chapter_quiz_done","")))c.chapterQuiz=false;
            if(!chapterName.isEmpty() && !chapterRun.chapter.isEmpty() && !chapterName.equals(chapterRun.chapter)) {
                if(chapterClient!=null)chapterClient.cancel();chapterClient=null;chapterRun=new ChapterRun();
            }
            AutomationEngine.Action a = engine.plan(snapshot, c, System.currentTimeMillis());
            AutomationEngine.Action confirmation=chapterRun.submitted && !chapterRun.confirmed?ChapterRun.confirmation(snapshot):null;
            if(confirmation!=null)a=confirmation;
            else if(a.kind.equals("chapter_quiz"))a=chapterAction(ChapterQuiz.read(snapshot),snapshot,doneKey);
            if(quizQuestion!=null && quizSubmittedAt>0) {
                int feedback=quizSignal!=0?quizSignal:QuizFeedback.detect(snapshot);
                if(feedback<0) {
                    String failure=rejectQuizAnswer();
                    if(failure!=null){stopWith(failure);return;}
                    if(!VideoQuiz.present(snapshot)) {
                        AutomationEngine.Node button=null;
                        for(AutomationEngine.Node n:snapshot)if(n.visible() && n.id.equals("com.chaoxing.mobile:id/btnOk")
                            && (n.text.equals("确定") || n.text.equals("重新作答") || n.text.equals("重试")))button=n;
                        if(button==null){stopWith("已读到弹题答错提示，但无法确认关闭按钮，请检查");return;}
                        a=new AutomationEngine.Action("quiz_feedback_close","关闭已读到的弹题答错提示");
                        a.x=(button.left+button.right)/2;a.y=(button.top+button.bottom)/2;a.gestureOnly=true;
                    }
                } else if(feedback>0) {
                    acceptQuizAnswer();quizSignal=0;
                }
            }
            if (!engine.observation.isEmpty()) AppState.log(this, engine.observation);
            AppState.status(this, a.message);
            SharedPreferences.Editor edit = p.edit().putString("video", engine.videoTitle).putString("progress", engine.progress)
                .putString("completed_title", engine.completedTitle).putLong("completed_at", engine.completedAt).putString("playing_chapter",engine.playingChapter);
            edit.apply();
            if (a.kind.equals("stop")) { stopWith(a.message); return; }
            if (a.kind.equals("video_quiz")) {
                a = quizAction(VideoQuiz.read(snapshot));
                AppState.status(this, a.message);
                if (a.kind.equals("stop")) { stopWith(a.message); return; }
            } else if (!quizFingerprint.isEmpty() && !a.kind.equals("quiz_feedback_close")) {
                if(observeQuizContinuation(snapshot))a=quizWait("弹题控件暂不可读，等待页面恢复");
            }
            if (a.kind.equals("wait")) {
                if (a.message.equals("请打开学习通课程的视频页") || a.message.equals("等待视频加载") || a.message.equals("等待控件显示")) unknownCount++;
                else unknownCount = 0;
                if (unknownCount > 60) stopWith("未找到视频控件，请重新进入视频页后开始");
                return;
            }
            unknownCount = 0;
            if (a.kind.equals("back")) {
                if (performGlobalAction(GLOBAL_ACTION_BACK)) completed(a, c);
                else failed();
                return;
            }
            if (!a.gestureOnly && !a.kind.equals("scroll_next") && !a.kind.equals("chapter_scroll") && a.nodeIndex >= 0 && a.nodeIndex < live.size()) {
                AccessibilityNodeInfo target = live.get(a.nodeIndex);
                if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    completed(a, c); return;
                }
            }
            Path path = new Path(); path.moveTo(a.x, a.y);
            boolean scrolling=a.kind.equals("scroll_next") || a.kind.equals("chapter_scroll");
            if (scrolling) path.lineTo(a.x2, a.y2);
            gestureBusy = true;
            final AutomationEngine.Action gestureAction=a;
            GestureDescription gesture = new GestureDescription.Builder().addStroke(
                new GestureDescription.StrokeDescription(path, 0, a.kind.equals("chapter_scroll")?550:scrolling?350:80)).build();
            boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { gestureBusy = false; completed(gestureAction, c); }
                @Override public void onCancelled(GestureDescription d) { gestureBusy = false; failed(); }
            }, handler);
            if (!dispatched) { gestureBusy = false; failed(); }
        } catch (RuntimeException error) {
            AppState.status(this, "页面正在切换，等待重试读取");
        } finally {
            for (AccessibilityNodeInfo n : live) n.recycle();
        }
    }
    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> live, List<AutomationEngine.Node> result) {
        if (n == null) return;
        if (live.size() >= 4000) { n.recycle(); return; }
        AutomationEngine.Node item = new AutomationEngine.Node(); item.index = live.size();
        item.text = n.getText() == null ? "" : n.getText().toString();
        item.desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString();
        item.id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
        item.type = n.getClassName() == null ? "" : n.getClassName().toString();
        item.pkg = n.getPackageName() == null ? "" : n.getPackageName().toString();
        item.clickable = n.isClickable(); item.checked = n.isChecked(); item.checkable = n.isCheckable(); item.selected = n.isSelected();
        item.onScreen = n.isVisibleToUser();
        Rect r = new Rect(); n.getBoundsInScreen(r);
        item.left = r.left; item.top = r.top; item.right = r.right; item.bottom = r.bottom;
        live.add(n); result.add(item);
        for (int i = 0; i < n.getChildCount(); i++) {
            int childIndex=live.size(); collect(n.getChild(i), live, result);
            if(result.size()>childIndex) result.get(childIndex).parentIndex=item.index;
        }
    }
    private void completed(AutomationEngine.Action a, AutomationEngine.Config c) {
        chapterRun.performed(a,SystemClock.elapsedRealtime());
        if(a.kind.equals("chapter_submit"))AppState.history(this).submitted(AppState.prefs(this).getString("course_name",""),chapterRun.chapter);
        if(a.kind.equals("quiz_submit") && quizQuestion!=null)AppState.history(this).videoResult(quizCourse,historyChapter(),quizQuestion,"已提交，等待续播");
        if (a.kind.equals("quiz_select")) quizSelected=true;
        if (a.kind.equals("quiz_submit")) {
            quizSubmittedAt=SystemClock.elapsedRealtime();
            quizTouches++;quizSignal=0;quizGone=false;quizResumeBaseline=-1;
        }
        engine.performed(a, c, System.currentTimeMillis()); gestureFailures = 0;
        nextActionAt = SystemClock.elapsedRealtime() + a.delay;
        AppState.prefs(this).edit().putString("completed_title", engine.completedTitle).putLong("completed_at", engine.completedAt).putString("playing_chapter",engine.playingChapter).apply();
        if (!a.kind.equals("reveal")) AppState.log(this, a.message);
        refreshNotification();
        handler.removeCallbacks(loop); handler.postDelayed(loop, a.delay);
    }
    private void failed() {
        nextActionAt = SystemClock.elapsedRealtime() + 1000;
        if (++gestureFailures >= 3) stopWith("屏幕操作未成功，请重新启用服务后再试");
    }
    private void stopWith(String message) {
        if(!chapterRun.chapter.isEmpty())AppState.history(this).status(AppState.prefs(this).getString("course_name",""),chapterRun.chapter,"已暂停："+message);
        cancelQuizWork();
        AppState.prefs(this).edit().putBoolean("running", false).putString("status", message).apply();
        AppState.log(this, message); refreshNotification();
        android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show();
    }
    public void cancelQuizWork() {
        quizGeneration++; handler.removeCallbacks(quizTimeout);
        if(quizClient!=null) quizClient.cancel();
        if(chapterClient!=null)chapterClient.cancel();chapterClient=null;chapterRun=new ChapterRun();
        quizClient=null; quizRequestBusy=false; quizAnswer=null; quizSelected=false;
        quizFingerprint=""; quizSubmittedAt=0; quizStableAt=0;
        quizReadFailedAt=0;
        quizQuestion=null;quizCourse="";quizCorrections=0;quizTouches=0;quizSignal=0;quizFromCache=false;quizGone=false;quizAccepted=false;quizResumeBaseline=-1;quizReappearances=0;quizRejected.clear();
    }
    private void observeCourseWhileIdle() {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return;
        if(!"com.chaoxing.mobile".contentEquals(root.getPackageName()==null?"":root.getPackageName())){root.recycle();return;}
        List<AccessibilityNodeInfo> live=new ArrayList<>();List<AutomationEngine.Node> nodes=new ArrayList<>();
        try{collect(root,live,nodes);observeCourse(nodes);cacheChapterResult(nodes);}catch(RuntimeException ignored){}
        finally{for(AccessibilityNodeInfo n:live)n.recycle();}
    }
    private void observeCourse(List<AutomationEngine.Node> nodes) {
        String name=CourseContext.detect(nodes);
        if(!name.isEmpty() && !name.equals(AppState.prefs(this).getString("course_name",""))) {
            AppState.setCourse(this,name);engine=new AutomationEngine();session=-1;
        }
    }
    private void cacheChapterResult(List<AutomationEngine.Node> nodes) {
        SharedPreferences p=AppState.prefs(this);
        String course=p.getString("course_name","");if(course.isEmpty())return;
        ChapterResult result=ChapterResult.read(nodes);if(result==null)return;
        AppState.history(this).result(course,result);
        if(!p.getBoolean("cache_enabled",true) || !p.getBoolean("chapter_quiz_enabled",true) || result.entries.isEmpty())return;
        StringBuilder identity=new StringBuilder(course).append(result.chapter).append(result.score);
        for(ChapterResult.Entry e:result.entries)identity.append(QuizKey.question(e.question)).append(e.answer).append(e.correctAnswer).append(e.correct).append(e.wrong);
        String hash=QuizKey.hash(identity.toString());if(hash.equals(p.getString("chapter_result_cached","")))return;
        int accepted=0,rejected=0;
        QuizCache cache=AppState.cache(this);
        for(ChapterResult.Entry e:result.entries) {
            if(e.wrong){cache.reject(course,e.question,e.answer);rejected++;}
            if(e.correct){cache.accept(course,e.question,e.answer);accepted++;}
            else if(!e.correctAnswer.isEmpty()){cache.accept(course,e.question,e.correctAnswer);accepted++;}
        }
        p.edit().putString("chapter_result_cached",hash).apply();
        AppState.log(this,"测验结果 "+result.score+" 分：缓存已确认正确答案 "+accepted+" 题，作废错误答案 "+rejected+" 题");
    }
    private void acceptQuizAnswer() {
        if(quizQuestion==null || quizAnswer==null || quizAccepted)return;
        quizAccepted=true;
        AppState.history(this).videoResult(quizCourse,historyChapter(),quizQuestion,"平台接受并续播（无分数）");
        if(AppState.prefs(this).getBoolean("cache_enabled",true) && !quizCourse.isEmpty()) {
            AppState.cache(this).accept(quizCourse,quizQuestion,quizAnswer.label);
            AppState.log(this,"已通过弹题，答案已保存到本课程缓存（"+quizAnswer.label+"）");
        }
    }
    private String rejectQuizAnswer() {
        if(quizQuestion==null || quizAnswer==null)return "弹题反馈无法关联到答案，请检查";
        AppState.cache(this).reject(quizCourse,quizQuestion,quizAnswer.label);
        AppState.history(this).videoResult(quizCourse,historyChapter(),quizQuestion,"平台判错");
        String rejectedText=QuizKey.normalize(quizQuestion.option(quizAnswer.label).text);
        if(!quizRejected.contains(rejectedText))quizRejected.add(rejectedText);
        if(++quizCorrections>2)return "同一道弹题已纠错两次，仍未通过，请检查题目";
        AppState.log(this,(quizFromCache?"缓存答案被平台判错":"模型答案被平台判错")+"，已作废，重新请求 DeepSeek（纠错 "+quizCorrections+"/2）");
        quizAnswer=null;quizSelected=false;quizSubmittedAt=0;quizTouches=0;quizSignal=0;quizFromCache=false;quizAccepted=false;quizResumeBaseline=-1;
        return null;
    }
    private boolean observeQuizContinuation(List<AutomationEngine.Node> nodes) {
        if(quizQuestion==null)return false;
        if(quizSubmittedAt==0) {
            for(AutomationEngine.Node n:nodes)if(n.visible() && n.id.equals("com.chaoxing.mobile:id/event_window"))return true;
            if(quizCorrections==0)cancelQuizWork();
            return false;
        }
        quizGone=true;
        int current=-1,total=0;boolean chapter=false;
        for(AutomationEngine.Node n:nodes) {
            if(n.id.equals("com.chaoxing.mobile:id/land_current_time") && !n.text.isEmpty())current=AutomationEngine.seconds(n.text);
            if(n.id.equals("com.chaoxing.mobile:id/land_total_time"))total=AutomationEngine.seconds(n.text);
            if(n.id.equals("com.chaoxing.mobile:id/tv_chapter_name"))chapter=true;
        }
        if(quizResumeBaseline<0 && current>=0)quizResumeBaseline=current;
        if((current>quizResumeBaseline && quizResumeBaseline>=0) || (current>=total && total>0)) {
            acceptQuizAnswer();cancelQuizWork();
        } else if(chapter)cancelQuizWork();
        return false;
    }
    private AutomationEngine.Action quizWait(String message) { return new AutomationEngine.Action("wait",message); }
    private AutomationEngine.Action quizStop(String message) { return new AutomationEngine.Action("stop",message); }
    private String historyChapter(){return AppState.prefs(this).getString("active_chapter",engine.playingChapter);}
    private AutomationEngine.Action chapterAction(ChapterQuiz page,List<AutomationEngine.Node> nodes,String doneKey) {
        if(page==null)return quizWait("等待章节测验网页");
        AutomationEngine.Action action=chapterRun.plan(page,nodes,SystemClock.elapsedRealtime());
        if(action.kind.equals("chapter_done")) {
            AppState.prefs(this).edit().putString("chapter_quiz_done",doneKey).apply();
            AppState.log(this,"已确认当前章节测验完成");
            return quizWait("章节测验已完成，准备继续视频或下一节");
        }
        if(!action.kind.equals("chapter_request"))return action;
        SharedPreferences p=AppState.prefs(this);
        final VideoQuiz q=chapterRun.requested;
        String course=p.getString("course_name","");
        if(p.getBoolean("cache_enabled",true)) {
            DeepSeekClient.Answer cached=AppState.cache(this).find(course,q);
            if(cached!=null){chapterRun.answer(q,cached);AppState.history(this).answer(course,page.chapter,q,cached,"课程缓存");AppState.log(this,"命中章节测验课程缓存，第 "+q.number+" 题："+cached.label+"，不调用 API");return quizWait("已读取测验缓存，准备选择");}
        }
        if(!KeyVault.exists(this))return quizStop("章节测验需要先保存 DeepSeek API Key");
        int count=p.getInt("quiz_requests",0);if(count>=AppState.API_LIMIT)return quizStop("本次自动 API 请求已达到 "+AppState.API_LIMIT+" 次，请检查后重新开始");
        final String key;
        try{key=KeyVault.read(this);}catch(Exception e){return quizStop("无法读取本机 API Key，请重新保存");}
        String model=p.getString("quiz_model","deepseek-flash");
        if(!model.equals("deepseek-flash") && !model.equals("deepseek-v4-pro"))return quizStop("请重新选择 DeepSeek 模型");
        p.edit().putInt("quiz_requests",count+1).apply();
        final ChapterRun target=chapterRun;target.busy=true;
        final long generation=quizGeneration,requestSession=session;
        final DeepSeekClient client=new DeepSeekClient();chapterClient=client;
        final java.util.List<String> rejected=AppState.cache(this).rejected(course,q);
        AppState.log(this,"请求 DeepSeek：章节测验第 "+q.number+" 题（本次第 "+(count+1)+" 次）");refreshNotification();
        final Runnable timeout=()->{if(chapterClient==client)stopWith("章节测验 AI 请求超时，请检查网络");};
        handler.postDelayed(timeout,45000);
        network.execute(()->{
            DeepSeekClient.Answer result=null;String error=null;
            try{result=client.solve(q,key,model,rejected);}
            catch(java.io.IOException e){error="章节测验 AI 网络失败或超时";}
            catch(org.json.JSONException e){error="章节测验模型响应格式不正确";}
            catch(Exception e){error=e.getMessage()==null?"章节测验 AI 请求失败":e.getMessage();}
            final DeepSeekClient.Answer value=result;final String problem=error;
            handler.post(()->{
                handler.removeCallbacks(timeout);
                if(generation!=quizGeneration || chapterRun!=target || AppState.prefs(this).getLong("session",0)!=requestSession || !AppState.prefs(this).getBoolean("running",false))return;
                chapterClient=null;target.busy=false;
                if(problem!=null){stopWith(problem);return;}
                AppState.history(this).answer(course,page.chapter,q,value,value.uncertain?"DeepSeek 推测":"DeepSeek");
                target.answer(q,value);AppState.log(this,"DeepSeek 测验第 "+q.number+" 题建议 "+value.label+"："+value.reason);
                handler.removeCallbacks(loop);handler.post(loop);
            });
        });
        return quizWait("DeepSeek 正在判断章节测验");
    }
    private AutomationEngine.Action quizAction(VideoQuiz q) {
        SharedPreferences p=AppState.prefs(this);
        if(q==null) return quizStop("弹题页面正在变化，请本人检查");
        if(!q.error.isEmpty()) {
            if(quizReadFailedAt==0)quizReadFailedAt=SystemClock.elapsedRealtime();
            if(SystemClock.elapsedRealtime()-quizReadFailedAt<5000)return quizWait("等待弹题页面完整加载");
            return quizStop(q.error);
        }
        quizReadFailedAt=0;
        if(!p.getBoolean("quiz_enabled",false) || !KeyVault.exists(this)) return quizStop("遇到视频弹题：请在助手的 DeepSeek 设置中保存 Key 并启用");
        long now=SystemClock.elapsedRealtime();
        if(!q.fingerprint.equals(quizFingerprint)) {
            cancelQuizWork();quizFingerprint=q.fingerprint;quizStableAt=now;quizQuestion=q;
            quizCourse=p.getString("course_name","");
            return quizWait("正在确认视频弹题内容");
        }
        quizQuestion=q;
        if(quizSubmittedAt>0) {
            if(quizGone) {
                if(++quizReappearances>3)return quizStop("弹题连续重现且未观察到续播，请检查播放状态");
                quizGone=false;quizSubmittedAt=0;quizSelected=false;quizTouches=0;quizResumeBaseline=-1;
                return quizWait("相同弹题再次出现，复用本次已读取的答案");
            }
            if(now-quizSubmittedAt<=6000)return quizWait("已提交视频弹题，等待判题及播放器继续");
            if(quizTouches>=2)return quizStop("两次提交后弹题仍未关闭，且没有可读取的判题结果，请检查");
            AutomationEngine.Action retry=new AutomationEngine.Action("quiz_submit","弹题未响应，再次触摸提交按钮");
            retry.x=(q.submit.left+q.submit.right)/2;retry.y=(q.submit.top+q.submit.bottom)/2;retry.gestureOnly=true;retry.delay=1200;return retry;
        }
        if(quizRequestBusy) return quizWait("DeepSeek 正在判断视频弹题");
        if(quizAnswer==null) {
            if(now-quizStableAt<600)return quizWait("正在确认视频弹题内容");
            if(quizCorrections==0 && p.getBoolean("cache_enabled",true)) {
                quizAnswer=AppState.cache(this).find(quizCourse,q);
                if(quizAnswer!=null) {
                    AppState.history(this).answer(quizCourse,historyChapter(),q,quizAnswer,"课程缓存");
                    quizFromCache=true;AppState.log(this,"命中本课程答案缓存："+quizAnswer.label+"，不调用 API");
                    return quizWait("已读取本课程缓存，准备自动选择");
                }
            }
            int count=p.getInt("quiz_requests",0);
            if(count>=AppState.API_LIMIT) return quizStop("本次自动 API 请求已达到 "+AppState.API_LIMIT+" 次，请检查后重新开始");
            final String key;
            try{key=KeyVault.read(this);}catch(Exception e){return quizStop("无法读取本机 API Key，请在设置中重新保存");}
            String model=p.getString("quiz_model","deepseek-flash");
            if(!model.equals("deepseek-flash")&&!model.equals("deepseek-v4-pro")) return quizStop("请在设置中重新选择 DeepSeek 模型");
            p.edit().putInt("quiz_requests",count+1).apply();
            final long generation=quizGeneration, requestSession=session;
            final DeepSeekClient client=new DeepSeekClient();quizClient=client;quizRequestBusy=true;
            final java.util.List<String> rejected=new ArrayList<>(AppState.cache(this).rejected(quizCourse,q));
            for(String rejectedText:quizRejected)if(!rejected.contains(rejectedText))rejected.add(rejectedText);
            handler.postDelayed(quizTimeout,45000);
            AppState.log(this,"读取到文字单选弹题，正在请求 DeepSeek（本次第 "+(count+1)+" 题）");refreshNotification();
            network.execute(()->{
                DeepSeekClient.Answer result=null;String error=null;
                try{result=client.solve(q,key,model,rejected);}
                catch(java.net.SocketTimeoutException e){error="DeepSeek 请求超时，请检查网络后重新开始";}
                catch(org.json.JSONException e){error="模型响应格式不正确，请本人处理";}
                catch(java.io.IOException e){error="DeepSeek 网络连接失败，请检查网络后重新开始";}
                catch(Exception e){error=e.getMessage()==null?"DeepSeek 请求失败":e.getMessage();}
                final DeepSeekClient.Answer value=result;final String problem=error;
                handler.post(()->{
                    SharedPreferences current=AppState.prefs(PlaybackService.this);
                    if(generation!=quizGeneration || !current.getBoolean("running",false) || current.getLong("session",0)!=requestSession)return;
                    handler.removeCallbacks(quizTimeout);quizRequestBusy=false;quizClient=null;
                    if(problem!=null){stopWith(problem);return;}
                    quizAnswer=value;
                    AppState.history(PlaybackService.this).answer(quizCourse,historyChapter(),q,value,value.uncertain?"DeepSeek 推测":"DeepSeek");
                    AppState.log(PlaybackService.this,"DeepSeek 建议 "+value.label+"："+value.reason);
                    handler.removeCallbacks(loop);handler.post(loop);
                });
            });
            return quizWait("DeepSeek 正在判断视频弹题");
        }
        VideoQuiz.Option option=q.option(quizAnswer.label);
        if(option==null)return quizStop("页面选项已改变，停止自动答题");
        AutomationEngine.Action a;
        if(!quizSelected) {
            a=new AutomationEngine.Action("quiz_select","选择视频弹题选项 "+quizAnswer.label);
            a.x=option.x;a.y=option.y;a.delay=900;
        } else {
            if(q.submit==null || !q.submit.visible())return quizStop("弹题提交按钮不可见，请本人处理");
            a=new AutomationEngine.Action("quiz_submit","提交视频中途弹题（"+quizAnswer.label+"）");
            // This WebView reports ACTION_CLICK success without triggering its touch handler.
            // Use a physical accessibility gesture for the player quiz's submit control.
            a.x=(q.submit.left+q.submit.right)/2;a.y=(q.submit.top+q.submit.bottom)/2;a.nodeIndex=-1;a.delay=1200;
        }
        return a;
    }
    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        SharedPreferences p = AppState.prefs(this);
        out.println("connected=" + (instance != null));
        out.println("running=" + p.getBoolean("running", false));
        out.println("status=" + p.getString("status", ""));
        out.println("video=" + p.getString("video", ""));
        out.println("progress=" + p.getString("progress", ""));
        out.println("rate=" + p.getString("rate", "1"));
        out.println("quiz_enabled=" + p.getBoolean("quiz_enabled",false));
        out.println("api_key_saved=" + KeyVault.exists(this));
        out.println("quiz_requests=" + p.getInt("quiz_requests",0));
        out.println("course="+p.getString("course_name",""));
        out.println("course_cache_entries="+AppState.cache(this).count(p.getString("course_name","")));
        out.println("recent_actions:");
        out.println(p.getString("log", ""));
        if (args != null && java.util.Arrays.asList(args).contains("nodes")) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            if (!"com.chaoxing.mobile".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                root.recycle(); return;
            }
            List<AccessibilityNodeInfo> live = new ArrayList<>();
            List<AutomationEngine.Node> nodes = new ArrayList<>();
            try {
                collect(root, live, nodes);
                for (AutomationEngine.Node n : nodes) {
                    out.println(n.index + " | " + n.id + " | " + n.type + " | " + n.text.replace('\n', ' ') + " | " + n.desc.replace('\n', ' ')
                        + " | " + n.left + "," + n.top + "," + n.right + "," + n.bottom + " | click=" + n.clickable + " checked=" + n.checked + " selected=" + n.selected
                        + " parent=" + n.parentIndex + " visible=" + n.onScreen);
                }
                VideoQuiz question=VideoQuiz.read(nodes);
                if(question!=null){out.println("quiz_error="+question.error);out.println("quiz_stem="+question.stem);for(VideoQuiz.Option o:question.options)out.println("quiz_option="+o.label+":"+o.text+" tap="+o.x+","+o.y);}
                ChapterResult result=ChapterResult.read(nodes);
                if(result!=null){int hits=0;for(ChapterResult.Entry entry:result.entries)if(AppState.cache(this).find(p.getString("course_name",""),entry.question)!=null)hits++;
                    out.println("chapter_result_cache_hits="+hits+"/"+result.entries.size());}
            } finally { for (AccessibilityNodeInfo n : live) n.recycle(); }
        }
    }
    private PendingIntent command(String action, int code) {
        Intent i = new Intent(this, ControlReceiver.class).setAction("local.mr.videohelper." + action);
        return PendingIntent.getBroadcast(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    public void refreshNotification() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("playback", "播放辅助控制", NotificationManager.IMPORTANCE_LOW));
        boolean active = AppState.prefs(this).getBoolean("running", false);
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, "playback").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(active ? "视频播放辅助运行中" : "视频播放辅助待机")
            .setContentText(AppState.prefs(this).getString("status",active ? "保持学习通前台，可在此暂停或停止" : "打开助手查看状态"))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(active);
        builder.addAction(new Notification.Action.Builder(null, active ? "暂停辅助" : "继续辅助", command(active ? "PAUSE" : "START", 2)).build());
        builder.addAction(new Notification.Action.Builder(null, "停止", command("STOP", 3)).build());
        manager.notify(41, builder.build());
    }
}
