package study.ai_foreign_language_teacher.nlp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.config.TutorProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Клиент LanguageTool (POST /v2/check). Работает на CPU, без n-gram. */
@Component
public class LanguageToolClient implements GrammarChecker {

    private static final Logger log = LoggerFactory.getLogger(LanguageToolClient.class);
    private static final int MAX_MATCHES = 6;
    private static final int MAX_REPLACEMENTS = 3;

    private final TutorProperties.LanguageTool cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public LanguageToolClient(TutorProperties props, ObjectMapper mapper) {
        this.cfg = props.languagetool();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public List<LtMatch> check(String text) {
        if (!cfg.enabled() || text == null || text.isBlank() || !looksEnglish(text)) {
            return List.of();
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("text", text);
        form.put("language", cfg.language());
        form.put("motherTongue", "ru"); // включает правила про «ложных друзей переводчика»
        // в чате регистр и типографика — шум: ученик пишет без заглавных и точек
        form.put("disabledCategories", "CASING,TYPOGRAPHY");
        try {
            HttpResponse<String> response = http.send(post("/v2/check", form), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("LanguageTool returned HTTP {}: {}", response.statusCode(), response.body());
                return List.of();
            }
            return parse(text, mapper.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            // урок не должен падать из-за проверки: без LT учитель просто исправляет осторожнее
            log.warn("LanguageTool is unavailable ({}), continuing without it", e.toString());
            return List.of();
        }
    }

    private List<LtMatch> parse(String text, JsonNode root) {
        List<LtMatch> matches = new ArrayList<>();
        for (JsonNode m : root.path("matches")) {
            int offset = m.path("offset").asInt(-1);
            int length = m.path("length").asInt(0);
            if (offset < 0 || length <= 0 || offset + length > text.length()) {
                continue;
            }
            List<String> replacements = new ArrayList<>();
            for (JsonNode r : m.path("replacements")) {
                String value = r.path("value").asString("");
                if (!value.isBlank() && replacements.size() < MAX_REPLACEMENTS) {
                    replacements.add(value);
                }
            }
            JsonNode rule = m.path("rule");
            String ruleId = rule.path("id").asString("UNKNOWN");
            boolean spelling = LtMatch.isSpellingRule(ruleId);
            String span = text.substring(offset, offset + length);
            // «Junit → JUnit», «russian → Russian»: регистр в чате не считаем ошибкой
            if (spelling && replacements.stream().anyMatch(span::equalsIgnoreCase)) {
                continue;
            }
            matches.add(new LtMatch(
                    ruleId,
                    spelling ? "Spelling" : rule.path("category").path("name").asString("Grammar"),
                    spelling ? "Spelling" : rule.path("description").asString(""),
                    m.path("message").asString(""),
                    offset,
                    length,
                    text.substring(offset, offset + length),
                    List.copyOf(replacements)));
            if (matches.size() == MAX_MATCHES) {
                break;
            }
        }
        return matches;
    }

    /** Русскую реплику («не понимаю») английским правилам отдавать бессмысленно. */
    static boolean looksEnglish(String text) {
        long letters = text.chars().filter(Character::isLetter).count();
        long latin = text.chars().filter(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')).count();
        return letters > 0 && latin * 2 > letters;
    }

    @Override
    public Status status() {
        if (!cfg.enabled()) {
            return new Status(true, "disabled");
        }
        try {
            Map<String, String> form = Map.of("text", "ok", "language", cfg.language());
            HttpResponse<String> response = http.send(post("/v2/check", form), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? new Status(true, cfg.language())
                    : new Status(false, "HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(false, "interrupted");
        } catch (Exception e) {
            return new Status(false, "unreachable (" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + ")");
        }
    }

    private HttpRequest post(String path, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return HttpRequest.newBuilder(URI.create(cfg.baseUrl() + path))
                .timeout(cfg.timeout())
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
