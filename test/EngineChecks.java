import local.mr.videohelper.AutomationEngine;
import local.mr.videohelper.VideoQuiz;
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
        for(int i=0;i<input.getLength();i++) {
            Element e=(Element)input.item(i); AutomationEngine.Node v=n(e.getAttribute("text"),e.getAttribute("resource-id"),e.getAttribute("bounds"));
            v.pkg=e.getAttribute("package");v.desc=e.getAttribute("content-desc");v.type=e.getAttribute("class");
            v.clickable="true".equals(e.getAttribute("clickable"));v.selected="true".equals(e.getAttribute("selected"));v.index=out.size();out.add(v);
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
        System.out.println("PASS: "+checks+" Android UI decision checks");
    }
}
