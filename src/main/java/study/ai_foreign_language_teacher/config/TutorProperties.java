package study.ai_foreign_language_teacher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Настройки из application.properties с префиксом "tutor". */
@ConfigurationProperties(prefix = "tutor")
public record TutorProperties(Ollama ollama, LanguageTool languagetool, Lesson lesson, Embed embed) {

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
}
