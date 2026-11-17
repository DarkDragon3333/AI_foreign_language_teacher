package study.ai_foreign_language_teacher.export;

import study.ai_foreign_language_teacher.domain.RuleCard;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** CSV для импорта в Anki (поля Front, Back, Tags). */
public final class AnkiCsv {

    private static final byte[] BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private AnkiCsv() {
    }

    public static byte[] export(List<RuleCard> cards) {
        StringBuilder sb = new StringBuilder("Front,Back,Tags\n");
        for (RuleCard card : cards) {
            String front = escape(card.titleRu() + " — как сказать правильно?\n✗ " + card.exampleBad());
            String back = escape(card.ruleRu() + "\n✓ " + card.exampleOk() + "\n" + card.l1Trap());
            String tags = escape(card.slug().replace('_', '-') + " cefr-" + card.cefrMin().toLowerCase());
            sb.append(front).append(',').append(back).append(',').append(tags).append('\n');
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return out;
    }

    static String escape(String value) {
        if (value == null) {
            return "\"\"";
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        String inner = value.replace("\"", "\"\"");
        return quote ? "\"" + inner + "\"" : inner;
    }
}