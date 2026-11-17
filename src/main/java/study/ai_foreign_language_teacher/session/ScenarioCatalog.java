package study.ai_foreign_language_teacher.session;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.RoleScenario;
import study.ai_foreign_language_teacher.domain.ScenarioGoal;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class ScenarioCatalog {

    private final List<RoleScenario> scenarios;
    private final Map<String, RoleScenario> bySlug;

    public ScenarioCatalog(ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource("scenarios/scenarios.json").getInputStream()) {
            List<RoleScenario> loaded = mapper.readValue(in, new TypeReference<List<RoleScenario>>() {
            });
            Map<String, RoleScenario> slugs = new LinkedHashMap<>();
            for (RoleScenario s : loaded) {
                validate(s);
                if (slugs.put(s.slug(), s) != null) {
                    throw new IllegalStateException("Повторяется slug сценария: " + s.slug());
                }
            }
            this.scenarios = loaded.stream().sorted(Comparator.comparing(RoleScenario::titleRu)).toList();
            this.bySlug = Map.copyOf(slugs);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать scenarios/scenarios.json", e);
        }
    }

    public List<RoleScenario> all() {
        return scenarios;
    }

    public Optional<RoleScenario> find(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(bySlug.get(slug.strip().toLowerCase(Locale.ROOT)));
    }

    private static void validate(RoleScenario s) {
        require(s.slug(), "slug");
        if (!s.slug().matches("[a-z0-9_]+")) {
            throw new IllegalStateException("Плохой slug сценария: " + s.slug());
        }
        Cefr.parse(s.cefrMin());
        Cefr.parse(s.cefrMax());
        require(s.title(), s.slug() + ".title");
        require(s.titleRu(), s.slug() + ".titleRu");
        require(s.setting(), s.slug() + ".setting");
        require(s.teacherRole(), s.slug() + ".teacherRole");
        require(s.learnerRole(), s.slug() + ".learnerRole");
        if (s.goals().isEmpty()) {
            throw new IllegalStateException("У сценария " + s.slug() + " нет целей");
        }
        List<String> ids = new ArrayList<>();
        for (ScenarioGoal g : s.goals()) {
            require(g.id(), s.slug() + ".goal.id");
            if (!g.id().matches("[a-z0-9_]+")) {
                throw new IllegalStateException("Плохой id цели: " + g.id());
            }
            if (ids.contains(g.id())) {
                throw new IllegalStateException("Повтор id цели в " + s.slug() + ": " + g.id());
            }
            ids.add(g.id());
            require(g.textRu(), g.id() + ".textRu");
            require(g.textEn(), g.id() + ".textEn");
            if (g.hints().isEmpty()) {
                throw new IllegalStateException("У цели " + g.id() + " нет hints");
            }
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Пустое поле сценария: " + field);
        }
    }
}