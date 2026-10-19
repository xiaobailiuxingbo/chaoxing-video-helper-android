import local.mr.videohelper.DeepSeekClient;
import local.mr.videohelper.VideoQuiz;
import local.mr.videohelper.AutomationEngine;
import local.mr.videohelper.QuizCache;
import local.mr.videohelper.QuizKey;
import local.mr.videohelper.StudyHistory;
import local.mr.videohelper.ChapterResult;
import local.mr.videohelper.ChoiceAnswer;
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
    static QuizCache.Storage memory(final java.util.Map<String,String> values) {
        return new QuizCache.Storage(){
            public String read(String key){return values.getOrDefault(key,"");}
            public void write(String key,String value){values.put(key,value);}
            public void remove(String key){values.remove(key);}
            public java.util.Set<String> keys(){return new java.util.HashSet<>(values.keySet());}
        };
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
        DeepSeekClient.Answer guess=DeepSeekClient.parse(response(result().put("answerable",false).put("confidence","low"),"stop"),q,true);
        expect(guess.label.equals("B") && guess.uncertain && guess.reason.startsWith("推测"),"authorized uncertain candidate is returned and labelled honestly");
        expect(DeepSeekClient.parse(response(result().put("confidence","medium"),"stop"),q,true).uncertain,"medium confidence can proceed under automatic mode");
        expect(!DeepSeekClient.parse(response(result(),"stop"),q,true).uncertain,"high-confidence answer retains normal source");
        boolean invalidGuess=false;try{DeepSeekClient.parse(response(result().put("answers",new JSONArray()),"stop"),q,true);}catch(Exception e){invalidGuess=true;}
        expect(invalidGuess,"automatic mode still requires an actual existing option");
        invalidGuess=false;try{DeepSeekClient.parse(response(result().put("answers",new JSONArray().put("Z")),"stop"),q,true);}catch(Exception e){invalidGuess=true;}
        expect(invalidGuess,"automatic mode rejects nonexistent option");
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
        q.context="chapter";q.type="判断题";q.options.get(0).text="对";q.options.get(1).text="错";
        cache.accept("Course C",q,"B");expect(new QuizCache(storage).find("Course C",q).label.equals("B"),"chapter true false cache survives a new instance");
        q.context="video";expect(cache.find("Course C",q)==null,"chapter cache cannot leak into video popup");q.context="chapter";
        q.options.get(0).text="错";q.options.get(1).text="对";
        expect(cache.find("Course C",q).label.equals("A"),"chapter true false remaps by answer text when order reverses");
        cache.reject("Course C",q,"A");expect(cache.find("Course C",q)==null,"wrong chapter answer is invalidated persistently");
        final java.util.Map<String,String> historyRecords=new java.util.HashMap<>();QuizCache.Storage hs=memory(historyRecords);
        StudyHistory history=new StudyHistory(hs);q.number="1";
        history.observe("Course A","3.10 Example");history.answer("Course A","3.10 Example",q,new DeepSeekClient.Answer("A","missing video",true),"DeepSeek 推测");
        JSONObject pending=new StudyHistory(hs).records().get(0);
        expect(!pending.has("score") && pending.optString("status").equals("测验作答中"),"guess has no fabricated course score");
        JSONObject answer=pending.getJSONObject("questions").getJSONObject("chapter:"+QuizKey.question(q));
        expect(answer.getBoolean("uncertain") && answer.getString("answer_text").equals("错"),"answer text and uncertainty persist across history instances");
        history.submitted("Course A","3.10 Example");
        expect(history.records().get(0).optString("status").contains("等待判分"),"submitted chapter stays ungraded until result arrives");
        ChapterResult platform=new ChapterResult();platform.chapter="3.10 Example";platform.score="75";
        ChapterResult.Entry entry=new ChapterResult.Entry();entry.question=q;entry.answer="A";entry.wrong=true;entry.score="0.0";platform.entries.add(entry);
        history.result("Course A",platform);JSONObject graded=history.records().get(0);
        expect(graded.optString("score").equals("75") && graded.optString("status").equals("测验已完成"),"official platform score is saved as chapter grade");
        answer=graded.getJSONObject("questions").getJSONObject("chapter:"+QuizKey.question(q));
        expect(answer.optString("state").equals("答错") && answer.optString("score").equals("0.0") && answer.optBoolean("uncertain"),"wrong question score updates without losing original guess label");
        history.result("Course A",platform);expect(history.records().size()==1,"revisiting result updates same chapter rather than duplicating it");
        history.observe("Course A","3.2 Earlier");history.observe("Course B","3.2 Earlier");
        expect(history.records().size()==3 && history.records().get(0).optString("chapter").equals("3.2 Earlier"),"chapter list uses natural numeric order within each course");
        expect(history.records().get(2).optString("course").equals("Course B"),"same chapter in different courses stays separate");
        history.observe("","Unknown");expect(history.records().size()==3,"unknown course does not create shared history group");
        historyRecords.put("damaged","not json");expect(new StudyHistory(hs).records().size()==3,"damaged history record ignored");
        q.context="chapter";q.type="多选题";
        DeepSeekClient.Answer combination=DeepSeekClient.parse(response(result().put("answers",new JSONArray().put("B").put("A")),"stop"),q,true);
        expect(combination.label.equals("AB"),"multiple-choice array accepts and canonicalizes complete combination");
        boolean duplicate=false;try{DeepSeekClient.parse(response(result().put("answers",new JSONArray().put("A").put("A")),"stop"),q,true);}catch(Exception e){duplicate=true;}
        expect(duplicate,"repeated multi labels rejected");
        duplicate=false;try{DeepSeekClient.parse(response(result().put("answers",new JSONArray().put("AB")),"stop"),q,true);}catch(Exception e){duplicate=true;}
        expect(duplicate,"model must return separate valid labels rather than a concatenated element");
        cache.accept("Multi course",q,"AB");expect(cache.find("Multi course",q).label.equals("AB"),"complete multi answer combination persists");
        cache.reject("Multi course",q,"AB");expect(cache.find("Multi course",q)==null,"wrong whole combination invalidated");
        cache.accept("Multi course",q,"A");expect(cache.find("Multi course",q).label.equals("A"),"rejecting a combination does not exclude each constituent choice");
        String oldA=q.options.get(0).text;q.options.get(0).text=q.options.get(1).text;q.options.get(1).text=oldA;
        expect(cache.find("Multi course",q).label.equals("B"),"multi cache maps stored option texts after reordering");
        expect(new JSONArray(ChoiceAnswer.key(q,"AB")).length()==2,"rejected combination text key remains parseable JSON for model input");
        history.answer("Multi history","1.1 Example",q,combination,"DeepSeek");JSONObject multiHistory=history.records().get(history.records().size()-1);
        JSONObject storedMulti=multiHistory.getJSONObject("questions").getJSONObject("chapter:"+QuizKey.question(q));
        expect(storedMulti.optString("answer").equals("AB") && storedMulti.optString("answer_text").contains("；"),"history stores all selected labels and option texts");
        System.out.println("PASS: "+checks+" Android JSON and cache checks (no API requests)");
    }
}
