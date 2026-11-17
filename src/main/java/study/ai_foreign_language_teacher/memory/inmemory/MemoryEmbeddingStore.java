package study.ai_foreign_language_teacher.memory.inmemory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.memory.EmbeddingStore;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Векторы живут только до остановки процесса. Для профиля memory этого достаточно. */
@Repository
@Profile("memory")
public class MemoryEmbeddingStore implements EmbeddingStore {

    private final Map<String, Row> rows = new ConcurrentHashMap<>();

    @Override
    public Map<String, float[]> loadMatching(Map<String, String> hashBySlug) {
        Map<String, float[]> out = new HashMap<>();
        rows.forEach((slug, row) -> {
            if (row.vector != null && row.hash.equals(hashBySlug.get(slug))) {
                out.put(slug, row.vector);
            }
        });
        return out;
    }

    @Override
    public void upsert(RuleCard card, String hash) {
        rows.compute(card.slug(), (slug, row) -> {
            if (row != null && row.hash.equals(hash)) {
                return row;
            }
            return new Row(hash, null);
        });
    }

    @Override
    public void saveVector(String slug, String hash, float[] vector) {
        rows.computeIfPresent(slug, (key, row) -> row.hash.equals(hash) ? new Row(hash, vector) : row);
    }

    private record Row(String hash, float[] vector) {
    }
}
