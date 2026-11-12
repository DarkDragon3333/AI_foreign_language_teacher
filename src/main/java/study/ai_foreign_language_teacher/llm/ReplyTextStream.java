package study.ai_foreign_language_teacher.llm;

import java.util.function.Consumer;

/**
 * Вытаскивает значение строкового поля (reply_en) из JSON, который приходит кусками,
 * и отдаёт его по мере поступления — чтобы ученик видел ответ сразу, а не после всего JSON.
 * Не потокобезопасен: один экземпляр на один ответ.
 */
public final class ReplyTextStream implements Consumer<String> {

    private enum State { SEEK_KEY, SEEK_COLON, SEEK_QUOTE, IN_VALUE, DONE }

    private final String keyToken;
    private final Consumer<String> sink;
    private final StringBuilder buffer = new StringBuilder();
    private int pos = 0;
    private State state = State.SEEK_KEY;

    public ReplyTextStream(String field, Consumer<String> sink) {
        this.keyToken = "\"" + field + "\"";
        this.sink = sink;
    }

    @Override
    public void accept(String chunk) {
        if (state == State.DONE || chunk == null || chunk.isEmpty()) {
            return;
        }
        buffer.append(chunk);
        StringBuilder out = new StringBuilder();
        boolean progress = true;
        while (progress && state != State.DONE) {
            progress = step(out);
        }
        if (out.length() > 0) {
            sink.accept(out.toString());
        }
    }

    /** @return false, если для продолжения нужно дождаться следующего куска */
    private boolean step(StringBuilder out) {
        switch (state) {
            case SEEK_KEY -> {
                int idx = buffer.indexOf(keyToken, pos);
                if (idx < 0) {
                    pos = Math.max(pos, buffer.length() - keyToken.length());
                    return false;
                }
                pos = idx + keyToken.length();
                state = State.SEEK_COLON;
                return true;
            }
            case SEEK_COLON, SEEK_QUOTE -> {
                while (pos < buffer.length() && Character.isWhitespace(buffer.charAt(pos))) {
                    pos++;
                }
                if (pos >= buffer.length()) {
                    return false;
                }
                char expected = state == State.SEEK_COLON ? ':' : '"';
                if (buffer.charAt(pos) != expected) {
                    // значение не строка (null и т.п.) или это было не имя поля — стримить нечего
                    state = State.DONE;
                    return false;
                }
                pos++;
                state = state == State.SEEK_COLON ? State.SEEK_QUOTE : State.IN_VALUE;
                return true;
            }
            case IN_VALUE -> {
                while (pos < buffer.length()) {
                    char c = buffer.charAt(pos);
                    if (c == '"') {
                        state = State.DONE;
                        return false;
                    }
                    if (c != '\\') {
                        out.append(c);
                        pos++;
                        continue;
                    }
                    if (pos + 1 >= buffer.length()) {
                        return false; // экранирование разорвано между кусками
                    }
                    char e = buffer.charAt(pos + 1);
                    if (e == 'u') {
                        if (pos + 6 > buffer.length()) {
                            return false;
                        }
                        try {
                            out.append((char) Integer.parseInt(buffer.substring(pos + 2, pos + 6), 16));
                        } catch (NumberFormatException ignored) {
                            // битый unicode-escape пропускаем
                        }
                        pos += 6;
                        continue;
                    }
                    out.append(switch (e) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        default -> e; // кавычка, обратная и прямая косая черта
                    });
                    pos += 2;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }
}
