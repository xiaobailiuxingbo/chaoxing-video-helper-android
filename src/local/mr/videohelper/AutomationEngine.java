package local.mr.videohelper;

import java.util.List;

/** Local UI decisions. This class has no Android or network dependencies. */
public final class AutomationEngine {
    public static final class Node {
        public String text = "", desc = "", id = "", type = "", pkg = "";
        public int left, top, right, bottom, index;
        public boolean clickable, checked, checkable, selected, onScreen = true;
        public int parentIndex = -1;
        public boolean visible() { return onScreen && right > left && bottom > top; }
    }
    public static final class Config {
        public String rate = "1";
        public boolean consent = true, next = true;
    }
    public static final class Action {
        public final String kind, message;
        public int x, y, x2, y2, nodeIndex = -1;
        public long delay = 1100;
        Action(String kind, String message) { this.kind = kind; this.message = message; }
    }
    public String videoTitle = "", progress = "", completedTitle = "";
    public long completedAt;
    public int retries;
    private int consentStage, webFocus, scrollCount, previousSecond = -1;
    private String speedApplied = "";
    private long speedPending, lastReveal;
    private List<Node> nodes;
    private Config config;
    private long now;

    public static String normalize(String s) { return s.replace(" ", "").replace("、", "").replaceAll("_[0-9]+$", ""); }
    public static int seconds(String s) {
        if (s == null || !s.matches("[0-9]+(:[0-9]+){1,2}")) return 0;
        int result = 0;
        try { for (String part : s.split(":")) result = Math.addExact(Math.multiplyExact(result, 60), Integer.parseInt(part)); }
        catch (RuntimeException e) { return 0; }
        return result;
    }
    private Node id(String value) {
        for (Node n : nodes) if (n.id.equals(value) || n.id.equals("com.chaoxing.mobile:id/" + value)) return n;
        return null;
    }
    private Node text(String value) { for (Node n : nodes) if (n.text.equals(value)) return n; return null; }
    private Node desc(String value) { for (Node n : nodes) if (n.desc.equals(value)) return n; return null; }
    private boolean contains(String value) { for (Node n : nodes) if (n.text.contains(value)) return true; return false; }
    private String textOf(Node n) { return n == null ? "" : n.text; }
    private Action waitFor(String message) { return new Action("wait", message); }
    private Action stop(String message) { return new Action("stop", message); }
    private Action tap(String kind, Node n, String message) {
        if (n == null || !n.visible()) return waitFor("等待控件显示");
        Action a = new Action(kind, message);
        a.x = (n.left + n.right) / 2; a.y = (n.top + n.bottom) / 2; a.nodeIndex = n.index;
        return a;
    }
    private boolean completed() { return !completedTitle.isEmpty() && now >= completedAt && now - completedAt < 600000; }
    private Node web() { for (Node n : nodes) if (n.type.equals("android.webkit.WebView") && n.visible()) return n; return null; }
    private Node videoTab(boolean selectedOnly) {
        for (Node n : nodes) if (n.id.equals("com.chaoxing.mobile:id/tv_title") && n.text.contains("视频") && (!selectedOnly || n.selected)) return n;
        return null;
    }
    private Node retryButton() {
        for (Node n : nodes) if (n.text.equals("重试") || n.text.equals("点击重试") || n.text.equals("重新加载") || n.desc.equals("重试")) return n;
        return null;
    }
    private Node rateChoice() {
        for (Node n : nodes) {
            String s = n.text.trim().replace("倍速", "").replace("倍", "").replace("×", "").replace("x", "").replace("X", "").trim();
            if (s.equals("正常") || s.equals("正常速度")) s = "1";
            if (!s.matches("[0-9]+(\\.[0-9]+)?")) continue;
            try { if (Math.abs(Double.parseDouble(s) - Double.parseDouble(config.rate)) < 0.001 && n.visible()) return n; }
            catch (NumberFormatException ignored) { }
        }
        return null;
    }
    public void speedDenied(String rate) { speedPending = 0; speedApplied = videoTitle + ":" + rate; }

