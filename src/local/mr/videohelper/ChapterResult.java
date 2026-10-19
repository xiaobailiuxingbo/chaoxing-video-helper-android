package local.mr.videohelper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the platform's submitted result; merely submitting is never proof of correctness. */
public final class ChapterResult {
    public static final class Entry {
        public VideoQuiz question;
        public String answer="", correctAnswer="", score="";
        public boolean correct, wrong;
    }
    public final List<Entry> entries=new ArrayList<>();
    public String score="", chapter="";
    private static final Pattern OPTION=Pattern.compile("^([A-H])[.、．]\\s*(.+)$",Pattern.DOTALL);
    private static final Pattern SCORE=Pattern.compile("^本次得分[:：]\\s*([0-9]+(?:\\.[0-9]+)?)$");
    public static ChapterResult read(List<AutomationEngine.Node> nodes) {
        if(!ChapterQuiz.selected(nodes))return null;
        AutomationEngine.Node web=ChapterQuiz.activeWeb(nodes);if(web==null)return null;
        ChapterResult result=new ChapterResult();boolean complete=false;
        List<AutomationEngine.Node> content=new ArrayList<>();
        for(AutomationEngine.Node n:nodes) {
            if(n.visible() && n.id.equals("com.chaoxing.mobile:id/tv_chapter_name"))result.chapter=n.text;
            if(!ChapterQuiz.belongs(n,web,nodes))continue;content.add(n);
            if(n.text.equals("已完成"))complete=true;
            Matcher score=SCORE.matcher(n.text.trim());if(score.matches())result.score=score.group(1);
        }
        if(!complete || result.score.isEmpty() || ChapterQuiz.scoped(nodes,web,"form1")!=null)return null;
        List<AutomationEngine.Node> headers=new ArrayList<>();
        for(AutomationEngine.Node n:content)if(!ChapterQuiz.typeOf(n.text).isEmpty())headers.add(n);
        boolean fullScore;try{fullScore=Double.parseDouble(result.score)==100;}catch(Exception e){return null;}
        for(int i=0;i<headers.size();i++) {
            AutomationEngine.Node header=headers.get(i), title=header.parentIndex>=0?nodes.get(header.parentIndex):null;
            if(title==null)continue;
            int until=i+1<headers.size()?nodes.get(headers.get(i+1).parentIndex).index:Integer.MAX_VALUE;
            VideoQuiz q=new VideoQuiz();q.context="chapter";q.type=ChapterQuiz.typeOf(header.text);
            if(!(q.type.equals("单选题") || q.type.equals("判断题") || q.type.equals("多选题")))continue;
            StringBuilder stem=new StringBuilder();Entry entry=new Entry();entry.question=q;String mine="",correct="";boolean zero=false;
            for(AutomationEngine.Node n:content) {
                if(n.index<title.index || n.index>=until)continue;
                if(n.parentIndex==title.index) {
                    if(n==header)continue;
                    if(n.text.matches("[0-9]+[.、．]")){q.number=n.text.replaceAll("[^0-9]","");continue;}
                    if(!n.text.trim().isEmpty()){if(stem.length()>0)stem.append('\n');stem.append(n.text.trim());}
                }
                Matcher option=OPTION.matcher(n.text.trim());
                if(option.matches())q.options.add(new VideoQuiz.Option(option.group(1),option.group(2),0,0));
                String text=n.text.replaceAll("\\s+","");
                if(text.startsWith("我的答案：") || text.startsWith("我的答案:"))mine=text.substring(5);
                if(text.startsWith("正确答案：") || text.startsWith("正确答案:"))correct=text.substring(5);
                if(text.matches("得分[:：]0(?:\\.0+)?分"))zero=true;
                if(text.matches("得分[:：][0-9]+(?:\\.[0-9]+)?分"))entry.score=text.substring(3,text.length()-1);
            }
            q.stem=stem.toString();
            if(q.type.equals("判断题") && q.options.isEmpty() && (mine.equals("对") || mine.equals("错"))) {
                q.options.add(new VideoQuiz.Option("A","对",0,0));q.options.add(new VideoQuiz.Option("B","错",0,0));
            }
            if(q.number.isEmpty() || q.stem.length()<3 || q.options.size()<2)continue;
            entry.answer=label(q,mine);entry.correctAnswer=label(q,correct);
            if(entry.answer.isEmpty())continue;
            entry.correct=!zero && (fullScore || (!entry.correctAnswer.isEmpty() && entry.correctAnswer.equals(entry.answer)));
            entry.wrong=zero || (!entry.correctAnswer.isEmpty() && !entry.correctAnswer.equals(entry.answer));
            result.entries.add(entry);
        }
        return result;
    }
    private static String label(VideoQuiz q,String text) {
        if(ChoiceAnswer.multiple(q))return ChoiceAnswer.labels(q,text.replaceAll("[,，、;；\\s]+",""));
        if(q.option(text)!=null)return text;
        return QuizKey.labelForText(q,text);
    }
}
