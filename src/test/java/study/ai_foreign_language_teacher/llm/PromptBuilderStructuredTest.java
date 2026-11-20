package study.ai_foreign_language_teacher.llm;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonPhase;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.StructuredPlanState;
import study.ai_foreign_language_teacher.session.ScenarioService;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PromptBuilderStructuredTest {

    @Test
    void feedbackPhaseForbidsNewExercises() {
        PromptBuilder builder = new PromptBuilder(new tools.jackson.databind.ObjectMapper(), mock(ScenarioService.class));
        Instant start = Instant.now().minusSeconds(20 * 60);
        LessonSession session = new LessonSession(
                1, 1, LessonMode.STRUCTURED, "travel", Cefr.B1, 0, start, null, null, null,
                new StructuredPlanState(25, LessonPhase.FEEDBACK, start.plusSeconds(15 * 60)));
        String block = builder.structuredLessonBlock(session);
        assertTrue(block.contains("FEEDBACK"));
        assertTrue(block.toLowerCase().contains("no new exercises"));
    }
}