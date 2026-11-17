package study.ai_foreign_language_teacher.memory;

import study.ai_foreign_language_teacher.domain.RuleCard;

import java.util.Map;

/** Сохранённые векторы карточек, чтобы не пересчитывать их при каждом запуске. */
public interface EmbeddingStore {

    /** slug → вектор, только если текст карточки с тех пор не менялся. */
    Map<String, float[]> loadMatching(Map<String, String> hashBySlug);

    void upsert(RuleCard card, String hash);

    void saveVector(String slug, String hash, float[] vector);
}
