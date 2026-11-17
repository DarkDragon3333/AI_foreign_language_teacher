package study.ai_foreign_language_teacher.memory.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.config.TutorProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Клиент sidecar-модели эмбеддингов. Ходит на CPU и не трогает видеокарту.
 * Если сервис молчит, урок не ждёт: поиск остаётся по правилам LanguageTool и по словам.
 */
@Component
public class EmbeddingClient implements QueryEmbedder {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final Duration CONNECT = Duration.ofMillis(400);

    private final TutorProperties.Embed cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private volatile Instant pauseUntil = Instant.EPOCH;

    public EmbeddingClient(TutorProperties props, ObjectMapper mapper) {
        this.cfg = props.embed();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT).build();
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    /** Короткий запрос во время урока. После сбоя молчим полминуты, чтобы не прибавлять задержку каждому ходу. */
    public float[] embedQuery(String text) {
        if (!cfg.enabled() || text == null || text.isBlank() || Instant.now().isBefore(pauseUntil)) {
            return null;
        }
        try {
            List<float[]> vectors = embed(List.of(text.strip()), "query", cfg.timeout());
            return vectors.isEmpty() ? null : vectors.get(0);
        } catch (Exception e) {
            pauseUntil = Instant.now().plusSeconds(30);
            log.info("Поиск по смыслу недоступен, карточки ищутся по словам: {}", e.toString());
            return null;
        }
    }

    public boolean ready() {
        if (!cfg.enabled()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"up\"");
        } catch (Exception e) {
            return false;
        }
    }

    /** Пакет для индекса карточек. Таймаут длиннее: первая загрузка модели считает сразу много фраз. */
    public List<float[]> embedPassages(List<String> texts) throws Exception {
        return embed(texts, "passage", Duration.ofSeconds(120));
    }

    public String statusDetail() {
        if (!cfg.enabled()) {
            return "выключен";
        }
        return ready() ? cfg.baseUrl() : "не отвечает, поиск по словам";
    }

    private List<float[]> embed(List<String> texts, String kind, Duration timeout) throws Exception {
        String body = mapper.writeValueAsString(Map.of("texts", texts, "kind", kind));
        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/embed"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("embed HTTP " + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode vectors = root.path("vectors");
        if (!vectors.isArray() || vectors.size() != texts.size()) {
            throw new IllegalStateException("embed вернул не тот размер");
        }
        List<float[]> out = new ArrayList<>();
        for (JsonNode vector : vectors) {
            float[] row = new float[vector.size()];
            if (row.length != cfg.dimensions()) {
                throw new IllegalStateException("Ожидалось " + cfg.dimensions() + " чисел, пришло " + row.length);
            }
            for (int i = 0; i < row.length; i++) {
                row[i] = (float) vector.get(i).asDouble();
            }
            out.add(row);
        }
        return out;
    }
}
