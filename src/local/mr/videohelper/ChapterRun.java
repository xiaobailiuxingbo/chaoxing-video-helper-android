package local.mr.videohelper;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

/** Bounded chapter-test UI flow. Network and persistence are supplied by the service. */
public final class ChapterRun {
    public final Map<String,DeepSeekClient.Answer> answers=new LinkedHashMap<>();
    public final Map<String,VideoQuiz> questions=new LinkedHashMap<>();
    private final Set<String> selected=new HashSet<>();
    private final Map<String,Set<String>> multiSelections=new LinkedHashMap<>();
    private String pendingLabel="";
    private boolean pendingSelected;
    public String scope="";
    public VideoQuiz requested;
    public String chapter="", identity="";
    public boolean busy, submitted, confirmed;
    private int scrolls, touches;
    private long submittedAt, failedReadAt;
    public AutomationEngine.Action plan(ChapterQuiz page,List<AutomationEngine.Node> nodes,long now) {
        requested=null;
        if(!page.error.isEmpty()) {
            if(failedReadAt==0)failedReadAt=now;
            return action(now-failedReadAt<5000?"wait":"stop",page.error);
        }
        failedReadAt=0;
        if(page.completed)return action("chapter_done","已读取到章节测验完成状态");
        StringBuilder current=new StringBuilder(page.chapter).append(':').append(page.unsupported);
        for(VideoQuiz q:page.questions)current.append(':').append(q.number).append(':').append(q.fingerprint);
        if(identity.isEmpty()){identity=current.toString();chapter=page.chapter;}
        if(!identity.equals(current.toString()))return action("stop","章节测验内容已改变，请重新开始辅助");
        if(submitted) {
            if(now-submittedAt<8000)return action("wait","已提交章节测验，等待判题结果");
            return action("stop","章节测验提交后未读取到结果，请检查页面");
        }
        for(VideoQuiz q:page.questions) {
            String key=q.number+":"+q.fingerprint;
            questions.put(key,q);
            if(selected.contains(key))continue;
            DeepSeekClient.Answer answer=answers.get(key);
            if(answer==null) {
                if(busy)return action("wait","DeepSeek 正在判断章节测验");
                requested=q;
                return action("chapter_request","读取章节测验第 "+q.number+" 题");
            }
            String labels=ChoiceAnswer.labels(q,answer.label);if(labels.isEmpty())return action("stop","章节测验选项已改变");
            VideoQuiz.Option option=null;
            if(ChoiceAnswer.multiple(q)) {
                Set<String> picked=multiSelections.computeIfAbsent(key,k->new HashSet<>());
                for(VideoQuiz.Option o:q.options)if(o.selectionKnown){if(o.checked)picked.add(o.label);else picked.remove(o.label);}
                for(VideoQuiz.Option o:q.options)if(picked.contains(o.label)!=(labels.contains(o.label))){option=o;break;}
                if(option==null){selected.add(key);continue;}
                pendingSelected=labels.contains(option.label);
            } else option=q.option(labels);
            if(!option.visible)return scroll(page,option.node!=null && option.node.top<page.web.top+20);
            AutomationEngine.Action a=action("chapter_select",(ChoiceAnswer.multiple(q)&&!pendingSelected?"取消":"选择")+"章节测验第 "+q.number+" 题选项 "+option.label);
            a.x=option.x;a.y=option.y;a.nodeIndex=-1;a.gestureOnly=true;a.delay=800;requested=q;pendingLabel=option.label;return a;
        }
        if(!page.unsupported.isEmpty())return action("stop",page.unsupportedMessage());
        if(page.submit==null)return action("stop","没有找到章节测验提交按钮");
        if(!page.submit.visible() || page.submit.bottom-page.submit.top<50)return scroll(page,false);
        if(touches>=1)return action("stop","章节测验提交未生效，请检查页面");
        AutomationEngine.Action a=action("chapter_submit","提交已选择完成的章节测验");
        a.x=(page.submit.left+page.submit.right)/2;a.y=(page.submit.top+page.submit.bottom)/2;a.gestureOnly=true;a.delay=1500;return a;
    }
    public void answer(VideoQuiz q,DeepSeekClient.Answer answer) {answers.put(q.number+":"+q.fingerprint,answer);busy=false;}
    public void performed(AutomationEngine.Action a,long now) {
        if(a.kind.equals("chapter_select") && requested!=null){
            String key=requested.number+":"+requested.fingerprint;
            if(ChoiceAnswer.multiple(requested)) {
                Set<String> current=multiSelections.computeIfAbsent(key,k->new HashSet<>());
                if(pendingSelected)current.add(pendingLabel);else current.remove(pendingLabel);
            } else selected.add(key);
            scrolls=0;
        }
        if(a.kind.equals("chapter_submit")){submitted=true;submittedAt=now;touches++;}
        if(a.kind.equals("chapter_confirm")){confirmed=true;submittedAt=now;}
        if(a.kind.equals("chapter_scroll"))scrolls++;
    }
    public void scope(String value){if(!scope.equals(value)){scope=value;multiSelections.clear();selected.clear();}}
    public void restore(VideoQuiz q,String labels) {
        String key=q.number+":"+q.fingerprint;if(multiSelections.containsKey(key))return;
        Set<String> values=new HashSet<>();for(char c:labels.toCharArray())if(q.option(String.valueOf(c))!=null)values.add(String.valueOf(c));multiSelections.put(key,values);
    }
    public String selection(VideoQuiz q) {
        Set<String> current=multiSelections.get(q.number+":"+q.fingerprint);StringBuilder out=new StringBuilder();
        if(current!=null)for(VideoQuiz.Option o:q.options)if(current.contains(o.label))out.append(o.label);return out.toString();
    }
    private AutomationEngine.Action scroll(ChapterQuiz page,boolean up) {
        if(page.web==null || scrolls>=24)return action("stop","章节测验滚动后仍未找到目标控件，请检查");
        AutomationEngine.Action a=action("chapter_scroll",up?"向上查找测验选项":"向下查找测验选项或提交按钮");
        a.x=page.web.left+(page.web.right-page.web.left)/3;a.x2=a.x;
        int upper=page.web.top+(page.web.bottom-page.web.top)/3,lower=page.web.top+(page.web.bottom-page.web.top)*7/12;
        a.y=up?upper:lower;a.y2=up?lower:upper;a.delay=700;return a;
    }
    public static AutomationEngine.Action confirmation(List<AutomationEngine.Node> nodes) {
        boolean known=false;
        for(AutomationEngine.Node n:nodes)if(n.visible() && (n.id.equals("com.chaoxing.mobile:id/tvMessage") || n.id.equals("android:id/message"))) {
            String s=n.text;known=s.contains("提交") && (s.contains("确定") || s.contains("确认") || s.contains("不能修改") || s.contains("不可修改"));
        }
        if(!known)return null;
        for(AutomationEngine.Node n:nodes)if(n.visible() && (n.id.equals("com.chaoxing.mobile:id/btnOk") || n.id.equals("android:id/button1")) && (n.text.equals("确定") || n.text.equals("确认") || n.text.equals("提交"))) {
            AutomationEngine.Action a=action("chapter_confirm","确认提交当前章节测验");a.x=(n.left+n.right)/2;a.y=(n.top+n.bottom)/2;a.gestureOnly=true;a.delay=1800;return a;
        }
        return null;
    }
    private static AutomationEngine.Action action(String kind,String message){return new AutomationEngine.Action(kind,message);}
}
