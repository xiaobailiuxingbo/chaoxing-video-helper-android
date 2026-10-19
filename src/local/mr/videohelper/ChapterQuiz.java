package local.mr.videohelper;

import java.util.ArrayList;
import java.util.List;

/** Reads only the selected chapter-test tab, including offscreen text for scrolling. */
public final class ChapterQuiz {
    public String chapter="", error="";
    public AutomationEngine.Node web, submit;
    public boolean completed;
    public final List<VideoQuiz> questions=new ArrayList<>();
    public static boolean belongs(AutomationEngine.Node n,AutomationEngine.Node ancestor,List<AutomationEngine.Node> nodes) {
        for(int i=0;n!=null && i<nodes.size();i++) {
            if(n==ancestor)return true;
            n=n.parentIndex>=0 && n.parentIndex<nodes.size()?nodes.get(n.parentIndex):null;
        }
        return false;
    }
    public static AutomationEngine.Node activeWeb(List<AutomationEngine.Node> nodes) {
        AutomationEngine.Node found=null;
        for(AutomationEngine.Node n:nodes)if(n.visible() && n.type.equals("android.webkit.WebView"))found=n;
        return found;
    }
    public static boolean selected(List<AutomationEngine.Node> nodes) {
        for(AutomationEngine.Node n:nodes)if(n.visible() && n.id.equals("com.chaoxing.mobile:id/tv_title") && n.selected && n.text.contains("章节测验"))return true;
        return false;
    }
    public static AutomationEngine.Node scoped(List<AutomationEngine.Node> nodes,AutomationEngine.Node web,String id) {
        AutomationEngine.Node fallback=null;
        for(AutomationEngine.Node n:nodes)if(n.id.equals(id) && (web==null || belongs(n,web,nodes))) {
            if(n.visible())return n;fallback=n;
        }
        return fallback;
    }
    private static List<AutomationEngine.Node> descendants(AutomationEngine.Node box,List<AutomationEngine.Node> nodes) {
        List<AutomationEngine.Node> out=new ArrayList<>();for(AutomationEngine.Node n:nodes)if(n!=box && belongs(n,box,nodes))out.add(n);return out;
    }
    public static ChapterQuiz read(List<AutomationEngine.Node> nodes) {
        if(!selected(nodes))return null;
        ChapterQuiz page=new ChapterQuiz();page.web=activeWeb(nodes);
        for(AutomationEngine.Node n:nodes)if(n.visible() && n.id.equals("com.chaoxing.mobile:id/tv_chapter_name"))page.chapter=n.text;
        if(page.web==null || page.chapter.isEmpty())return page.fail("等待章节测验网页加载");
        AutomationEngine.Node form=scoped(nodes,page.web,"form1");
        if(form==null) {
            for(AutomationEngine.Node n:nodes)if(belongs(n,page.web,nodes) && (n.text.equals("已完成") || n.text.equals("已提交") || n.text.equals("本次成绩")))page.completed=true;
            if(!page.completed)page.error="无法确认章节测验结构";
            return page;
        }
        List<AutomationEngine.Node> children=descendants(form,nodes);
        for(AutomationEngine.Node n:children)if(n.text.equals("提交") && n.type.equals("android.widget.Button"))page.submit=n;
        for(AutomationEngine.Node header:children) {
            if(!header.text.matches("[\\[【](单选题|多选题|判断题|填空题|简答题)[\\]】]"))continue;
            VideoQuiz q=new VideoQuiz();q.context="chapter";q.type=header.text.replaceAll("[\\[\\]【】]","");
            AutomationEngine.Node title=header.parentIndex>=0?nodes.get(header.parentIndex):null;
            if(title==null)return page.fail("测验题目分组无法确认");
            for(AutomationEngine.Node n:children)if(n.parentIndex==title.index && n.text.matches("[0-9]+[.、．]"))q.number=n.text.replaceAll("[^0-9]","");
            StringBuilder stem=new StringBuilder();
            for(AutomationEngine.Node n:descendants(title,nodes)) {
                if(n==header || n.text.matches("[0-9]+[.、．]"))continue;
                if(n.type.contains("Image") || n.type.contains("Texture"))return page.fail("章节测验含图片或媒体，请本人处理");
                if(!n.text.trim().isEmpty()){if(stem.length()>0)stem.append('\n');stem.append(n.text.trim());}
            }
            q.stem=stem.toString();
            AutomationEngine.Node options=null;
            for(AutomationEngine.Node n:children)if(n.parentIndex==form.index && n.index>title.index && n.type.equals("android.widget.ListView")){options=n;break;}
            if(options==null || q.number.isEmpty() || q.stem.length()<3 || q.stem.length()>6000)return page.fail("测验题干或选项不完整");
            for(AutomationEngine.Node row:children)if(row.parentIndex==options.index) {
                AutomationEngine.Node label=null;StringBuilder text=new StringBuilder();
                for(AutomationEngine.Node n:descendants(row,nodes)) {
                    if(n.text.matches("[A-H]")){if(label!=null)return page.fail("选项编号存在歧义");label=n;continue;}
                    if(n.type.contains("Image") || n.type.contains("Texture"))return page.fail("章节测验选项含图片或媒体");
                    if(!n.text.trim().isEmpty()){if(text.length()>0)text.append(' ');text.append(n.text.trim());}
                }
                if(label==null || text.length()==0 || text.length()>3000)return page.fail("测验选项内容无法完整读取");
                VideoQuiz.Option option=new VideoQuiz.Option(label.text,text.toString(),(label.left+label.right)/2,(label.top+label.bottom)/2);
                option.node=label;option.visible=label.visible() && label.bottom-label.top>=35 && label.top>=page.web.top+12 && label.bottom<=page.web.bottom-12;
                q.options.add(option);
            }
            if(!(q.type.equals("单选题") || q.type.equals("判断题")))return page.fail("章节测验暂支持文字单选和判断题，当前题型请本人处理");
            if(q.options.size()<2 || q.options.size()>8 || (q.type.equals("判断题") && q.options.size()!=2))return page.fail("测验选项数量不完整");
            for(int i=0;i<q.options.size();i++)if(!q.options.get(i).label.equals(String.valueOf((char)('A'+i))))return page.fail("测验选项编号不连续");
            StringBuilder identity=new StringBuilder(q.context).append(q.type).append(q.stem);
            for(VideoQuiz.Option o:q.options)identity.append('\n').append(o.label).append(':').append(o.text);
            q.fingerprint=QuizKey.hash(identity.toString());page.questions.add(q);
        }
        if(page.questions.isEmpty())return page.fail("未读取到章节测验题目");
        java.util.Set<String> numbers=new java.util.HashSet<>();
        for(VideoQuiz q:page.questions)if(!numbers.add(q.number))return page.fail("测验题号重复，请检查页面");
        if(page.submit==null)return page.fail("没有读取到章节测验提交按钮");
        return page;
    }
    private ChapterQuiz fail(String value){error=value;return this;}
}
