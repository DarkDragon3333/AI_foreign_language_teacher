package study.ai_foreign_language_teacher.llm;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public interface LlmClient {

    /**
     * Один ход чата. Блокирует поток до конца генерации.
     *
     * @param model   имя модели; null — модель по умолчанию из настроек
     * @param format  JSON-схема ответа (structured output)
     * @param seed    фиксирует случайность (для сравнения моделей); null — не задавать
     * @param onChunk вызывается на каждый кусок текста по мере генерации
     */
    Call chat(String model, List<LlmMessage> messages, double temperature, Map<String, Object> format,
              Integer seed, Consumer<String> onChunk);

    default String chat(List<LlmMessage> messages, double temperature, Map<String, Object> format, Consumer<String> onChunk) {
        return chat(null, messages, temperature, format, null, onChunk).text();
    }

    Status status();

    String defaultModel();

    /** Модели, скачанные в Ollama. */
    List<ModelInfo> models();

    /** Сколько памяти занимает загруженная модель и какая часть лежит на видеокарте. */
    Optional<Running> running(String model);

    record Status(boolean up, String detail) {
    }

    /** @param evalTokens сколько токенов сгенерировано; evalNanos — за какое время; loadNanos — загрузка модели в память */
    record Call(String text, long evalTokens, long evalNanos, long loadNanos) {
    }

    record ModelInfo(String name, long sizeBytes, String parameters, String quantization) {
    }

    record Running(long sizeBytes, long vramBytes) {
        public int gpuPercent() {
            return sizeBytes <= 0 ? 0 : (int) Math.round(100.0 * vramBytes / sizeBytes);
        }
    }
}
