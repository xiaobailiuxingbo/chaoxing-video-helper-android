package local.mr.videohelper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class QuizKey {
    public static String normalize(String text) {
        return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC).trim().replaceAll("\\s+"," ");
    }
    public static String hash(String value) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void append(StringBuilder out,String value){out.append(value.length()).append(':').append(value);}
    public static String question(VideoQuiz question) {
        StringBuilder identity=new StringBuilder();append(identity,normalize(question.type));append(identity,normalize(question.stem));
        List<String> options=new ArrayList<>();for(VideoQuiz.Option o:question.options)options.add(normalize(o.text));Collections.sort(options);
        for(String option:options)append(identity,option);return hash(identity.toString());
    }
    public static String labelForText(VideoQuiz q,String text) {
        String found="", wanted=normalize(text);
        for(VideoQuiz.Option option:q.options)if(normalize(option.text).equals(wanted)) {
            if(!found.isEmpty())return "";found=option.label;
        }
        return found;
    }
}
