package study.ai_foreign_language_teacher.llm;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface LlmClient {

    /**
     * Один ход чата. Блокирует поток до конца генерации.
     *
     * @param format  JSON-схема ответа (structured output)
     * @param onChunk вызывается на каждый кусок текста по мере генерации
     * @return полный текст ответа модели
     */
    String chat(List<LlmMessage> messages, double temperature, Map<String, Object> format, Consumer<String> onChunk);

    Status status();

    record Status(boolean up, String detail) {
    }
}
