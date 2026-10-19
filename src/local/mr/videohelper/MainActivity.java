package local.mr.videohelper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.text.InputType;
import android.widget.EditText;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView badge, status, progress, log;
    private Button enable, start, pause;
    private final int green = Color.rgb(20, 108, 89), ink = Color.rgb(25, 42, 57), muted = Color.rgb(94, 110, 125);
    private final Runnable update = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 1000); }
    };
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private GradientDrawable shape(int color, int border) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(16));
        if (border != 0) d.setStroke(dp(1), border); return d;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setLineSpacing(dp(3), 1f); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(18), dp(18), dp(18), dp(18));
        v.setBackground(shape(Color.WHITE, Color.rgb(227, 234, 239)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(15); parent.addView(v, lp); return v;
    }
    private void add(LinearLayout parent, View v, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(top); parent.addView(v, lp);
    }
    private Button button(String value, boolean primary) {
        Button b = new Button(this); b.setText(value); b.setTextSize(15); b.setAllCaps(false); b.setMinHeight(dp(50));
        b.setTextColor(primary ? Color.WHITE : green); b.setBackground(shape(primary ? green : Color.rgb(235, 246, 241), 0)); return b;
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(244, 247, 250)); getWindow().setNavigationBarColor(Color.rgb(244, 247, 250));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(244, 247, 250));
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(22), dp(22), dp(22), dp(26));
        scroll.addView(page); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            scroll.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom()); return insets;
        });
        add(page, text("视频播放助手", 28, ink, true), 8);
        add(page, text("在手机上独立启动与控制", 14, muted, false), 6);
        badge = text("服务未启用", 12, green, true); badge.setPadding(dp(12), dp(6), dp(12), dp(6));
        badge.setBackground(shape(Color.rgb(227, 241, 235), 0));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.topMargin = dp(14); page.addView(badge, bp);

        LinearLayout stateCard = card(page);
        add(stateCard, text("当前状态", 13, muted, false), 0);
        status = text("先启用服务，再点击开始辅助", 19, ink, true); add(stateCard, status, 8);
        progress = text("打开学习通视频页后显示播放进度", 13, muted, false); add(stateCard, progress, 8);
        enable = button("启用无障碍服务", false); add(stateCard, enable, 16);
        enable.setOnClickListener(v -> {
            Toast.makeText(this, "找到“学习通视频辅助”并开启", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        start = button("开始辅助", true); add(stateCard, start, 10);
        start.setOnClickListener(v -> {
            if (PlaybackService.instance == null) { Toast.makeText(this, "请先启用学习通视频辅助服务", Toast.LENGTH_LONG).show(); return; }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
            AppState.start(this); PlaybackService.instance.refreshNotification(); render();
            Toast.makeText(this, "已开始，请切回学习通视频页", Toast.LENGTH_LONG).show();
        });
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        pause = button("暂停辅助", false); Button stop = button("停止", false);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(50), 1); half.rightMargin = dp(8); row.addView(pause, half);
        row.addView(stop, new LinearLayout.LayoutParams(0, dp(50), 1)); add(stateCard, row, 10);
        pause.setOnClickListener(v -> { AppState.pause(this); if (PlaybackService.instance != null) PlaybackService.instance.refreshNotification(); render(); });
        stop.setOnClickListener(v -> { AppState.stop(this); if (PlaybackService.instance != null) PlaybackService.instance.refreshNotification(); render(); });
        add(stateCard, text("开始后切回学习通，视频页需保持前台。暂停辅助只停止自动操作，视频仍由学习通播放器控制。", 12, muted, false), 12);

        LinearLayout options = card(page); add(options, text("播放设置", 17, ink, true), 0);
        add(options, text("播放倍速", 13, muted, false), 12);
        String[] rates = {"1", "1.25", "1.5", "1.75", "2"};
        Spinner spinner = new Spinner(this); String[] labels = {"1× · 原速", "1.25×", "1.5×", "1.75×", "2×"};
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        String chosen = AppState.prefs(this).getString("rate", "1");
        for (int i = 0; i < rates.length; i++) if (rates[i].equals(chosen)) spinner.setSelection(i);
        add(options, spinner, 6);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { AppState.prefs(MainActivity.this).edit().putString("rate", rates[position]).apply(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        add(options, text("这门课关闭了未完成视频的倍速。助手仅使用播放器提供的选项；不可用时保持当前速度。", 12, muted, false), 8);
        CheckBox consent = new CheckBox(this); consent.setText("自动确认已授权的课程监控提示"); consent.setTextColor(ink);
        consent.setChecked(AppState.prefs(this).getBoolean("consent", true)); add(options, consent, 12);
        consent.setOnCheckedChangeListener((b, checked) -> AppState.prefs(this).edit().putBoolean("consent", checked).apply());
        CheckBox next = new CheckBox(this); next.setText("视频播完后继续下一章节"); next.setTextColor(ink);
        next.setChecked(AppState.prefs(this).getBoolean("next", true)); add(options, next, 4);
        next.setOnCheckedChangeListener((b, checked) -> AppState.prefs(this).edit().putBoolean("next", checked).apply());
        add(options, text("自动重试最多三次；章节作业和考试保持未作答。真正的人脸验证会停下等待本人处理。单次最多运行六小时。", 12, muted, false), 10);
        Button ai=button("DeepSeek · 视频弹题设置",false);add(options,ai,14);
        ai.setOnClickListener(v->showAiSettings());
        add(options,text("支持文字单选弹题；无法读取、其他题型或模型不确定时暂停。需要先填写 API Key 并开启自动答题。",12,muted,false),8);

        LinearLayout history = card(page); add(history, text("最近操作", 17, ink, true), 0);
        log = text("尚无记录", 12, muted, false); add(history, log, 10);
        add(page, text("手机独立运行。启用 AI 后，仅将弹题的题干和选项发送到 DeepSeek，按 API 用量计费；不发送人脸画面。普通播放辅助无需调用 AI。", 12, muted, false), 20);
    }
    private void showAiSettings() {
        SharedPreferences p=AppState.prefs(this);
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(12),dp(20),dp(18));
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        add(form,text("开启后，视频弹题的题干与选项会发送到 DeepSeek 官方 API，产生 API 费用。模型仍可能答错；不确定时停止。",13,muted,false),0);
        add(form,text("API Key（只保存在这台手机）",14,ink,true),14);
        EditText secret=new EditText(this);secret.setSingleLine(true);secret.setTextSize(14);
        secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setHint(KeyVault.exists(this)?"已保存；留空保留，输入新 Key 则替换":"填写 DeepSeek API Key");
        secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);add(form,secret,6);
        add(form,text("模型",14,ink,true),12);
        Spinner model=new Spinner(this);String[] models={"deepseek-flash","deepseek-v4-pro"};
        model.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,models));
        model.setSelection("deepseek-v4-pro".equals(p.getString("quiz_model","deepseek-flash"))?1:0);add(form,model,4);
        CheckBox enabled=new CheckBox(this);enabled.setText("自动选择并提交视频中途弹题");enabled.setTextColor(ink);
        enabled.setChecked(p.getBoolean("quiz_enabled",false));add(form,enabled,12);
        add(form,text("仅支持可完整读取的文字单选题。每次开始最多请求 20 道题；同一题不自动重试。保存后需点击“开始辅助”。",12,muted,false),8);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("DeepSeek 视频弹题")
            .setView(scroll).setPositiveButton("保存",null).setNegativeButton("取消",null).setNeutralButton("清除 Key",null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnShowListener(v->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
                String raw=secret.getText().toString().trim();
                try {
                    if(enabled.isChecked() && raw.isEmpty() && !KeyVault.exists(this)){secret.setError("请先填写 API Key");return;}
                    if(!raw.isEmpty())KeyVault.save(this,raw);
                    if(p.getBoolean("running",false))AppState.pause(this);
                    p.edit().putBoolean("quiz_enabled",enabled.isChecked()).putString("quiz_model",models[model.getSelectedItemPosition()]).apply();
                    secret.setText("");dialog.dismiss();
                    Toast.makeText(this,"已保存，请点击开始辅助后切回学习通",Toast.LENGTH_LONG).show();render();
                } catch(IllegalArgumentException e){secret.setError("API Key 格式不正确，请检查");}
                catch(Exception e){Toast.makeText(this,"本机密钥保存失败，请重新填写",Toast.LENGTH_LONG).show();}
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(button->{
                if(p.getBoolean("running",false))AppState.pause(this);
                KeyVault.clear(this);secret.setText("");dialog.dismiss();
                Toast.makeText(this,"已清除 Key 并关闭自动答题",Toast.LENGTH_LONG).show();render();
            });
        });
        dialog.show();
    }
    @Override protected void onResume() { super.onResume(); handler.removeCallbacks(update); handler.post(update); }
    @Override protected void onPause() { handler.removeCallbacks(update); super.onPause(); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (PlaybackService.instance != null) PlaybackService.instance.refreshNotification();
    }
    private void render() {
        SharedPreferences p = AppState.prefs(this); boolean connected = PlaybackService.instance != null, active = p.getBoolean("running", false);
        badge.setText(!connected ? "服务未启用" : active ? "辅助运行中" : "服务已就绪");
        status.setText(!connected ? "先启用服务，再点击开始辅助" : p.getString("status", "点击开始辅助"));
        String title = p.getString("video", ""), value = p.getString("progress", "");
        progress.setText(title.isEmpty() ? "打开学习通视频页后显示播放进度" : title + (value.isEmpty() ? "" : "\n" + value));
        enable.setText(connected ? "查看无障碍服务设置" : "启用无障碍服务");
        start.setEnabled(connected); start.setAlpha(connected ? 1f : 0.5f); start.setText(active ? "重新开始辅助" : "开始辅助");
        pause.setEnabled(active); pause.setAlpha(active ? 1f : 0.5f);
        log.setText(p.getString("log", "尚无操作记录"));
    }
}