    public Action plan(List<Node> input, Config c, long wallTime) {
        nodes = input; config = c; now = wallTime;
        boolean inApp = false;
        for (Node n : nodes) if (n.pkg.equals("com.chaoxing.mobile")) { inApp = true; break; }
        if (!inApp) return waitFor("等待学习通视频页进入前台");

        if (id("faceProtocol") != null && contains("摄像头进行抓拍")) {
            if (!c.consent) return stop("请本人确认课程监控提示后再开始");
            Node dialog = id("confirmOk"), label = text("请先阅读并勾选");
            if (dialog == null || label == null || !dialog.visible()) return stop("监控提示结构改变，请人工确认");
            if (consentStage == 0) {
                boolean checked = false;
                for (Node n : nodes) if (n.checkable && n.checked && n.top >= dialog.top && n.bottom <= dialog.bottom) checked = true;
                if (!checked) {
                    Action a = new Action("consent_check", "勾选已授权的课程监控协议");
                    a.x = label.left - (dialog.right - dialog.left) / 20;
                    a.y = (label.top + label.bottom) / 2; a.delay = 500;
                    return a;
                }
                consentStage = 1;
            }
            if (consentStage >= 3) return stop("监控确认没有生效，请本人检查");
            Action a = new Action("consent_ok", "确认已授权的课程监控提示");
            a.x = dialog.left + (dialog.right - dialog.left) * 3 / 4;
            a.y = dialog.bottom - (dialog.right - dialog.left) * 85 / 1000; a.delay = 1500;
            return a;
        }
        consentStage = 0;
        for (Node n : nodes) {
            if(!n.visible())continue;
            if (n.text.contains("人脸信息采集") || n.text.contains("活体检测") || n.text.contains("请正对摄像头") || n.text.contains("身份验证") || n.text.contains("请进行人脸识别"))
                return stop("需要本人完成人脸或身份验证");
            if (n.text.equals("登录") || n.text.equals("重新登录") || n.text.equals("账号登录") || n.text.equals("密码登录")) return stop("请重新登录学习通");
        }
        if (VideoQuiz.present(nodes)) return new Action("video_quiz", "检测到视频中途弹题");
        Node message = id("tvMessage");
        if (message != null && message.text.contains("当前章节还有任务点未完成，是否去完成")) {
            Node skip = id("btnCancel");
            if (completed() && skip != null && skip.text.equals("下一章节")) return tap("skip_tasks", skip, "保留测验未作答，进入下一章节");
            return stop("任务提示需要人工确认；未观察到本节视频播放结束");
        }
        Node surface = id("surface_container"), chapter = id("tv_chapter_name");
        Node retry = retryButton();
        boolean knownError = message != null && message.text.contains("视频加载失败") && message.text.contains("重试切换线路")
            && retry != null && retry.id.equals("com.chaoxing.mobile:id/btnOk");
        if (retry != null && (knownError || surface != null || (chapter != null && videoTab(true) != null))) {
            if (retries >= 3) return stop("连续重试三次仍未恢复，请检查网络后重新开始");
            Action a = tap("retry", retry, "加载失败，重试切换播放线路"); a.delay = 5000; return a;
        }
        if (speedPending > 0) {
            Node choice = rateChoice();
            if (choice != null) return tap("speed_choice", choice, "选择 " + c.rate + " 倍速");
            if (now - speedPending < 4000) return waitFor("等待倍速菜单");
            speedDenied(c.rate);
        }
        if (surface != null) {
            String title = textOf(id("title"));
            if (!title.isEmpty() && !title.equals(videoTitle)) {
                videoTitle = title; speedApplied = ""; previousSecond = -1; retries = 0;
            }
            String elapsed = textOf(id("land_current_time")), duration = textOf(id("land_total_time"));
            int current = seconds(elapsed), total = seconds(duration);
            if (total > 0) {
                progress = elapsed + " / " + duration;
                if (current > previousSecond && previousSecond >= 0) retries = 0;
                previousSecond = current;
                if (current >= total) {
                    if (!c.next) return waitFor("当前视频已播完");
                    return new Action("back", "视频已到结尾，返回章节页");
                }
            }
            Node speed = id("speed");
            if (c.rate.equals("1") && speed != null && speed.text.equals("倍速")) speedApplied = videoTitle + ":1";
            if (speed != null && speed.visible() && speed.clickable && !speedApplied.equals(videoTitle + ":" + c.rate)) {
                Action a = tap("speed_open", speed, "打开播放器倍速菜单"); a.delay = 400; return a;
            }
            if (id("land_current_time") == null && now - lastReveal >= 12000) {
                Action a = new Action("reveal", "读取视频播放进度");
                a.x = surface.left + (surface.right - surface.left) / 4;
                a.y = surface.top + (surface.bottom - surface.top) / 3; a.delay = 300;
                return a;
            }
            return waitFor("正在辅助播放；请保持学习通前台");
        }
        if (chapter != null) {
            String currentTitle = normalize(chapter.text);
            if (!completedTitle.isEmpty() && (!currentTitle.equals(completedTitle) || !completed())) {
                completedTitle = ""; completedAt = 0;
            }
            Node view = web(), next = id("nextChapter"), play = desc("播放");
            boolean taskLabel = false;
            for (Node n : nodes) if (n.text.startsWith("任务点")) taskLabel = true;
            if (view != null && next == null && play == null && !taskLabel) {
                if (webFocus >= 3) return stop("章节控件无法读取，请重新进入视频页");
                Action a = new Action("focus_web", "等待章节视频控件加载");
                a.x = (view.left + view.right) / 2; a.y = view.top + (view.bottom - view.top) / 20; a.delay = 500;
                return a;
            }
            webFocus = 0;
            if (completed()) {
                if (next == null) return stop("没有找到下一节，已停止");
                if (!next.visible()) {
                    if (view == null || scrollCount >= 6) return stop("下一节按钮不可见，请人工检查");
                    Action a = new Action("scroll_next", "滚动到下一节；测验保持未作答");
                    a.x = view.left + (view.right - view.left) / 5;
                    a.y = view.bottom - (view.bottom - view.top) / 8;
                    a.x2 = a.x; a.y2 = view.top + (view.bottom - view.top) / 5; a.delay = 850;
                    return a;
                }
                Action a = tap("next", next, "进入下一节");
                Node note = id("writeNotes");
                if (note != null && a.x >= note.left && a.x <= note.right && a.y >= note.top && a.y <= note.bottom) {
                    if (note.left - next.left >= 12) a.x = (next.left + note.left) / 2;
                    else if (next.right - note.right >= 12) a.x = (next.right + note.right) / 2;
                    else return stop("请先收起遮住下一节的写笔记浮窗");
                    a.nodeIndex = -1;
                }
                return a;
            }
            if (videoTab(true) == null) {
                Node tab = videoTab(false);
                if (tab == null) return stop("本节没有视频，请选择后续视频章节");
                return tap("video_tab", tab, "切换到视频标签页");
            }
            if (play != null) return tap("play", play, "开始播放当前视频");
            return waitFor("等待视频加载");
        }
        return waitFor("请打开学习通课程的视频页");
    }

    public void performed(Action a, Config c, long wallTime) {
        if (a.kind.equals("consent_check")) consentStage = 1;
        else if (a.kind.equals("consent_ok")) consentStage++;
        else if (a.kind.equals("retry")) retries++;
        else if (a.kind.equals("focus_web")) webFocus++;
        else if (a.kind.equals("scroll_next")) scrollCount++;
        else if (a.kind.equals("next")) scrollCount = 0;
        else if (a.kind.equals("speed_open")) speedPending = wallTime;
        else if (a.kind.equals("speed_choice")) speedDenied(c.rate);
        else if (a.kind.equals("reveal")) lastReveal = wallTime;
        else if (a.kind.equals("back")) { completedTitle = normalize(videoTitle); completedAt = wallTime; }
    }
}
