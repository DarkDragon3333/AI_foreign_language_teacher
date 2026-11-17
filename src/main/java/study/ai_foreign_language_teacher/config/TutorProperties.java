package study.ai_foreign_language_teacher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Настройки из application.properties с префиксом "tutor". */
@ConfigurationProperties(prefix = "tutor")
public record TutorProperties(Ollama ollama, LanguageTool languagetool, Lesson lesson, Embed embed, Stt stt, Tts tts) {

    public record Ollama(
            String baseUrl,
            String model,
            int numCtx,
            int numPredict,
            Duration requestTimeout,
            Duration queueTimeout,
            String keepAlive,
            boolean disableThinking) {
    }

    public record LanguageTool(String baseUrl, String language, boolean enabled, Duration timeout) {
    }

    public record Lesson(
            String defaultCefr,
            String nativeLang,
            String targetLang,
            int historyMessages) {
    }

    /** Маленькая CPU-модель эмбеддингов. Видеопамять не занимает; если сервис молчит, карточки ищутся по правилам LT. */
    public record Embed(String baseUrl, boolean enabled, Duration timeout, int dimensions) {
    }

    /** Распознавание речи на CPU. Видеопамять не занимает. Пустой language — язык определяется по фразе. */
    public record Stt(String baseUrl, boolean enabled, String language, Duration timeout) {
    }

    /** Озвучка английской реплики на CPU. Видеопамять не занимает. */
    public record Tts(String baseUrl, boolean enabled, String voice, Duration timeout) {
    }
}
