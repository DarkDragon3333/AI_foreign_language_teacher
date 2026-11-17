package study.ai_foreign_language_teacher.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.config.TutorProperties;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Клиент озвучки. Сервис на CPU и не трогает видеокарту. */
@Component
public class TtsClient {

    private static final Logger log = LoggerFactory.getLogger(TtsClient.class);

    private final TutorProperties.Tts cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public TtsClient(TutorProperties props, ObjectMapper mapper) {
        this.cfg = props.tts();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(400)).build();
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    /** WAV английской реплики. Голос берётся из настроек, не из браузера. */
    public byte[] speak(String text) {
        if (!cfg.enabled()) {
            throw new IllegalArgumentException("Озвучка выключена");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Нечего озвучивать");
        }
        try {
            String body = mapper.writeValueAsString(Map.of("text", text.strip(), "voice", cfg.voice()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + "/speak"))
                    .timeout(cfg.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length < 44) {
                throw new IllegalArgumentException("Озвучка не ответила: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("TTS request failed: {}", e.toString());
            throw new IllegalArgumentException("Озвучка сейчас недоступна");
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

    public String statusDetail() {
        if (!cfg.enabled()) {
            return "выключена";
        }
        return ready() ? cfg.baseUrl() : "не отвечает";
    }
}
