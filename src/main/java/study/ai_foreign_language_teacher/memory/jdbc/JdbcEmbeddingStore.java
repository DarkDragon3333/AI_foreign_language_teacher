package study.ai_foreign_language_teacher.memory.jdbc;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.memory.EmbeddingStore;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/** Текст карточек и их векторы в Postgres. */
@Repository
@Profile("!memory")
public class JdbcEmbeddingStore implements EmbeddingStore {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcEmbeddingStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Map<String, float[]> loadMatching(Map<String, String> hashBySlug) {
        Map<String, float[]> out = new HashMap<>();
        jdbc.sql("SELECT slug, content_hash, embedding::text AS embedding FROM rule_cards WHERE embedding IS NOT NULL")
                .query((rs, i) -> {
                    String slug = rs.getString("slug");
                    String hash = rs.getString("content_hash");
                    if (hash != null && hash.equals(hashBySlug.get(slug))) {
                        out.put(slug, parse(rs.getString("embedding")));
                    }
                    return slug;
                }).list();
        return out;
    }

    @Override
    public void upsert(RuleCard card, String hash) {
        jdbc.sql("""
                        INSERT INTO rule_cards (slug, cefr_min, cefr_max, title, title_ru, rule_text, rule_ru,
                                                example_ok, example_bad, l1_trap, lt_rules, keywords, content_hash)
                        VALUES (:slug, :cefrMin, :cefrMax, :title, :titleRu, :rule, :ruleRu,
                                :exampleOk, :exampleBad, :l1Trap, CAST(:ltRules AS jsonb), CAST(:keywords AS jsonb), :hash)
                        ON CONFLICT (slug) DO UPDATE SET
                            cefr_min = EXCLUDED.cefr_min,
                            cefr_max = EXCLUDED.cefr_max,
                            title = EXCLUDED.title,
                            title_ru = EXCLUDED.title_ru,
                            rule_text = EXCLUDED.rule_text,
                            rule_ru = EXCLUDED.rule_ru,
                            example_ok = EXCLUDED.example_ok,
                            example_bad = EXCLUDED.example_bad,
                            l1_trap = EXCLUDED.l1_trap,
                            lt_rules = EXCLUDED.lt_rules,
                            keywords = EXCLUDED.keywords,
                            embedding = CASE WHEN rule_cards.content_hash = EXCLUDED.content_hash
                                             THEN rule_cards.embedding ELSE NULL END,
                            content_hash = EXCLUDED.content_hash""")
                .param("slug", card.slug())
                .param("cefrMin", card.cefrMin())
                .param("cefrMax", card.cefrMax())
                .param("title", card.title())
                .param("titleRu", card.titleRu())
                .param("rule", card.rule())
                .param("ruleRu", card.ruleRu())
                .param("exampleOk", card.exampleOk())
                .param("exampleBad", card.exampleBad())
                .param("l1Trap", card.l1Trap())
                .param("ltRules", mapper.writeValueAsString(card.ltRules()))
                .param("keywords", mapper.writeValueAsString(card.keywords()))
                .param("hash", hash)
                .update();
    }

    @Override
    public void saveVector(String slug, String hash, float[] vector) {
        jdbc.sql("""
                        UPDATE rule_cards SET embedding = CAST(:embedding AS vector)
                        WHERE slug = :slug AND content_hash = :hash""")
                .param("embedding", format(vector))
                .param("slug", slug)
                .param("hash", hash)
                .update();
    }

    static String format(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    static float[] parse(String text) {
        String body = text == null ? "" : text.strip();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].strip());
        }
        return vector;
    }
}
