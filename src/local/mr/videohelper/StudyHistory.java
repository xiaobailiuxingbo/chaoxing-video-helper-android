package local.mr.videohelper;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Local course -> chapter records. Official scores never come from model estimates. */
public final class StudyHistory {
    private final QuizCache.Storage storage;
    public StudyHistory(QuizCache.Storage storage){this.storage=storage;}
    private String key(String course,String chapter){return "h1:"+QuizKey.hash(QuizKey.normalize(course))+":"+QuizKey.hash(QuizKey.normalize(chapter));}
    private JSONObject load(String course,String chapter) {
        try{return new JSONObject(storage.read(key(course,chapter)));}catch(Exception e){return new JSONObject();}
    }
    private void save(String course,String chapter,JSONObject record) {
        if(course.trim().isEmpty() || chapter.trim().isEmpty())return;
        try {
            record.put("course",course).put("chapter",chapter).put("updated",System.currentTimeMillis());
            storage.write(key(course,chapter),record.toString());
        }catch(Exception ignored){}
    }
    public void status(String course,String chapter,String status) {
        JSONObject record=load(course,chapter);
        if(status.equals(record.optString("status")))return;
        try{record.put("status",status);save(course,chapter,record);}catch(Exception ignored){}
    }
    public void observe(String course,String chapter) {
        JSONObject record=load(course,chapter);if(record.optString("chapter").isEmpty())status(course,chapter,"尚无测验记录");
    }
    public void answer(String course,String chapter,VideoQuiz q,DeepSeekClient.Answer answer,String source) {
        if(ChoiceAnswer.labels(q,answer.label).isEmpty())return;
        JSONObject record=load(course,chapter), questions=record.optJSONObject("questions");if(questions==null)questions=new JSONObject();
        String identity=q.context+":"+QuizKey.question(q);
        JSONObject entry=questions.optJSONObject(identity);if(entry==null)entry=new JSONObject();
        try {
            JSONArray options=new JSONArray();for(VideoQuiz.Option o:q.options)options.put(new JSONObject().put("label",o.label).put("text",o.text));
            entry.put("number",q.number).put("type",q.type).put("context",q.context).put("stem",q.stem).put("options",options)
                .put("answer",answer.label).put("answer_text",ChoiceAnswer.display(q,answer.label)).put("reason",answer.reason)
                .put("uncertain",answer.uncertain).put("source",source).put("state","待提交");
            questions.put(identity,entry);record.put("questions",questions);
            if(q.context.equals("chapter")){record.put("status","测验作答中");record.remove("result_id");}
            else if(record.optString("status").isEmpty() || record.optString("status").equals("尚无测验记录"))record.put("status","视频学习中");
            save(course,chapter,record);
        }catch(Exception ignored){}
    }
    public void videoResult(String course,String chapter,VideoQuiz q,String state) {
        JSONObject record=load(course,chapter), questions=record.optJSONObject("questions");if(questions==null)return;
        JSONObject entry=questions.optJSONObject(q.context+":"+QuizKey.question(q));if(entry==null)return;
        try{entry.put("state",state);save(course,chapter,record);}catch(Exception ignored){}
    }
    public void submitted(String course,String chapter) {
        JSONObject record=load(course,chapter), questions=record.optJSONObject("questions");
        try {
            if(questions!=null){java.util.Iterator<String> keys=questions.keys();while(keys.hasNext()) {
                JSONObject q=questions.optJSONObject(keys.next());if(q!=null && q.optString("context").equals("chapter"))q.put("state","已提交，等待判分");
            }}
            record.put("status","已提交，等待判分");save(course,chapter,record);
        }catch(Exception ignored){}
    }
    public void result(String course,ChapterResult result) {
        JSONObject record=load(course,result.chapter), questions=record.optJSONObject("questions");if(questions==null)questions=new JSONObject();
        StringBuilder stamp=new StringBuilder(result.score);
        for(ChapterResult.Entry e:result.entries)stamp.append(QuizKey.question(e.question)).append(e.answer).append(e.correctAnswer).append(e.score).append(e.correct).append(e.wrong);
        String resultId=QuizKey.hash(stamp.toString());if(resultId.equals(record.optString("result_id")))return;
        try {
            for(ChapterResult.Entry e:result.entries) {
                String identity="chapter:"+QuizKey.question(e.question);
                JSONObject item=questions.optJSONObject(identity);if(item==null)item=new JSONObject();
                item.put("number",e.question.number).put("context","chapter").put("type",e.question.type).put("stem",e.question.stem)
                    .put("answer",e.answer).put("answer_text",ChoiceAnswer.display(e.question,e.answer)).put("score",e.score)
                    .put("state",e.wrong?"答错":e.correct?"正确":"已判分");
                JSONArray options=new JSONArray();for(VideoQuiz.Option o:e.question.options)options.put(new JSONObject().put("label",o.label).put("text",o.text));item.put("options",options);
                if(!e.correctAnswer.isEmpty())item.put("correct_text",ChoiceAnswer.display(e.question,e.correctAnswer));
                questions.put(identity,item);
            }
            record.put("questions",questions).put("status","测验已完成").put("score",result.score).put("result_id",resultId);save(course,result.chapter,record);
        }catch(Exception ignored){}
    }
    public List<JSONObject> records() {
        List<JSONObject> out=new ArrayList<>();
        for(String key:storage.keys())try{JSONObject value=new JSONObject(storage.read(key));if(!value.optString("course").isEmpty() && !value.optString("chapter").isEmpty())out.add(value);}catch(Exception ignored){}
        out.sort((a,b)->{int c=a.optString("course").compareTo(b.optString("course"));return c!=0?c:compareChapter(a.optString("chapter"),b.optString("chapter"));});return out;
    }
    public static int compareChapter(String a,String b) {
        int i=0,j=0;
        while(i<a.length() && j<b.length()) {
            if(Character.isDigit(a.charAt(i)) && Character.isDigit(b.charAt(j))) {
                int ai=i,bj=j;while(i<a.length() && Character.isDigit(a.charAt(i)))i++;while(j<b.length() && Character.isDigit(b.charAt(j)))j++;
                String an=a.substring(ai,i).replaceFirst("^0+(?!$)",""),bn=b.substring(bj,j).replaceFirst("^0+(?!$)","");
                int c=Integer.compare(an.length(),bn.length());if(c==0)c=an.compareTo(bn);if(c!=0)return c;
            } else {int c=Character.compare(a.charAt(i++),b.charAt(j++));if(c!=0)return c;}
        }
        return Integer.compare(a.length()-i,b.length()-j);
    }
}
