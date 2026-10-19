package local.mr.videohelper;

import java.util.List;

/** Read only the course home header; chapter/player titles are not course names. */
public final class CourseContext {
    public static String detect(List<AutomationEngine.Node> nodes) {
        boolean courseHome=false;
        String title="";
        for(AutomationEngine.Node n:nodes) {
            if(!n.visible() || !n.pkg.equals("com.chaoxing.mobile"))continue;
            if(n.id.equals("com.chaoxing.mobile:id/vp_course"))courseHome=true;
            if(n.id.equals("com.chaoxing.mobile:id/toolbar_tv_title"))title=n.text.trim();
        }
        if(!courseHome || title.isEmpty() || title.length()>200 || title.equals("课程") || title.equals("章节"))return "";
        return title;
    }
}
