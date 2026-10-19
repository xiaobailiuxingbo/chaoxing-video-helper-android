import local.mr.videohelper.AutomationEngine;
import local.mr.videohelper.VideoQuiz;
import local.mr.videohelper.CourseContext;
import local.mr.videohelper.QuizKey;
import local.mr.videohelper.QuizFeedback;
import local.mr.videohelper.ChapterQuiz;
import local.mr.videohelper.ChapterRun;
import local.mr.videohelper.DeepSeekClient;
import local.mr.videohelper.ChapterResult;
import local.mr.videohelper.ChoiceAnswer;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class EngineChecks {
    static int checks;
    static final long NOW = 1000000L;
    static void expect(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static AutomationEngine.Node n(String text, String id, String bounds) {
        AutomationEngine.Node v = new AutomationEngine.Node(); v.text=text; v.id=id; v.pkg="com.chaoxing.mobile";
        String[] a=bounds.replaceAll("[\\[\\]]", ",").split(",+"); List<Integer> values=new ArrayList<>();
        for(String x:a) if(!x.isEmpty()) values.add(Integer.parseInt(x));
        v.left=values.get(0);v.top=values.get(1);v.right=values.get(2);v.bottom=values.get(3); return v;
    }
    static List<AutomationEngine.Node> list(AutomationEngine.Node... ns) {
        List<AutomationEngine.Node> all=new ArrayList<>();for(AutomationEngine.Node n:ns){n.index=all.size();all.add(n);}return all;
    }
    static List<AutomationEngine.Node> fixture(String path) throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        NodeList input=f.newDocumentBuilder().parse(new File(path)).getElementsByTagName("node");
        List<AutomationEngine.Node> out=new ArrayList<>();
        java.util.Map<org.w3c.dom.Node,Integer> parents=new java.util.HashMap<>();
        for(int i=0;i<input.getLength();i++) {
            Element e=(Element)input.item(i); AutomationEngine.Node v=n(e.getAttribute("text"),e.getAttribute("resource-id"),e.getAttribute("bounds"));
            v.pkg=e.getAttribute("package");v.desc=e.getAttribute("content-desc");v.type=e.getAttribute("class");
            v.clickable="true".equals(e.getAttribute("clickable"));v.selected="true".equals(e.getAttribute("selected"));v.index=out.size();
            v.parentIndex=parents.getOrDefault(e.getParentNode(),-1);parents.put(e,v.index);out.add(v);
        }
        return out;
    }
    static List<AutomationEngine.Node> player(String current,String total) {
        AutomationEngine.Node speed=n("倍速","com.chaoxing.mobile:id/speed","[2540,1062][2730,1203]");speed.clickable=true;
        return list(n("","com.chaoxing.mobile:id/surface_container","[142,0][2800,1224]"),n("sample","com.chaoxing.mobile:id/title","[363,146][2306,217]"),
            n(current,"com.chaoxing.mobile:id/land_current_time","[247,975][338,1032]"),n(total,"com.chaoxing.mobile:id/land_total_time","[2596,975][2695,1032]"),speed);
    }
    static List<AutomationEngine.Node> quizFixture(String path) throws Exception {
        List<AutomationEngine.Node> result=new ArrayList<>();
        for(String line:java.nio.file.Files.readAllLines(java.nio.file.Paths.get(path),java.nio.charset.StandardCharsets.UTF_8)) {
            String[] cells=line.trim().split(" \\| ",-1);
            if(cells.length!=7 || !cells[0].matches("[0-9]+"))continue;
            String[] b=cells[5].split(",");
            AutomationEngine.Node v=n(cells[3],cells[1],"["+b[0]+","+b[1]+"]["+b[2]+","+b[3]+"]");
            v.index=Integer.parseInt(cells[0]);v.type=cells[2];v.desc=cells[4];v.onScreen=cells[6].contains("visible=true");
            v.clickable=cells[6].contains("click=true");v.checked=cells[6].contains("checked=true");v.selected=cells[6].contains("selected=true");
            v.parentIndex=Integer.parseInt(cells[6].replaceAll(".*parent=(-?[0-9]+).*","$1"));result.add(v);
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        AutomationEngine.Config c=new AutomationEngine.Config(); AutomationEngine e=new AutomationEngine();
        expect(AutomationEngine.seconds("01:02:03")==3723,"hour timer");
        expect(AutomationEngine.seconds("09:08")==548,"zero-prefixed timer");
        expect(AutomationEngine.seconds("invalid")==0,"bad timer");
        expect(AutomationEngine.normalize("2.1、音乐_1").equals("2.1音乐"),"normalized chapter identity");
        AutomationEngine.Node other=n("重试","","[0,0][20,20]");other.pkg="example.other";
        expect(e.plan(list(other),c,NOW).kind.equals("wait"),"other app untouched");
        expect(e.plan(player("00:03","05:20"),c,NOW).kind.equals("wait"),"normal speed avoids restricted menu");
        c.rate="1.5";e=new AutomationEngine();
        AutomationEngine.Action a=e.plan(player("00:03","05:20"),c,NOW);
        expect(a.kind.equals("speed_open")&&a.x==2635&&a.y==1132,"native speed coordinates");
        e.performed(a,c,NOW);
        expect(e.plan(list(n("1.5X","","[200,300][400,500]")),c,NOW+500).kind.equals("speed_choice"),"popup without underlying player");
        e.speedDenied(c.rate);
        e.plan(list(n("","com.chaoxing.mobile:id/surface_container","[142,0][2800,1224]")),c,NOW+1000);
        expect(!e.plan(player("00:05","05:20"),c,NOW+1500).kind.equals("speed_open"),"hidden title preserves denied-speed state");
        c.rate="1";e=new AutomationEngine();
        expect(!e.plan(player("00:00","00:00"),c,NOW).kind.equals("back"),"zero duration is not completion");
        a=e.plan(player("05:20","05:20"),c,NOW);
        expect(a.kind.equals("back"),"actual end returns to chapter");e.performed(a,c,NOW);
        expect(e.completedTitle.equals("sample"),"end state recorded locally");
        c.next=false;expect(new AutomationEngine().plan(player("05:20","05:20"),c,NOW).kind.equals("wait"),"auto-next can be disabled");c.next=true;
        List<AutomationEngine.Node> consent=list(n("","confirmOk","[164,1279][1113,1997]"),
            n("本课教师已开启学习监控，系统将在您观看视频时开启摄像头进行抓拍。","","[164,1279][1113,1647]"),
            n("请先阅读并勾选","","[346,1650][647,1703]"),n("人脸识别授权协议","faceProtocol","[346,1650][990,1766]"));
        e=new AutomationEngine();a=e.plan(consent,c,NOW);
        expect(a.kind.equals("consent_check")&&a.x==299&&a.y==1676,"known consent checkbox");
        e.performed(a,c,NOW);a=e.plan(consent,c,NOW+500);
        expect(a.kind.equals("consent_ok")&&a.x==875&&a.y==1917,"known consent confirmation");
        c.consent=false;expect(new AutomationEngine().plan(consent,c,NOW).kind.equals("stop"),"consent setting respected");c.consent=true;
        expect(new AutomationEngine().plan(list(n("人脸信息采集","","[0,0][100,100]")),c,NOW).kind.equals("stop"),"actual identity check stops");
        List<AutomationEngine.Node> retry=fixture(args[0]+"/live-retry.xml");e=new AutomationEngine();
        for(int i=0;i<3;i++){a=e.plan(retry,c,NOW+i*6000);expect(a.kind.equals("retry")&&a.x==1707&&a.y==753,"observed retry dialog "+i);e.performed(a,c,NOW+i*6000);}
        expect(e.plan(retry,c,NOW+20000).kind.equals("stop"),"retry limit");
        expect(!new AutomationEngine().plan(list(n("重试","","[0,0][100,100]")),c,NOW).kind.equals("retry"),"unscoped retry untouched");
        e=new AutomationEngine();e.completedTitle="2.2音乐制作的技术";e.completedAt=NOW-1000;
        expect(e.plan(fixture(args[0]+"/live-quiz-navigation.xml"),c,NOW).kind.equals("scroll_next"),"quiz scroll preserves answers");
        a=e.plan(fixture(args[0]+"/live-unfinished-tasks.xml"),c,NOW);
        expect(a.kind.equals("skip_tasks")&&a.x==403&&a.y==1584,"platform next-chapter option");
        expect(new AutomationEngine().plan(fixture(args[0]+"/live-unfinished-tasks.xml"),c,NOW).kind.equals("stop"),"no skip without observed video end");
        AutomationEngine.Node ch=n("2.2 音乐制作的技术","com.chaoxing.mobile:id/tv_chapter_name","[0,311][1280,502]");
        AutomationEngine.Node web=n("","","[0,653][1280,2744]");web.type="android.webkit.WebView";
        expect(new AutomationEngine().plan(list(ch,web),c,NOW).kind.equals("focus_web"),"opaque chapter webview focused");
        a=e.plan(list(ch,n("下一节","nextChapter","[973,1997][1228,2109]"),n("","writeNotes","[1018,2011][1232,2224]")),c,NOW);
        expect(a.kind.equals("next")&&a.x==995&&a.y==2053,"note overlay avoided");
        List<AutomationEngine.Node> quiz=quizFixture(args[0]+"/live-video-question.txt");
        VideoQuiz q=VideoQuiz.read(quiz);
        expect(q!=null && q.error.isEmpty(),"observed native video popup is readable");
        expect(q.stem.equals("《臭臭猫》选自哪部美剧？"),"underlying native title excluded from question");
        expect(q.options.size()==2 && q.option("B").text.equals("《老友记》"),"separate option labels and text joined");
        expect(q.option("B").x==303 && q.option("B").y==546,"radio point inside observed option row");
        expect(new AutomationEngine().plan(quiz,c,NOW).kind.equals("video_quiz"),"video question takes priority over player speed and reveal");
        String fingerprint=q.fingerprint;
        for(AutomationEngine.Node v:quiz)if(v.text.equals("《老友记》"))v.text="different option";
        expect(!VideoQuiz.read(quiz).fingerprint.equals(fingerprint),"changed option invalidates pending answer");
        quiz=quizFixture(args[0]+"/live-video-question.txt");
        for(AutomationEngine.Node v:quiz)if(v.id.equals("com.chaoxing.mobile:id/event_window"))v.onScreen=false;
        expect(!VideoQuiz.present(quiz),"hidden popup ignored");
        quiz=quizFixture(args[0]+"/live-video-question.txt");
        for(AutomationEngine.Node v:quiz)if(v.id.equals("videoquiz-submit"))v.id="chapter-submit";
        expect(VideoQuiz.read(quiz)==null,"chapter submit is never treated as video quiz");
        quiz=quizFixture(args[0]+"/live-video-question.txt");
        for(AutomationEngine.Node v:quiz)if(v.text.equals("[单选题]"))v.text="[多选题]";
        expect(!VideoQuiz.read(quiz).error.isEmpty(),"unsupported multi choice pauses");
        quiz=quizFixture(args[0]+"/live-video-question.txt");
        for(AutomationEngine.Node v:quiz)if(v.text.equals("《老友记》"))v.text="";
        expect(!VideoQuiz.read(quiz).error.isEmpty(),"missing option text pauses");
        quiz=quizFixture(args[0]+"/live-video-question.txt");
        for(AutomationEngine.Node v:quiz)if(v.text.equals("《臭臭猫》选自哪部美剧？"))v.type="android.widget.ImageView";
        expect(!VideoQuiz.read(quiz).error.isEmpty(),"media question cannot be guessed by text model");
        List<AutomationEngine.Node> chapterPlay=quizFixture(args[0]+"/live-play-button.txt");
        e=new AutomationEngine();a=e.plan(chapterPlay,c,NOW);
        expect(a.kind.equals("play") && a.gestureOnly && a.x==635 && a.y==1386,"observed web button requires actual screen touch");
        expect(!a.message.contains("开始播放当前视频"),"a dispatched click is not reported as playback success");
        e.performed(a,c,NOW);
        expect(e.plan(chapterPlay,c,NOW+1000).kind.equals("wait") && e.observation.isEmpty(),"same chapter waits for player without log spam");
        a=e.plan(chapterPlay,c,NOW+5000);
        expect(a.kind.equals("play"),"unresponsive first click can be retried after waiting");e.performed(a,c,NOW+5000);
        a=e.plan(chapterPlay,c,NOW+10000);
        expect(a.kind.equals("play"),"third bounded touch attempt");e.performed(a,c,NOW+10000);
        expect(e.plan(chapterPlay,c,NOW+15000).kind.equals("stop"),"three ineffective touches stop instead of looping");
        e=new AutomationEngine();a=e.plan(chapterPlay,c,NOW);e.performed(a,c,NOW);
        a=e.plan(player("00:10","05:20"),c,NOW+1000);
        expect(a.kind.equals("wait") && e.observation.isEmpty(),"opening player alone does not confirm playback");
        e.plan(player("00:10","05:20"),c,NOW+2000);
        expect(e.observation.isEmpty(),"stationary timer does not confirm playback");
        e.plan(player("00:11","05:20"),c,NOW+3000);
        expect(e.observation.contains("已确认视频开始播放"),"advancing native timer confirms actual playback");
        e.plan(player("00:12","05:20"),c,NOW+4000);
        expect(e.observation.isEmpty(),"playback confirmation logged once");
        e=new AutomationEngine();a=e.plan(chapterPlay,c,NOW);e.performed(a,c,NOW);
        e.plan(player("00:10","05:20"),c,NOW+1000);
        expect(e.plan(player("00:10","05:20"),c,NOW+30000).kind.equals("stop"),"player that opens but never advances stops after timeout");
        chapterPlay=quizFixture(args[0]+"/live-play-button.txt");
        for(AutomationEngine.Node v:chapterPlay)if(v.desc.equals("播放"))v.onScreen=false;
        expect(!new AutomationEngine().plan(chapterPlay,c,NOW).kind.equals("play"),"hidden play button is not clicked");
        chapterPlay=quizFixture(args[0]+"/live-play-button.txt");
        for(AutomationEngine.Node v:chapterPlay)if(v.desc.equals("播放"))v.parentIndex=-1;
        expect(!new AutomationEngine().plan(chapterPlay,c,NOW).kind.equals("play"),"unrelated native play icon is excluded");
        AutomationEngine.Node courseHeader=n("示例：课程 A","com.chaoxing.mobile:id/toolbar_tv_title","[0,100][1000,200]");
        AutomationEngine.Node coursePager=n("","com.chaoxing.mobile:id/vp_course","[0,300][1000,2000]");
        expect(CourseContext.detect(list(courseHeader,coursePager)).equals("示例：课程 A"),"course home supplies full course name");
        expect(CourseContext.detect(list(courseHeader)).isEmpty(),"a generic toolbar title is not a course name");
        expect(CourseContext.detect(quizFixture(args[0]+"/live-play-button.txt")).isEmpty(),"chapter title cannot overwrite course identity");
        expect(QuizKey.normalize("课程　A").equals("课程 A"),"cache normalizes full-width whitespace");
        quiz=quizFixture(args[0]+"/live-video-question.txt");q=VideoQuiz.read(quiz);
        String stableKey=QuizKey.question(q), exactUiKey=q.fingerprint;
        for(AutomationEngine.Node v:quiz) {
            if(v.text.equals("《老友记》"))v.text="swap";
            else if(v.text.equals("《生活大爆炸》"))v.text="《老友记》";
        }
        for(AutomationEngine.Node v:quiz)if(v.text.equals("swap"))v.text="《生活大爆炸》";
        VideoQuiz reordered=VideoQuiz.read(quiz);
        expect(QuizKey.question(reordered).equals(stableKey),"cache identity survives option reordering");
        expect(!reordered.fingerprint.equals(exactUiKey),"asynchronous UI guard still detects reordered options");
        expect(QuizKey.labelForText(reordered,"《老友记》").equals("A"),"cached answer text maps to the current label");
        quiz=quizFixture(args[0]+"/live-video-question.txt");
        for(AutomationEngine.Node v:quiz)if(v.text.equals("《臭臭猫》选自哪部美剧？"))v.text="下列回答错误的是？";
        expect(QuizFeedback.detect(quiz)==0,"question wording containing wrong-answer phrase is not feedback");
        AutomationEngine.Node wrong=n("回答错误，请重新作答","videoquiz-result","[300,700][1100,800]");wrong.type="android.widget.TextView";wrong.parentIndex=20;wrong.index=quiz.size();quiz.add(wrong);
        expect(QuizFeedback.detect(quiz)==-1,"explicit post-answer wrong feedback detected");
        expect(QuizFeedback.detect(list(n("回答错误","com.chaoxing.mobile:id/tv_subtitle","[0,0][100,100]")))==0,"video subtitle is not a quiz result");
        chapterPlay=quizFixture(args[0]+"/live-play-button.txt");e=new AutomationEngine();a=e.plan(chapterPlay,c,NOW);e.performed(a,c,NOW);
        List<AutomationEngine.Node> part=player("12:19","12:19");for(AutomationEngine.Node v:part)if(v.id.endsWith("/title"))v.text="3.1、何为音乐产业？(1)";
        a=e.plan(part,c,NOW+1000);e.performed(a,c,NOW+1000);
        expect(e.completedTitle.equals("3.1何为音乐产业？"),"completion uses source chapter rather than suffixed video title");
        expect(e.plan(chapterPlay,c,NOW+2000).kind.equals("next"),"suffixed video does not cause replay of same chapter");
        List<AutomationEngine.Node> chapterNodes=quizFixture(args[0]+"/chapter-quiz.txt");
        ChapterQuiz page=ChapterQuiz.read(chapterNodes);
        expect(page!=null && page.error.isEmpty() && page.questions.size()==4,"chapter form includes four questions with offscreen text");
        expect(page.questions.get(2).type.equals("判断题") && page.questions.get(2).option("B").text.equals("错"),"true false uses actual option text");
        expect(page.questions.get(0).context.equals("chapter"),"chapter and video cache namespaces are separate");
        expect(ChapterQuiz.scoped(chapterNodes,page.web,"nextChapter").index==154,"active next button skips hidden video tab duplicate");
        c.chapterQuiz=true;expect(new AutomationEngine().plan(chapterNodes,c,NOW).kind.equals("chapter_quiz"),"enabled chapter test takes precedence over switching back to video");c.chapterQuiz=false;
        e=new AutomationEngine();e.completedTitle=AutomationEngine.normalize(page.chapter);e.completedAt=NOW;
        a=e.plan(chapterNodes,c,NOW+500);expect(a.kind.equals("scroll_next"),"scroll targets current test tab next button");
        AutomationEngine.Node currentNext=chapterNodes.get(154);currentNext.top=2300;currentNext.bottom=2400;currentNext.onScreen=true;
        a=e.plan(chapterNodes,c,NOW+1000);expect(a.kind.equals("next") && a.gestureOnly && a.x>0,"visible active button selected instead of hidden duplicate");
        e.performed(a,c,NOW+1000);expect(e.plan(chapterNodes,c,NOW+1500).kind.equals("wait"),"next touch waits for actual page transition");
        ChapterRun run=new ChapterRun();a=run.plan(page,chapterNodes,NOW);
        expect(a.kind.equals("chapter_request") && run.requested.number.equals("1"),"chapter requests its first unanswered question");
        VideoQuiz firstQuestion=run.requested;run.answer(firstQuestion,new DeepSeekClient.Answer("B","test"));
        a=run.plan(page,chapterNodes,NOW+1000);expect(a.kind.equals("chapter_select") && a.x==136,"single choice selected using current screen location");
        run.performed(a,NOW+1000);a=run.plan(page,chapterNodes,NOW+2000);
        expect(a.kind.equals("chapter_request") && run.requested.number.equals("2"),"selected first question is not clicked again");
        run.answer(run.requested,new DeepSeekClient.Answer("A","test"));a=run.plan(page,chapterNodes,NOW+3000);
        expect(a.kind.equals("chapter_scroll") && a.y>a.y2 && a.y-a.y2<600,"partially clipped option scrolls gently into view");
        for(int i=0;i<24;i++)run.performed(a,NOW+3000+i);
        expect(run.plan(page,chapterNodes,NOW+5000).kind.equals("stop"),"unresponsive chapter scrolling is bounded");
        run=new ChapterRun();
        for(VideoQuiz question:page.questions){run.answer(question,new DeepSeekClient.Answer("A","test"));for(VideoQuiz.Option option:question.options){option.visible=true;option.y=1500;}}
        for(int i=0;i<4;i++){a=run.plan(page,chapterNodes,NOW+i*1000);expect(a.kind.equals("chapter_select"),"each known answer selected before submit "+i);run.performed(a,NOW+i*1000);}
        expect(run.plan(page,chapterNodes,NOW+4500).kind.equals("chapter_scroll"),"offscreen submit requires scrolling");
        page.submit.top=2300;page.submit.bottom=2420;page.submit.onScreen=true;
        a=run.plan(page,chapterNodes,NOW+5000);expect(a.kind.equals("chapter_submit"),"submit only after all questions selected");run.performed(a,NOW+5000);
        expect(run.plan(page,chapterNodes,NOW+5500).kind.equals("wait"),"submitted form waits for judging");
        expect(run.plan(page,chapterNodes,NOW+14000).kind.equals("stop"),"no endless submission when feedback missing");
        List<AutomationEngine.Node> confirm=list(n("确定要提交当前作业吗？","com.chaoxing.mobile:id/tvMessage","[100,100][1000,600]"),n("确定","com.chaoxing.mobile:id/btnOk","[600,600][1000,800]"));
        expect(ChapterRun.confirmation(confirm)!=null,"known submit confirmation accepted");confirm.get(0).text="是否删除课程？";
        expect(ChapterRun.confirmation(confirm)==null,"unrelated confirmation never accepted");
        chapterNodes=quizFixture(args[0]+"/chapter-quiz.txt");chapterNodes.get(93).text="[简答题]";
        ChapterQuiz mixed=ChapterQuiz.read(chapterNodes);
        expect(mixed.error.isEmpty() && mixed.questions.size()==3 && mixed.unsupported.contains("第 1 题（简答题）"),"unsupported type reports its real number without blocking readable choices");
        ChapterRun mixedRun=new ChapterRun();
        for(VideoQuiz question:mixed.questions){mixedRun.answer(question,new DeepSeekClient.Answer("A","test"));for(VideoQuiz.Option option:question.options){option.visible=true;option.y=1500;}}
        for(int i=0;i<3;i++){a=mixedRun.plan(mixed,chapterNodes,NOW+i*1000);expect(a.kind.equals("chapter_select"),"supported part of mixed form proceeds "+i);mixedRun.performed(a,NOW+i*1000);}
        a=mixedRun.plan(mixed,chapterNodes,NOW+4000);expect(a.kind.equals("stop") && a.message.contains("第 1 题（简答题）") && a.message.contains("尚未提交"),"mixed form never submits with unsupported questions missing");
        chapterNodes=quizFixture(args[0]+"/chapter-quiz.txt");chapterNodes.get(93).text="［ 单选题 ］";
        expect(ChapterQuiz.read(chapterNodes).error.isEmpty() && ChapterQuiz.read(chapterNodes).questions.get(0).type.equals("单选题"),"fullwidth brackets and spacing do not misclassify single choice");
        chapterNodes=quizFixture(args[0]+"/chapter-quiz.txt");chapterNodes.get(138).text="[多选题]";
        ChapterQuiz multi=ChapterQuiz.read(chapterNodes);
        expect(multi.error.isEmpty() && multi.questions.size()==4 && ChoiceAnswer.multiple(multi.questions.get(3)),"offscreen multiple-choice header is supported rather than blaming visible single choice");
        VideoQuiz multiQuestion=multi.questions.get(3);ChapterRun multiRun=new ChapterRun();
        for(VideoQuiz question:multi.questions){multiRun.answer(question,new DeepSeekClient.Answer(ChoiceAnswer.multiple(question)?"AB":"A","test"));for(VideoQuiz.Option option:question.options){option.visible=true;option.y=1500;}}
        multi.submit.top=2300;multi.submit.bottom=2420;multi.submit.onScreen=true;
        for(int i=0;i<3;i++){a=multiRun.plan(multi,chapterNodes,NOW+i*1000);multiRun.performed(a,NOW+i*1000);}
        a=multiRun.plan(multi,chapterNodes,NOW+4000);expect(a.kind.equals("chapter_select") && a.message.endsWith("A"),"multi selects first required label");multiRun.performed(a,NOW+4000);
        a=multiRun.plan(multi,chapterNodes,NOW+5000);expect(a.kind.equals("chapter_select") && a.message.endsWith("B"),"multi selects next label without toggling the first off");multiRun.performed(a,NOW+5000);
        expect(multiRun.plan(multi,chapterNodes,NOW+6000).kind.equals("chapter_submit"),"all multiple-choice labels selected before submission");
        ChapterRun resumed=new ChapterRun();resumed.restore(multiQuestion,"AB");
        for(VideoQuiz question:multi.questions)resumed.answer(question,new DeepSeekClient.Answer(ChoiceAnswer.multiple(question)?"A":"A","test"));
        for(int i=0;i<3;i++){a=resumed.plan(multi,chapterNodes,NOW+i*1000);resumed.performed(a,NOW+i*1000);}
        a=resumed.plan(multi,chapterNodes,NOW+4000);expect(a.kind.equals("chapter_select") && a.message.startsWith("取消") && a.message.endsWith("B"),"resumed selection removes stale label when candidate combination changes");resumed.performed(a,NOW+4000);
        expect(resumed.selection(multiQuestion).equals("A") && resumed.plan(multi,chapterNodes,NOW+5000).kind.equals("chapter_submit"),"restored partial selection avoids duplicate toggles and completes");
        ChapterRun checkedRun=new ChapterRun();
        for(VideoQuiz question:multi.questions)checkedRun.answer(question,new DeepSeekClient.Answer(ChoiceAnswer.multiple(question)?"AB":"A","test"));
        for(int i=0;i<3;i++){a=checkedRun.plan(multi,chapterNodes,NOW+i*1000);checkedRun.performed(a,NOW+i*1000);}
        multiQuestion.option("A").selectionKnown=true;multiQuestion.option("A").checked=true;
        multiQuestion.option("B").selectionKnown=true;multiQuestion.option("B").checked=false;
        a=checkedRun.plan(multi,chapterNodes,NOW+4000);expect(a.kind.equals("chapter_select") && a.message.endsWith("B"),"accessible preselected checkbox is not toggled off");checkedRun.performed(a,NOW+4000);
        multiQuestion.option("B").checked=true;expect(checkedRun.plan(multi,chapterNodes,NOW+5000).kind.equals("chapter_submit"),"accessible checked state confirms complete combination before submit");
        chapterNodes=quizFixture(args[0]+"/chapter-quiz.txt");chapterNodes.get(101).type="android.widget.ImageView";
        expect(!ChapterQuiz.read(chapterNodes).error.isEmpty(),"media options cannot be silently omitted");
        List<AutomationEngine.Node> resultNodes=quizFixture(args[0]+"/chapter-result.txt");
        ChapterResult scored=ChapterResult.read(resultNodes);
        expect(scored!=null && scored.entries.size()==4 && scored.score.equals("100"),"submitted score page contains all four answers");
        ChapterQuiz original=ChapterQuiz.read(quizFixture(args[0]+"/chapter-quiz.txt"));
        for(int i=0;i<4;i++) {
            expect(scored.entries.get(i).correct,"full score confirms each answer "+i);
            expect(QuizKey.question(scored.entries.get(i).question).equals(QuizKey.question(original.questions.get(i))),"result and editable question share stable cache key "+i);
        }
        expect(scored.entries.get(2).answer.equals("B"),"true false result text converted to cached answer text");
        for(AutomationEngine.Node node:resultNodes)if(node.text.equals("本次得分: 100"))node.text="本次得分: 75";
        expect(!ChapterResult.read(resultNodes).entries.get(0).correct,"partial total score cannot confirm individual answers");
        for(AutomationEngine.Node node:resultNodes)if(node.text.equals("得分：25.0分")){node.text="得分：0.0分";break;}
        expect(ChapterResult.read(resultNodes).entries.get(0).wrong,"explicit zero score invalidates the submitted choice");
        expect(ChapterResult.read(quizFixture(args[0]+"/chapter-quiz.txt"))==null,"an unsubmitted form cannot populate correct answer cache");
        resultNodes=quizFixture(args[0]+"/chapter-result.txt");resultNodes.get(90).text="[多选题]";resultNodes.get(101).text="我的答案：D，B";
        ChapterResult multiResult=ChapterResult.read(resultNodes);
        expect(multiResult.entries.size()==4 && multiResult.entries.get(0).answer.equals("BD") && multiResult.entries.get(0).correct,"platform multi result reads all labels despite separators and order");
        chapterNodes=quizFixture(args[0]+"/chapter-quiz.txt");chapterNodes.get(93).text="[多选题]";
        expect(QuizKey.question(multiResult.entries.get(0).question).equals(QuizKey.question(ChapterQuiz.read(chapterNodes).questions.get(0))),"multi result has same cache identity as editable question");
        ChapterQuiz liveMixed=ChapterQuiz.read(quizFixture(args[0]+"/chapter-mixed-types.txt"));
        expect(liveMixed.error.isEmpty() && liveMixed.unsupported.isEmpty() && liveMixed.questions.size()==5,"observed mixed layout now reads all three single and two multiple choice questions");
        expect(liveMixed.questions.get(1).option("C").text.equals("丙二"),"nested option text in observed layout remains intact");
        expect(liveMixed.questions.get(3).type.equals("多选题") && liveMixed.questions.get(4).type.equals("多选题"),"offscreen multi questions do not become a visible single-choice error");
        AutomationEngine.Action unsaved=new AutomationEngine().plan(list(n("本节测验还未提交，请先保存或提交","com.chaoxing.mobile:id/tvMessage","[100,100][1000,600]")),c,NOW);
        expect(unsaved.kind.equals("stop") && unsaved.message.contains("尚未保存或提交"),"real unsaved-test block is distinguished from a missing next button");
        System.out.println("PASS: "+checks+" Android UI decision checks");
    }
}
