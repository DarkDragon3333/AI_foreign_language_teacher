package study.ai_foreign_language_teacher.speech;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Режет стрим reply_en на предложения и отдаёт готовые куски в TTS. */
public final class SpeakableSentences {

    private static final Pattern END = Pattern.compile("([.!?]+)(\\s+|$)");

    private final Consumer<String> onSentence;
    private final StringBuilder buf = new StringBuilder();

    public SpeakableSentences(Consumer<String> onSentence) {
        this.onSentence = onSentence;
    }

    public void accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        buf.append(delta);
        drain();
    }

    public void flush() {
        String tail = buf.toString().strip();
        buf.setLength(0);
        if (!tail.isEmpty()) {
            onSentence.accept(tail);
        }
    }

    private void drain() {
        List<String> ready = new ArrayList<>();
        Matcher matcher = END.matcher(buf);
        int last = 0;
        while (matcher.find()) {
            int end = matcher.end();
            String piece = buf.substring(last, end).strip();
            if (!piece.isEmpty()) {
                ready.add(piece);
            }
            last = end;
        }
        if (last > 0) {
            buf.delete(0, last);
        }
        for (String sentence : ready) {
            onSentence.accept(sentence);
        }
    }
}