package study.ai_foreign_language_teacher.llm;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Формат, в котором отвечает модель. Отличается от контракта для интерфейса (TeacherReply) именами полей.
 *
 * Имена подобраны так, чтобы алфавитный порядок совпадал с нужным порядком генерации: часть движков Ollama
 * сортирует поля схемы по алфавиту. Со старыми именами (reply_en, correction, cards_to_review…) модель была
 * вынуждена сначала писать исправление и служебные поля, а сам ответ — в самом конце, причём исправленную
 * фразу раньше списка ошибок. Теперь порядок одинаковый на любом движке:
 * ответ → перевод → ошибки → исправленная фраза → правило → следующий вопрос.
 * Старые имена оставлены как псевдонимы.
 */
public record LlmReply(
        @JsonProperty("answer_en") @JsonAlias("reply_en") String answerEn,
        @JsonProperty("answer_ru") @JsonAlias("reply_ru") String answerRu,
        @JsonProperty("correction") Correction correction,
        @JsonProperty("explanation") Explanation explanation,
        @JsonProperty("follow_up_question") @JsonAlias("next_prompt") String followUpQuestion) {

    public record Correction(
            @JsonProperty("errors") List<ErrorItem> errors,
            @JsonProperty("fixed_text") @JsonAlias("corrected") String fixedText,
            @JsonProperty("original") String original) {
    }

    public record ErrorItem(
            @JsonProperty("fragment") @JsonAlias("span") String fragment,
            @JsonProperty("rule") String rule,
            @JsonProperty("tip") @JsonAlias("hint") String tip) {
    }

    public record Explanation(
            @JsonProperty("brief_rule") @JsonAlias("simple") String briefRule,
            @JsonProperty("example_correct") @JsonAlias("example_ok") String exampleCorrect,
            @JsonProperty("example_wrong") @JsonAlias("example_bad") String exampleWrong) {
    }
}
