package study.ai_foreign_language_teacher.memory.rules;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.RuleCard;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Учебник в памяти: текст карточек из resources/rules, векторы подставляются, когда их посчитает CPU-модель. */
@Component
public class CardCatalog {

    private final List<RuleCard> cards;
    private final Map<String, RuleCard> bySlug;
    private final Map<String, List<RuleCard>> byLtRule;
    private final Map<String, String> hashes;
    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();

    public CardCatalog(ObjectMapper mapper) {
        List<RuleCard> loaded = new ArrayList<>();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver(CardCatalog.class.getClassLoader())
                    .getResources("classpath:rules/*.json");
            if (files.length == 0) {
                throw new IllegalStateException("В resources/rules нет карточек правил");
            }
            for (Resource file : files) {
                try (InputStream in = file.getInputStream()) {
                    List<RuleCard> batch = mapper.readValue(in, new TypeReference<List<RuleCard>>() {
                    });
                    loaded.addAll(batch);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать карточки правил", e);
        }
        loaded.sort(Comparator.comparing(RuleCard::slug));
        Map<String, RuleCard> slugs = new LinkedHashMap<>();
        Map<String, List<RuleCard>> byRule = new LinkedHashMap<>();
        Map<String, String> hash = new LinkedHashMap<>();
        for (RuleCard card : loaded) {
            validate(card);
            if (slugs.put(card.slug(), card) != null) {
                throw new IllegalStateException("Повторяется slug карточки: " + card.slug());
            }
            hash.put(card.slug(), sha256(card.hashSource()));
            for (String ruleId : card.ltRules()) {
                byRule.computeIfAbsent(ruleId.toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(card);
            }
        }
        this.cards = List.copyOf(slugs.values());
        this.bySlug = Map.copyOf(slugs);
        this.byLtRule = Map.copyOf(byRule);
        this.hashes = Map.copyOf(hash);
    }

    public List<RuleCard> all() {
        return cards;
    }

    public Optional<RuleCard> find(String slug) {
        return Optional.ofNullable(bySlug.get(slug));
    }

    public List<RuleCard> byLtRule(String ruleId) {
        if (ruleId == null) {
            return List.of();
        }
        return byLtRule.getOrDefault(ruleId.toUpperCase(Locale.ROOT), List.of());
    }

    public String hash(String slug) {
        return hashes.get(slug);
    }

    public Map<String, String> hashes() {
        return hashes;
    }

    public void setVector(String slug, float[] vector) {
        vectors.put(slug, vector);
    }

    public float[] vector(String slug) {
        return vectors.get(slug);
    }

    private static void validate(RuleCard card) {
        require(card.slug(), "slug");
        if (!card.slug().matches("[a-z0-9_]+")) {
            throw new IllegalStateException("Плохой slug: " + card.slug());
        }
        Cefr min = Cefr.parse(card.cefrMin());
        Cefr max = Cefr.parse(card.cefrMax());
        if (min.ordinal() > max.ordinal()) {
            throw new IllegalStateException("У карточки " + card.slug() + " cefrMin выше cefrMax");
        }
        require(card.title(), card.slug() + ".title");
        require(card.titleRu(), card.slug() + ".titleRu");
        require(card.rule(), card.slug() + ".rule");
        require(card.ruleRu(), card.slug() + ".ruleRu");
        require(card.exampleOk(), card.slug() + ".exampleOk");
        require(card.exampleBad(), card.slug() + ".exampleBad");
        require(card.l1Trap(), card.slug() + ".l1Trap");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Пустое поле карточки: " + field);
        }
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
