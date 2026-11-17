package study.ai_foreign_language_teacher.memory.rules;

/** Превращает фразу ученика в числа. Null — поиск по смыслу сейчас недоступен. */
public interface QueryEmbedder {

    float[] embedQuery(String text);
}
