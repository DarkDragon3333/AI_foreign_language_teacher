package study.ai_foreign_language_teacher.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Контракт ответа LLM из раздела 6 ARCHITECTURE.md. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeacherReply(
        @JsonProperty("reply_en") String replyEn,
        @JsonProperty("reply_ru") String replyRu,
        @JsonProperty("correction") Correction correction,
        @JsonProperty("explanation") Explanation explanation,
        @JsonProperty("next_prompt") String nextPrompt,
        @JsonProperty("cards_to_review") List<String> cardsToReview,
        @JsonProperty("mode") String mode,
        @JsonProperty("cefr") String cefr,
        @JsonProperty("support_level") Integer supportLevel,
        @JsonProperty("reading_passage") String readingPassage) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Correction(
            @JsonProperty("original") String original,
            @JsonProperty("corrected") String corrected,
            @JsonProperty("errors") List<ErrorItem> errors) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorItem(
            @JsonProperty("span") String span,
            @JsonProperty("rule") String rule,
            @JsonProperty("hint") String hint) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Explanation(
            @JsonProperty("simple") String simple,
            @JsonProperty("example_ok") String exampleOk,
            @JsonProperty("example_bad") String exampleBad,
            @JsonProperty("title") String title,
            @JsonProperty("trap") String trap) {
    }

    /** Текст, который видит ученик в чате. */
    public String displayText() {
        if (nextPrompt == null || nextPrompt.isBlank()) {
            return replyEn == null ? "" : replyEn;
        }
        String answer = replyEn == null ? "" : replyEn.strip();
        String passage = readingPassage == null ? "" : readingPassage.strip();
        String question = nextPrompt.strip();
        StringBuilder sb = new StringBuilder();
        if (!answer.isEmpty()) sb.append(answer);
        if (!passage.isEmpty()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(passage);
        }
        if (!sb.isEmpty()) sb.append(' ');
        sb.append(question);
        return sb.toString().strip();
    }
}
