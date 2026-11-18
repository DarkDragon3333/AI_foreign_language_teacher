package study.ai_foreign_language_teacher.speech;

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
import java.util.UUID;

/** Клиент сервиса распознавания. Сервис на CPU и не трогает видеокарту. */
@Component
public class SttClient {

    private static final Logger log = LoggerFactory.getLogger(SttClient.class);

    private final TutorProperties.Stt cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public SttClient(TutorProperties props, ObjectMapper mapper) {
        this.cfg = props.stt();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(400)).build();
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    /** PCM int16 mono → текст. */
    public String transcribePcm16(byte[] pcm, int sampleRate, String prompt) {
        if (pcm == null || pcm.length < sampleRate / 5) {
            return "";
        }
        return transcribe(WavPcm.wrap(pcm, sampleRate), "audio/wav", prompt);
    }

    /** Текст фразы. Пустая строка — в записи не нашлось слов. */
    public String transcribe(byte[] audio, String contentType) {
        return transcribe(audio, contentType, null);
    }

    public String transcribe(byte[] audio, String contentType, String prompt) {
        if (!cfg.enabled()) {
            throw new IllegalArgumentException("Распознавание речи выключено");
        }
        if (audio == null || audio.length == 0) {
            throw new IllegalArgumentException("Пустая запись");
        }
        String boundary = "----tutor-" + UUID.randomUUID();
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        byte[] body = multipart(boundary, audio, type);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(transcribeUrl(prompt)))
                    .timeout(cfg.timeout())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException("Распознавание не ответило: HTTP " + response.statusCode());
            }
            JsonNode text = mapper.readTree(response.body()).path("text");
            return text.isMissingNode() || text.isNull() ? "" : withoutDictatedCommas(text.asString(""));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("STT request failed: {}", e.toString());
            throw new IllegalArgumentException("Распознавание речи сейчас недоступно");
        }
    }

    /** Whisper ставит запятую на паузу. Ученик её не произносил. */
    static String withoutDictatedCommas(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        return text.replace(",", "").replaceAll("[\\t ]{2,}", " ").strip();
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
            return "выключен";
        }
        return ready() ? cfg.baseUrl() : "не отвечает";
    }

    private String transcribeUrl(String prompt) {
        String base = cfg.baseUrl() + "/transcribe";
        StringBuilder query = new StringBuilder();
        String lang = cfg.language();
        if (lang != null && !lang.isBlank() && !"auto".equalsIgnoreCase(lang)) {
            query.append("language=").append(URLEncoder.encode(lang.strip(), StandardCharsets.UTF_8));
        }
        if (prompt != null && !prompt.isBlank()) {
            if (query.length() > 0) {
                query.append('&');
            }
            String clipped = prompt.length() > 220 ? prompt.substring(0, 220) : prompt;
            query.append("prompt=").append(URLEncoder.encode(clipped.strip(), StandardCharsets.UTF_8));
        }
        if (query.length() == 0) {
            return base;
        }
        return base + '?' + query;
    }

    private static byte[] multipart(String boundary, byte[] audio, String contentType) {
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"speech.webm\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] headBytes = head.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tailBytes = tail.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body = new byte[headBytes.length + audio.length + tailBytes.length];
        System.arraycopy(headBytes, 0, body, 0, headBytes.length);
        System.arraycopy(audio, 0, body, headBytes.length, audio.length);
        System.arraycopy(tailBytes, 0, body, headBytes.length + audio.length, tailBytes.length);
        return body;
    }
}
