package local.mr.videohelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Recognizes the player's own quiz, never a chapter worksheet. No network dependencies. */
public final class VideoQuiz {
    public static final class Option {
        public String label, text;
        public int x, y;
        Option(String label, String text, int x, int y) { this.label=label; this.text=text; this.x=x; this.y=y; }
    }
    public String type="", stem="", fingerprint="", error="";
    public final List<Option> options = new ArrayList<>();
    public final java.util.Set<Integer> contentNodes = new java.util.HashSet<>();
    public AutomationEngine.Node submit;
    private static boolean id(AutomationEngine.Node n, String id) {
        return n.id.equals(id) || n.id.equals("com.chaoxing.mobile:id/"+id);
    }
    public static boolean present(List<AutomationEngine.Node> nodes) {
        boolean event=false, submit=false;
        for (AutomationEngine.Node n:nodes) {
            if (!n.visible() || !n.pkg.equals("com.chaoxing.mobile")) continue;
            if(id(n,"event_window")) event=true;
            if(id(n,"videoquiz-submit")) submit=true;
        }
        return event && submit;
    }
    private static boolean inside(AutomationEngine.Node n, AutomationEngine.Node box) {
        return n.visible() && n.left>=box.left && n.right<=box.right && n.top>=box.top && n.bottom<=box.bottom;
    }
    private static boolean belongs(AutomationEngine.Node n, AutomationEngine.Node ancestor, List<AutomationEngine.Node> nodes) {
        for(int i=0; i<nodes.size() && n!=null; i++) {
            if(n.index==ancestor.index)return true;
            n=n.parentIndex>=0 && n.parentIndex<nodes.size()?nodes.get(n.parentIndex):null;
        }
        return false;
    }
    public static VideoQuiz read(List<AutomationEngine.Node> nodes) {
        if(!present(nodes)) return null;
        VideoQuiz q=new VideoQuiz();
        AutomationEngine.Node area=null, header=null, event=null, web=null;
        for(AutomationEngine.Node n:nodes) if(n.visible() && id(n,"videoquiz-submit"))q.submit=n;
        AutomationEngine.Node ancestor=q.submit;
        for(int i=0;ancestor!=null && i<nodes.size();i++) {
            if(ancestor.type.equals("android.webkit.WebView")){web=ancestor;break;}
            ancestor=ancestor.parentIndex>=0 && ancestor.parentIndex<nodes.size()?nodes.get(ancestor.parentIndex):null;
        }
        if(web==null)return q.fail("弹题网页结构无法确认");
        for(AutomationEngine.Node n:nodes) {
            if(!n.visible()) continue;
            if(id(n,"event_window"))event=n;
            if(!belongs(n,web,nodes))continue;
            if(id(n,"radioArea"))area=n;
            if(n.text.matches("[\\[【]?(单选题|判断题|多选题)[\\]】]?")) {header=n;q.type=n.text.replaceAll("[\\[\\]【】]", "");}
        }
        if(header==null || area==null || event==null) return q.fail("弹题结构无法完整读取");
        if(!q.type.equals("单选题")) return q.fail("当前仅支持文字单选弹题，其他题型请本人处理");
        List<AutomationEngine.Node> labels=new ArrayList<>();
        StringBuilder stem=new StringBuilder();
        for(AutomationEngine.Node n:nodes) {
            if(!belongs(n,web,nodes) || !inside(n,event))continue;
            if(n.top>=header.bottom && n.bottom<=area.top && !n.text.trim().isEmpty()) {
                if(!n.type.equals("android.widget.TextView")) return q.fail("题干含无法确认的内容");
                if(stem.length()>0)stem.append('\n');stem.append(n.text.trim());
                q.contentNodes.add(n.index);
            }
            if(n.top>=header.bottom && n.top<area.bottom && (n.type.contains("Image") || n.type.contains("Texture")))
                return q.fail("题目含图片或媒体，当前文字模式无法判断");
            if(inside(n,area) && Pattern.matches("[A-H][、.．:：]",n.text.trim()))labels.add(n);
        }
        q.stem=stem.toString();
        if(q.stem.length()<3 || q.stem.length()>6000 || labels.size()<2 || labels.size()>8) return q.fail("题干或选项读取不完整");
        labels.sort((a,b)->Integer.compare(a.top,b.top));
        for(int i=0;i<labels.size();i++) {
            AutomationEngine.Node label=labels.get(i);
            q.contentNodes.add(label.index);
            String expected=String.valueOf((char)('A'+i));
            if(!label.text.startsWith(expected))return q.fail("选项编号不连续，停止自动答题");
            StringBuilder option=new StringBuilder();
            int nextTop=i+1<labels.size()?labels.get(i+1).top:area.bottom;
            for(AutomationEngine.Node n:nodes) {
                if(n==label || !belongs(n,web,nodes) || !inside(n,area) || n.top<label.top || n.bottom>nextTop || n.text.trim().isEmpty())continue;
                if(n.text.matches("[A-H][、.．:：]"))continue;
                if(!n.type.equals("android.widget.TextView"))return q.fail("选项含无法确认的内容");
                if(option.length()>0)option.append(' ');option.append(n.text.trim());
                q.contentNodes.add(n.index);
            }
            if(option.length()==0 || option.length()>3000) return q.fail("选项内容无法完整读取");
            q.options.add(new Option(expected,option.toString(),area.left+(label.bottom-label.top)/2,(label.top+label.bottom)/2));
        }
        try {
            StringBuilder identity=new StringBuilder(q.type).append('\n').append(q.stem);
            for(Option o:q.options)identity.append('\n').append(o.label).append(':').append(o.text);
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));q.fingerprint=hex.toString();
        }catch(Exception e){return q.fail("题目校验失败");}
        return q;
    }
    private VideoQuiz fail(String s){error=s;return this;}
    public Option option(String label){for(Option o:options)if(o.label.equals(label))return o;return null;}
}
