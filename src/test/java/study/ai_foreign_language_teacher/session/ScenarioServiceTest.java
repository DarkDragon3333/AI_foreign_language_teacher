package study.ai_foreign_language_teacher.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonSession;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioServiceTest {

    private ScenarioService service;
    private LessonSession session;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = JsonMapper.builder().build();
        service = new ScenarioService(new ScenarioCatalog(mapper));
        session = new LessonSession(1, 1, LessonMode.ROLEPLAY, "cafe", Cefr.A2, 0, Instant.EPOCH, null, null);
        service.start(1, "cafe");
    }

    @Test
    void marksGoalWhenHintAppearsInMessage() {
        List<String> fresh = service.onUserMessage(1, session, "Hello! I would like a latte, please.");
        assertTrue(fresh.contains("greet"));
        assertTrue(fresh.contains("order_drink"));
        var progress = service.progress(1, session).orElseThrow();
        assertTrue(progress.goals().stream().filter(g -> g.id().equals("greet")).findFirst().orElseThrow().done());
    }

    @Test
    void questionMarkGoalForInterview() {
        LessonSession job = new LessonSession(2, 1, LessonMode.ROLEPLAY, "job_interview", Cefr.B1, 0, Instant.EPOCH, null, null);
        service.start(2, "job_interview");
        service.onUserMessage(2, job, "My name is Anna. I studied marketing.");
        List<String> fresh = service.onUserMessage(2, job, "What is a typical day in this role?");
        assertTrue(fresh.contains("question_back"));
    }

    @Test
    void noProgressOutsideRoleplay() {
        LessonSession talk = new LessonSession(3, 1, LessonMode.CONVERSATION, "travel", Cefr.B1, 0, Instant.EPOCH, null, null);
        assertFalse(service.progress(3, talk).isPresent());
        assertEquals(List.of(), service.onUserMessage(3, talk, "Hello"));
    }
}