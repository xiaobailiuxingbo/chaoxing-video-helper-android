package local.mr.videohelper;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Read-only local course and chapter score viewer. */
public final class HistoryActivity extends Activity {
    private final Handler handler=new Handler();
    private final Set<String> closedCourses=new HashSet<>(),openedChapters=new HashSet<>();
    private ScrollView scroll;
    private LinearLayout page;
    private String previous="";
    private final int ink=Color.rgb(25,42,57),green=Color.rgb(20,108,89),muted=Color.rgb(94,110,125);
    private final Runnable refresh=new Runnable(){@Override public void run(){render(false);handler.postDelayed(this,1500);}};
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private void add(LinearLayout parent,android.view.View view,int gap){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(gap);parent.addView(view,p);}
    private TextView text(String value,int size,int color,boolean bold) {
        TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(dp(3),1f);
        if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return view;
    }
    private Button button(String value) {
        Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(green);b.setMinHeight(dp(48));return b;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);getWindow().setStatusBarColor(Color.rgb(244,247,250));getWindow().setNavigationBarColor(Color.rgb(244,247,250));
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(244,247,250));
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(20),dp(20),dp(20),dp(26));scroll.addView(page);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{scroll.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        if(state!=null){String[] courses=state.getStringArray("closed"),chapters=state.getStringArray("opened");if(courses!=null)java.util.Collections.addAll(closedCourses,courses);if(chapters!=null)java.util.Collections.addAll(openedChapters,chapters);}
        render(true);
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){state.putStringArray("closed",closedCourses.toArray(new String[0]));state.putStringArray("opened",openedChapters.toArray(new String[0]));super.onSaveInstanceState(state);}
    private void render(boolean force) {
        List<JSONObject> records=AppState.history(this).records();String signature=records.toString();if(!force && signature.equals(previous))return;previous=signature;
        int y=scroll.getScrollY();page.removeAllViews();
        Button back=button("‹ 返回播放助手");back.setTag("history_back");back.setOnClickListener(v->finish());add(page,back,0);
        add(page,text("课程与章节记录",25,ink,true),14);
        add(page,text("按课程分组，显示本机已读取章节的最新作答与学习通实际得分。点击章节展开各题答案。",13,muted,false),8);
        if(records.isEmpty())add(page,text("尚无章节记录。打开学习通章节并开始辅助，或打开已提交测验的结果页后，记录会自动保存。",15,muted,false),24);
        Map<String,List<JSONObject>> groups=new LinkedHashMap<>();
        for(JSONObject record:records)groups.computeIfAbsent(record.optString("course"),key->new ArrayList<>()).add(record);
        for(Map.Entry<String,List<JSONObject>> group:groups.entrySet()) {
            String course=group.getKey();int scored=0;for(JSONObject record:group.getValue())if(record.has("score"))scored++;
            boolean closed=closedCourses.contains(course);
            Button heading=button((closed?"▸ ":"▾ ")+course+"\n"+group.getValue().size()+" 章 · "+scored+" 章有得分");heading.setTag("history_course");
            heading.setOnClickListener(v->{if(closedCourses.contains(course))closedCourses.remove(course);else closedCourses.add(course);render(true);});add(page,heading,20);
            if(closed)continue;
            for(JSONObject record:group.getValue()) {
                String chapter=record.optString("chapter"),id=QuizKey.hash(course+"\n"+chapter);
                LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(14),dp(12),dp(14),dp(14));
                GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(12));card.setBackground(bg);add(page,card,10);
                String score=record.has("score")?record.optString("score")+" 分":"待判分／暂无得分";
                Button title=button((openedChapters.contains(id)?"▾ ":"▸ ")+chapter+"\n"+score);title.setTag("history_chapter");
                title.setOnClickListener(v->{if(openedChapters.contains(id))openedChapters.remove(id);else openedChapters.add(id);render(true);});add(card,title,0);
                TextView status=text(record.optString("status","尚未作答"),13,muted,false);status.setTag("history_chapter_status");add(card,status,5);
                if(!openedChapters.contains(id))continue;
                JSONObject questions=record.optJSONObject("questions");List<JSONObject> entries=new ArrayList<>();
                if(questions!=null){java.util.Iterator<String> keys=questions.keys();while(keys.hasNext()){JSONObject q=questions.optJSONObject(keys.next());if(q!=null)entries.add(q);}}
                entries.sort((a,b)->{int context=a.optString("context").compareTo(b.optString("context"));return context!=0?context:StudyHistory.compareChapter(a.optString("number"),b.optString("number"));});
                for(JSONObject q:entries) {
                    String prefix=q.optString("context").equals("video")?"视频弹题":"测验第 "+q.optString("number")+" 题";
                    TextView question=text(prefix+" · "+q.optString("type")+"\n"+q.optString("stem"),15,ink,true);question.setTag("history_question");add(card,question,18);
                    JSONArray options=q.optJSONArray("options");if(options!=null)for(int i=0;i<options.length();i++){JSONObject o=options.optJSONObject(i);if(o!=null)add(card,text(o.optString("label")+". "+o.optString("text"),13,muted,false),3);}
                    String answer="我的答案："+q.optString("answer")+" · "+q.optString("answer_text")+(q.optBoolean("uncertain",false)?"（推测）":"");
                    TextView answerView=text(answer,14,green,true);answerView.setTag("history_answer");add(card,answerView,7);
                    String details=q.optString("state","待提交");if(!q.optString("score").isEmpty())details+=" · 得分 "+q.optString("score")+" 分";
                    TextView feedback=text(details,13,muted,false);feedback.setTag("history_question_score");add(card,feedback,4);
                    if(!q.optString("correct_text").isEmpty())add(card,text("平台正确答案："+q.optString("correct_text"),13,muted,false),4);
                    if(!q.optString("reason").isEmpty())add(card,text(q.optString("source")+"："+q.optString("reason"),12,muted,false),4);
                }
            }
        }
        add(page,text("未读取到的历史章节不会凭空补全。视频弹题没有分数时显示平台反馈。记录仅保存在本机，覆盖更新保留，卸载或清除应用数据会删除。",12,muted,false),22);
        scroll.post(()->scroll.scrollTo(0,y));
    }
    @Override public void dump(String prefix,java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args) {
        if(args!=null && java.util.Arrays.asList(args).contains("controls")){controls(getWindow().getDecorView(),out);return;}
        super.dump(prefix,fd,out,args);
    }
    private void controls(android.view.View view,java.io.PrintWriter out) {
        if(view.getTag() instanceof String && view instanceof TextView) {
            android.graphics.Rect bounds=new android.graphics.Rect();boolean visible=view.isShown() && view.getLocalVisibleRect(bounds);int[] xy=new int[2];view.getLocationOnScreen(xy);bounds.offset(xy[0],xy[1]);
            out.println("CONTROL "+view.getTag()+" visible="+visible+" bounds="+bounds.toShortString()+" text="+((TextView)view).getText().toString().replace('\n',' '));
        }
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++)controls(((android.view.ViewGroup)view).getChildAt(i),out);
    }
}
