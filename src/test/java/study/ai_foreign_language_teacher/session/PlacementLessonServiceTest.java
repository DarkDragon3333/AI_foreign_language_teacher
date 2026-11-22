package study.ai_foreign_language_teacher.session;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.ActivityType;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.PlacementPlanState;
import study.ai_foreign_language_teacher.domain.PlacementStage;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.domain.SessionPlanState;
import study.ai_foreign_language_teacher.domain.SessionSummary;
import study.ai_foreign_language_teacher.llm.PlacementMetaParser;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.SessionRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlacementLessonServiceTest {

    @Test
    void advancesThroughTestBlocks() {
        AtomicReference<SessionPlanState> plan = new AtomicReference<>(PlacementPlanState.start());
        PlacementLessonService service = service(plan);
        LessonSession session = baseSession(plan.get());
        LessonSession next = service.advanceAfterUser(session, "My answer here.", "What is your name?");
        PlacementPlanState after = next.placementPlan();
        assertEquals(0, after.testBlock());
        assertEquals(1, after.testQuestion());
    }

    @Test
    void stepIndexFollowsStageNotModelText() {
        PlacementLessonService service = new PlacementLessonService(mock(SessionRepository.class),
                mock(ProfileRepository.class), new PlacementMetaParser(new ObjectMapper()), new ObjectMapper());
        var testMid = new PlacementPlanState(PlacementStage.TEST, 1, 0, 0, null, null);
        assertEquals(3, PlacementLessonService.stepIndex(testMid));
        var surveyFirst = new PlacementPlanState(PlacementStage.SURVEY, 4, 0, 0, null, null);
        assertEquals(9, PlacementLessonService.stepIndex(surveyFirst));
        assertEquals("survey", PlacementLessonService.stageForUi(surveyFirst));
        var plan = new PlacementPlanState(PlacementStage.PLAN, 4, 0, 3, null, null);
        assertEquals(11, PlacementLessonService.stepIndex(plan));
        assertEquals("plan", PlacementLessonService.stageForUi(plan));
    }

    @Test
    void surveyStartsAfterLastTestAnswer() {
        AtomicReference<SessionPlanState> plan = new AtomicReference<>(
                new PlacementPlanState(PlacementStage.TEST, 3, 1, 0, null, null));
        PlacementLessonService service = service(plan);
        LessonSession session = baseSession(plan.get());
        PlacementPlanState after = service.advanceAfterUser(session, "Done.", "Final test question?").placementPlan();
        assertEquals(PlacementStage.SURVEY, after.stage());
    }

    @Test
    void validatesPlacementTaskFollowUp() {
        assertTrue(PlacementLessonService.isValidPlacementTask("What do you think?"));
        assertTrue(PlacementLessonService.isValidPlacementTask(
                "Choose one. A) She goes. B) She go. C) She going."));
        assertFalse(PlacementLessonService.isValidPlacementTask("Describe your last trip."));
        assertFalse(PlacementLessonService.isValidPlacementTask("Read the text silently and answer one question about it."));
        assertFalse(PlacementLessonService.isValidPlacementTask("Sorry, I lost my train of thought. Could you say that again?"));
        assertFalse(PlacementLessonService.isValidPlacementTask("That is a very proactive mindset!"));
        assertFalse(PlacementLessonService.isValidPlacementTask(""));
    }

    @Test
    void skipsAdvanceOnShortOrRepeatUtterance() {
        AtomicReference<SessionPlanState> plan = new AtomicReference<>(PlacementPlanState.start());
        PlacementLessonService service = service(plan);
        LessonSession session = baseSession(plan.get());
        session = service.advanceAfterUser(session, "?", "Task?");
        assertEquals(0, session.placementPlan().testQuestion());
        assertFalse(PlacementLessonService.countsAsPlacementAnswer("?", "Task?"));
        assertFalse(PlacementLessonService.countsAsPlacementAnswer("a", "Task?"));
        assertFalse(PlacementLessonService.countsAsPlacementAnswer("repeat the question", "Task?"));
        assertFalse(PlacementLessonService.countsAsPlacementAnswer("-", "What is your goal?"));
        assertFalse(PlacementLessonService.countsAsPlacementAnswer("answer", ""));
        assertTrue(PlacementLessonService.countsAsPlacementAnswer("I work in IT.", "Describe your job."));
    }

    @Test
    void polishSurveyForcesGoalQuestionAndClearsPassage() {
        var plan = new PlacementPlanState(PlacementStage.SURVEY, 4, 0, 0, null, null);
        TeacherReply bad = new TeacherReply("Sounds good! Here is a new topic.", null, null, null,
                "Please read this passage about Amazon.", List.of(), null, null, null,
                "Mia walked in the park.");
        TeacherReply fixed = PlacementLessonService.polishPlacementReply(bad, plan);
        assertEquals(PlacementLessonService.SURVEY_GOAL_QUESTION, fixed.nextPrompt());
        assertEquals(null, fixed.readingPassage());
    }

    @Test
    void floorAssessedLevelRaisesA2WhenAnswersShowB1Grammar() {
        List<ChatMessage> history = List.of(
                new ChatMessage(1L, 1L, Role.USER, "If I had known, I would have called you earlier.", null, null));
        assertEquals("B1", PlacementLessonService.floorAssessedLevel("A2", history));
        assertEquals("B2", PlacementLessonService.floorAssessedLevel("B2", history));
    }

    @Test
    void recoversTaskBuriedInAnswerWhenFollowUpIsJunk() {
        TeacherReply buried = new TeacherReply(
                "Excellent! Choose one. A) To work abroad. B) To travel. C) To pass an exam. D) To read.",
                null, null, null, ".", java.util.List.of(), null, null, null, null);
        TeacherReply fixed = PlacementLessonService.recoverTaskFromAnswer(buried);
        assertTrue(fixed.nextPrompt().contains("D) To read."));
        assertEquals("Excellent!", fixed.replyEn());
    }

    private PlacementLessonService service(AtomicReference<SessionPlanState> plan) {
        SessionRepository sessions = mock(SessionRepository.class);
        when(sessions.find(1L)).thenAnswer(inv -> Optional.of(baseSession(plan.get())));
        org.mockito.Mockito.doAnswer(inv -> {
            plan.set(inv.getArgument(1));
            return null;
        }).when(sessions).updatePlanState(org.mockito.ArgumentMatchers.eq(1L), any());
        return new PlacementLessonService(sessions, mock(ProfileRepository.class),
                new PlacementMetaParser(new ObjectMapper()), new ObjectMapper());
    }

    private static LessonSession baseSession(SessionPlanState plan) {
        return new LessonSession(1, 1, LessonMode.PLACEMENT, "placement", Cefr.B1, 0, Instant.now(), null, null, null, plan);
    }
}