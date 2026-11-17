package study.ai_foreign_language_teacher.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.config.TutorProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Клиент Ollama (/api/chat, стриминг NDJSON).
 * Семафор на 1 разрешение — жёсткое правило архитектуры: один одновременный запрос к LLM, иначе OOM VRAM.
 */
@Component
public class OllamaClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final TutorProperties.Ollama cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Semaphore slot = new Semaphore(1, true);

    public OllamaClient(TutorProperties props, ObjectMapper mapper) {
        this.cfg = props.ollama();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Модели, которые ответили, что не умеют «думать»: им параметр think больше не шлём. */
    private final Set<String> noThinkParam = ConcurrentHashMap.newKeySet();

    @Override
    public Call chat(String model, List<LlmMessage> messages, double temperature, Map<String, Object> format,
                     Integer seed, Consumer<String> onChunk) {
        String name = model == null || model.isBlank() ? cfg.model() : model;
        acquireSlot();
        try {
            try {
                return send(name, messages, temperature, format, seed, onChunk, cfg.disableThinking() && !noThinkParam.contains(name));
            } catch (ThinkingUnsupported e) {
                noThinkParam.add(name);
                return send(name, messages, temperature, format, seed, onChunk, false);
            }
        } finally {
            slot.release();
        }
    }

    private Call send(String model, List<LlmMessage> messages, double temperature, Map<String, Object> format,
                      Integer seed, Consumer<String> onChunk, boolean sendThinkOff) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", temperature);
        options.put("num_ctx", cfg.numCtx());
        options.put("num_predict", cfg.numPredict());
        options.put("repeat_penalty", 1.15); // маленькие модели склонны зацикливаться
        if (seed != null) {
            options.put("seed", seed);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        if (sendThinkOff) {
            body.put("think", false);
        }
        if (format != null) {
            body.put("format", format);
        }
        body.put("options", options);
        body.put("keep_alive", cfg.keepAlive());

        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/api/chat"))
                .timeout(cfg.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        long started = System.nanoTime();
        try {
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            try (Stream<String> lines = response.body()) {
                if (response.statusCode() != 200) {
                    String error = String.join(" ", lines.limit(5).toList());
                    if (sendThinkOff && error.toLowerCase().contains("think")) {
                        throw new ThinkingUnsupported();
                    }
                    throw new LlmException("Ollama returned HTTP " + response.statusCode() + ": " + error);
                }
                StringBuilder full = new StringBuilder();
                long evalTokens = 0;
                long evalNanos = 0;
                long loadNanos = 0;
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = mapper.readTree(line);
                    if (node.has("error")) {
                        throw new LlmException("Ollama error: " + node.path("error").asString());
                    }
                    String chunk = node.path("message").path("content").asString("");
                    if (!chunk.isEmpty()) {
                        full.append(chunk);
                        if (onChunk != null) {
                            onChunk.accept(chunk);
                        }
                    }
                    if (node.path("done").asBoolean(false)) {
                        evalTokens = node.path("eval_count").asLong(0);
                        evalNanos = node.path("eval_duration").asLong(0);
                        loadNanos = node.path("load_duration").asLong(0);
                        break;
                    }
                }
                log.info("LLM turn [{}]: {} chars in {} ms", model, full.length(), (System.nanoTime() - started) / 1_000_000);
                return new Call(full.toString(), evalTokens, evalNanos, loadNanos);
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            throw new LlmException("Cannot reach Ollama at " + cfg.baseUrl() + " (" + reason(e) + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM request interrupted", e);
        }
    }

    private static final class ThinkingUnsupported extends RuntimeException {
    }

    @Override
    public String defaultModel() {
        return cfg.model();
    }

    @Override
    public List<ModelInfo> models() {
        try {
            List<ModelInfo> result = new java.util.ArrayList<>();
            for (JsonNode m : getJson("/api/tags").path("models")) {
                JsonNode details = m.path("details");
                result.add(new ModelInfo(m.path("name").asString(""), m.path("size").asLong(0),
                        details.path("parameter_size").asString(""), details.path("quantization_level").asString("")));
            }
            result.sort(java.util.Comparator.comparing(ModelInfo::name));
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", e);
        } catch (Exception e) {
            throw new LlmException("Cannot reach Ollama at " + cfg.baseUrl() + " (" + reason(e) + ")", e);
        }
    }

    @Override
    public Optional<Running> running(String model) {
        try {
            for (JsonNode m : getJson("/api/ps").path("models")) {
                String name = m.path("name").asString("");
                if (name.equals(model) || name.equals(model + ":latest")) {
                    return Optional.of(new Running(m.path("size").asLong(0), m.path("size_vram").asLong(0)));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Cannot read Ollama /api/ps: {}", e.toString());
        }
        return Optional.empty();
    }

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + path)).timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private void acquireSlot() {
        try {
            if (!slot.tryAcquire(cfg.queueTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new LlmException("The teacher is busy with another request, try again in a moment");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while waiting for the LLM", e);
        }
    }

    private static String reason(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Для /api/health: Ollama отвечает на /api/tags и нужная модель скачана. */
    @Override
    public Status status() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Status(false, "HTTP " + response.statusCode());
            }
            for (JsonNode model : mapper.readTree(response.body()).path("models")) {
                String name = model.path("name").asString("");
                if (name.equals(cfg.model()) || name.equals(cfg.model() + ":latest")) {
                    return new Status(true, cfg.model());
                }
            }
            return new Status(false, "model " + cfg.model() + " is not pulled yet");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(false, "interrupted");
        } catch (Exception e) {
            return new Status(false, "unreachable (" + reason(e) + ")");
        }
    }
}
