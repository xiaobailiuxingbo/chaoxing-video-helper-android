import local.mr.videohelper.DeepSeekClient;
import local.mr.videohelper.VideoQuiz;
import local.mr.videohelper.AutomationEngine;
import local.mr.videohelper.QuizCache;
import local.mr.videohelper.QuizKey;
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
        String connection=new JSONObject().put("model","deepseek-flash").put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("content","OK")))).toString();
        expect(DeepSeekClient.parseConnection(connection).equals("deepseek-flash"),"connection test requires complete model response");
        boolean badConnection=false;try{DeepSeekClient.parseConnection(connection.replace("\"OK\"","null"));}catch(Exception e){badConnection=true;}
        expect(badConnection,"null response cannot be called connection success");
        final java.util.Map<String,String> records=new java.util.HashMap<>();
        QuizCache.Storage storage=new QuizCache.Storage(){
            public String read(String key){return records.getOrDefault(key,"");}
            public void write(String key,String value){records.put(key,value);}
            public void remove(String key){records.remove(key);}
            public java.util.Set<String> keys(){return new java.util.HashSet<>(records.keySet());}
        };
        QuizCache cache=new QuizCache(storage);
        cache.accept("Course A",q,"B");expect(cache.find("Course A",q).label.equals("B"),"accepted answer stored per course");
        expect(cache.find("Course B",q)==null,"another course cannot read the entry");
        expect(cache.find("",q)==null,"unknown course does not share a global cache");
        expect(new QuizCache(storage).find("Course A",q).label.equals("B"),"recreating cache object retains stored data");
        VideoQuiz.Option first=q.options.get(0),second=q.options.get(1);String firstText=first.text;first.text=second.text;second.text=firstText;
        expect(cache.find("Course A",q).label.equals("A"),"persistent answer text survives shuffled option labels");
        cache.reject("Course A",q,"A");expect(cache.find("Course A",q)==null,"wrong feedback invalidates the cached answer");
        expect(cache.rejected("Course A",q).contains("two"),"rejected answer supplied for model correction");
        cache.accept("Course A",q,"A");expect(cache.find("Course A",q)==null,"a known-wrong answer cannot be restored as accepted");
        cache.accept("Course A",q,"B");expect(cache.find("Course A",q).label.equals("B"),"corrected choice replaces invalidated answer");
        cache.accept("Course B",q,"B");cache.clear("Course A");
        expect(cache.count("Course A")==0 && cache.find("Course B",q)!=null,"clear only removes the selected course");
        records.replaceAll((key,value)->"malformed data");expect(cache.find("Course B",q)==null,"damaged cache entry ignored safely");
        System.out.println("PASS: "+checks+" Android JSON and cache checks (no API requests)");
    }
}
