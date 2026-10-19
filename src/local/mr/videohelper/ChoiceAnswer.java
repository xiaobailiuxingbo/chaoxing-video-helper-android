package local.mr.videohelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared validation of one choice or a complete multiple-choice combination. */
public final class ChoiceAnswer {
    public static boolean multiple(VideoQuiz q){return q.context.equals("chapter") && q.type.equals("多选题");}
    public static String labels(VideoQuiz q,String encoded) {
        if(encoded==null || !encoded.matches("[A-H]{1,8}") || (!multiple(q) && encoded.length()!=1))return "";
        java.util.Set<Character> seen=new java.util.TreeSet<>();
        for(char label:encoded.toCharArray())if(q.option(String.valueOf(label))==null || !seen.add(label))return "";
        StringBuilder out=new StringBuilder();for(char label:seen)out.append(label);return out.toString();
    }
    public static List<String> texts(VideoQuiz q,String encoded) {
        String labels=labels(q,encoded);List<String> out=new ArrayList<>();
        for(char label:labels.toCharArray())out.add(QuizKey.normalize(q.option(String.valueOf(label)).text));return out;
    }
    public static String display(VideoQuiz q,String encoded){return String.join("；",texts(q,encoded));}
    public static String key(VideoQuiz q,String encoded) {
        List<String> texts=texts(q,encoded);if(texts.isEmpty())return "";if(!multiple(q))return texts.get(0);
        Collections.sort(texts);StringBuilder out=new StringBuilder("[");
        for(String text:texts) {
            if(out.length()>1)out.append(',');out.append('"');
            for(char c:text.toCharArray()) {
                if(c=='\\' || c=='"')out.append('\\').append(c);
                else if(c<32)out.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else out.append(c);
            }
            out.append('"');
        }
        return out.append(']').toString();
    }
}
