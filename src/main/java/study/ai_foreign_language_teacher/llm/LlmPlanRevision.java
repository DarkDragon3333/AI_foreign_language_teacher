package study.ai_foreign_language_teacher.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record LlmPlanRevision(
        String summary,
        List<Revision> revisions) {

    public record Revision(
            String date,
            @JsonProperty("activity_type") String activityType,
            String reason) {
    }
}