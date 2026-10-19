package local.mr.videohelper;

import java.util.List;

public final class QuizFeedback {
    public static int text(String raw) {
        String s=raw==null?"":raw.replaceAll("\\s+","");
        if(s.contains("回答错误") || s.contains("答案错误") || s.contains("答题错误") || s.contains("回答不正确") || s.contains("答错了") || s.contains("答题失败"))return -1;
        if(s.contains("回答正确") || s.contains("答题正确") || s.contains("答案正确"))return 1;
        return 0;
    }
    public static int detect(List<AutomationEngine.Node> nodes) {
        VideoQuiz question=VideoQuiz.read(nodes);
        int found=0;
        for(AutomationEngine.Node n:nodes)if(n.visible()) {
            if(question!=null && question.contentNodes.contains(n.index))continue;
            if(question==null && !n.id.equals("com.chaoxing.mobile:id/tvMessage"))continue;
            int value=text(n.text);if(value<0)return -1;if(value>0)found=1;
        }
        return found;
    }
}
