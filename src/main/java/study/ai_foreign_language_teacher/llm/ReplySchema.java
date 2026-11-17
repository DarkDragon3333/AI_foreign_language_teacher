package study.ai_foreign_language_teacher.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-схема ответа модели (см. LlmReply). Передаётся в Ollama как "format" — модель физически
 * не может выдать текст вне схемы. Все поля обязательны (маленьким моделям так проще);
 * пустые значения потом убирает ReplyParser.
 *
 * Поля объявлены в алфавитном порядке, и это же порядок генерации: answer_en идёт первым,
 * поэтому ученик видит ответ сразу, пока модель дописывает исправление. follow_up_ru стоит
 * сразу после follow_up_question: перевод вопроса пишется, когда вопрос уже есть в тексте.
 * Уровень, режим и ступень подсказки в схему не входят — их всё равно задаёт приложение,
 * а модель тратила на них токены.
 */
public final class ReplySchema {

    private ReplySchema() {
    }

    public static Map<String, Object> schema() {
        Map<String, Object> errorItem = object(props(
                "fragment", string(),
                "rule", string(),
                "tip", string()));

        Map<String, Object> correction = object(props(
                "errors", Map.of("type", "array", "items", errorItem),
                "fixed_text", string()));

        Map<String, Object> explanation = object(props(
                "brief_rule", string(),
                "example_correct", string(),
                "example_wrong", string()));

        return object(props(
                "answer_en", string(),
                "answer_ru", string(),
                "correction", correction,
                "explanation", explanation,
                "follow_up_question", string(),
                "follow_up_ru", string()));
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "object");
        map.put("properties", properties);
        map.put("required", List.copyOf(properties.keySet()));
        return map;
    }
}
