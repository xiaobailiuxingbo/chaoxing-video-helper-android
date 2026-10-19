package local.mr.videohelper;

import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Persistent storage is supplied by the app; tests use an in-memory store. */
public final class QuizCache {
    public interface Storage {
        String read(String key);
        void write(String key,String value);
        void remove(String key);
        Set<String> keys();
    }
    private final Storage storage;
    public QuizCache(Storage storage){this.storage=storage;}
    private String prefix(String course){return "v1:"+QuizKey.hash(QuizKey.normalize(course))+":";}
    private String key(String course,VideoQuiz q){return prefix(course)+QuizKey.question(q);}
    private JSONObject record(String key) {
        try{return new JSONObject(storage.read(key));}catch(Exception e){return new JSONObject();}
    }
    public List<String> rejected(String course,VideoQuiz q) {
        List<String> out=new ArrayList<>();if(QuizKey.normalize(course).isEmpty())return out;
        JSONArray values=record(key(course,q)).optJSONArray("rejected");
        if(values!=null)for(int i=0;i<values.length();i++)if(!values.optString(i,"").isEmpty())out.add(values.optString(i,""));return out;
    }
    public DeepSeekClient.Answer find(String course,VideoQuiz q) {
        if(QuizKey.normalize(course).isEmpty())return null;
        JSONObject record=record(key(course,q));String text=record.optString("answer","");
        if(text.isEmpty() || rejected(course,q).contains(QuizKey.normalize(text)))return null;
        if(ChoiceAnswer.multiple(q)) {
            JSONArray values=record.optJSONArray("answer_values");if(values==null || values.length()==0)return null;
            StringBuilder mapped=new StringBuilder();
            for(int i=0;i<values.length();i++){String label=QuizKey.labelForText(q,values.optString(i,""));if(label.isEmpty())return null;mapped.append(label);}
            String label=ChoiceAnswer.labels(q,mapped.toString());if(label.isEmpty() || !ChoiceAnswer.key(q,label).equals(text))return null;
            return new DeepSeekClient.Answer(label,"本课程已确认的多选答案组合缓存");
        }
        String label=QuizKey.labelForText(q,text);
        return label.isEmpty()?null:new DeepSeekClient.Answer(label,"本课程已通过弹题的本机缓存");
    }
    public void accept(String course,VideoQuiz q,String label) {
        if(QuizKey.normalize(course).isEmpty() || ChoiceAnswer.labels(q,label).isEmpty())return;
        String text=ChoiceAnswer.key(q,label);
        if(rejected(course,q).contains(text))return;
        String key=key(course,q);JSONObject value=record(key);
        try{value.put("answer",text).put("updated",System.currentTimeMillis());
            if(ChoiceAnswer.multiple(q))value.put("answer_values",new JSONArray(ChoiceAnswer.texts(q,label)));
            storage.write(key,value.toString());trim();}catch(Exception ignored){}
    }
    public void reject(String course,VideoQuiz q,String label) {
        if(QuizKey.normalize(course).isEmpty() || ChoiceAnswer.labels(q,label).isEmpty())return;
        String key=key(course,q), text=ChoiceAnswer.key(q,label);JSONObject value=record(key);
        List<String> rejected=rejected(course,q);if(!rejected.contains(text))rejected.add(text);
        try{value.remove("answer");value.put("rejected",new JSONArray(rejected)).put("updated",System.currentTimeMillis());storage.write(key,value.toString());trim();}catch(Exception ignored){}
    }
    public int count(String course) {
        if(QuizKey.normalize(course).isEmpty())return 0;
        int count=0;for(String key:storage.keys())if(key.startsWith(prefix(course)) && !record(key).optString("answer","").isEmpty())count++;return count;
    }
    public void clear(String course){for(String key:new ArrayList<>(storage.keys()))if(key.startsWith(prefix(course)))storage.remove(key);}
    private void trim() {
        Set<String> keys=storage.keys();if(keys.size()<=500)return;
        List<String> sorted=new ArrayList<>(keys);sorted.sort((a,b)->Long.compare(record(a).optLong("updated",0),record(b).optLong("updated",0)));
        for(int i=0;i<sorted.size()-500;i++)storage.remove(sorted.get(i));
    }
}
