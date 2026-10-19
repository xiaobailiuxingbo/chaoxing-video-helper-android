import local.mr.videohelper.DeepSeekClient;
import local.mr.videohelper.VideoQuiz;
import local.mr.videohelper.AutomationEngine;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Run against Android's actual org.json implementation; never performs a network call. */
public class JsonChecks {
    static int checks;
    static void expect(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static String response(JSONObject result,String finish) throws Exception {
        return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason",finish)
            .put("message",new JSONObject().put("content",result.toString())))).toString();
    }
    static JSONObject result() throws Exception{return new JSONObject().put("answerable",true).put("confidence","high").put("answers",new JSONArray().put("B")).put("reason","test");}
    static void rejects(String raw,VideoQuiz q,String label) throws Exception {
        boolean rejected=false;try{DeepSeekClient.parse(raw,q);}catch(Exception e){rejected=true;}expect(rejected,label);
    }
    public static void main(String[] args) throws Exception {
        List<AutomationEngine.Node> nodes=new ArrayList<>();
        String[] texts={"","","[单选题]","test question","","A、","one","B、","two",""};
        String[] ids={"com.chaoxing.mobile:id/event_window","","","","radioArea","","","","","videoquiz-submit"};
        for(int i=0;i<texts.length;i++) {
            AutomationEngine.Node n=new AutomationEngine.Node();n.index=i;n.pkg="com.chaoxing.mobile";n.text=texts[i];n.id=ids[i];n.type="android.widget.TextView";
            n.left=0;n.top=0;n.right=1000;n.bottom=1000;n.parentIndex=i==0?-1:i==1?0:1;nodes.add(n);
        }
        nodes.get(1).type="android.webkit.WebView";
        nodes.get(2).bottom=100;nodes.get(3).top=120;nodes.get(3).bottom=200;
        nodes.get(4).top=300;nodes.get(4).bottom=600;
        nodes.get(5).top=300;nodes.get(5).bottom=400;nodes.get(6).top=300;nodes.get(6).bottom=400;
        nodes.get(7).top=450;nodes.get(7).bottom=550;nodes.get(8).top=450;nodes.get(8).bottom=550;
        VideoQuiz q=VideoQuiz.read(nodes);expect(q!=null && q.error.isEmpty(),"parse test question");
        expect(DeepSeekClient.parse(response(result(),"stop"),q).label.equals("B"),"valid high certainty existing label");
        rejects(response(result().put("answerable",false),"stop"),q,"abstention");
        rejects(response(result().put("answerable","true"),"stop"),q,"boolean must be real boolean");
        rejects(response(result().put("confidence","medium"),"stop"),q,"uncertain answer");
        rejects(response(result().put("answers",new JSONArray().put("C")),"stop"),q,"out of range option");
        rejects(response(result().put("answers",new JSONArray().put("A").put("B")),"stop"),q,"single choice cannot have two options");
        rejects(response(result().put("answers",new JSONArray().put(1)),"stop"),q,"numeric index not accepted");
        rejects(response(result(),"length"),q,"truncated response");
        rejects("{\"choices\":[]}",q,"empty choices");
        rejects("not json",q,"invalid JSON");
        System.out.println("PASS: "+checks+" Android JSON response checks (no API requests)");
    }
}
