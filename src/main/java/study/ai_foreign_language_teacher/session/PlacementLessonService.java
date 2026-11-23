package study.ai_foreign_language_teacher.session;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.domain.ActivityType;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.DailyGoalMinutes;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.LearningPlan;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.PlacementPlanState;
import study.ai_foreign_language_teacher.domain.PlacementStage;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.SessionPlanState;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.llm.PlacementMetaParser;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.SessionRepository;
import study.ai_foreign_language_teacher.schedule.ScheduleService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class PlacementLessonService {

    private static final String[] TEST_BLOCKS = {"Grammar", "Vocabulary", "Reading", "Free response"};
    public static final int PLACEMENT_STEP_TOTAL = 11;

    private static final String[] SURVEY_TOPICS = {
            "main learning goal",
            "your schedule & timeline",
    };

    static final String SURVEY_GOAL_QUESTION =
            "What is your main goal for learning English (for example: work, IT interviews, exams, or travel)?";

    private static final Pattern PERFECT_OR_PARTICIPLE = Pattern.compile(
            "(?i)\\b(have|has|had)\\s+(been\\s+)?\\w+(ed|en)\\b|\\b've\\s+\\w+");
    private static final Pattern CONDITIONAL_CLAUSE = Pattern.compile("(?i)\\bif\\s+.+\\b(would|could|might|had)\\b");
    private static final Pattern SURVEY_READING_TRAP = Pattern.compile(
            "(?i)\\b(read this|short passage|comprehension|choose the correct sentence)\\b");

    private static final String[] SURVEY_QUESTIONS = {
            SURVEY_GOAL_QUESTION,
            "Set your study schedule and target timeline using the widget on screen.",
    };

    public static final String PLACEMENT_TEST_RETRY_HINT = """
            PLACEMENT FIX: follow_up_question must be a real test task — include "?" or start with Choose, Complete, Write, \
            Explain, Describe, Read, or Tell. answer_en is only a short reaction. Regenerate follow_up_question now.""";

    private final SessionRepository sessions;
    private final ProfileRepository profiles;
    private final PlacementMetaParser metaParser;
    private final ObjectMapper mapper;
    private final PlacementBank bank;

    public PlacementLessonService(SessionRepository sessions, ProfileRepository profiles,
                                  PlacementMetaParser metaParser, ObjectMapper mapper, PlacementBank bank) {
        this.sessions = sessions;
        this.profiles = profiles;
        this.metaParser = metaParser;
        this.mapper = mapper;
        this.bank = bank == null ? new PlacementBank() : bank;
    }

    public record LadderItemView(String id, String stem, List<String> choices) {
    }

    public record OfferView(String level, String boundaryLabel, String note) {
    }

    public record PlacementView(
            String stage,
            String stepLabel,
            int stepIndex,
            int stepTotal,
            boolean planReady,
            String assessedLevel,
            LearningPlan learningPlan,
            LadderItemView ladderItem,
            OfferView offer) {
    }

    public PlacementPlanState initPlan() {
        return PlacementLadder.begin(bank);
    }

    public Optional<PlacementView> view(LessonSession session) {
        if (session.mode() != LessonMode.PLACEMENT) {
            return Optional.empty();
        }
        PlacementPlanState plan = session.placementPlan();
        if (plan == null) {
            return Optional.empty();
        }
        return Optional.of(toView(plan));
    }

    PlacementView toView(PlacementPlanState plan) {
        int index = stepIndex(plan);
        int total = stepTotal(plan);
        String label = stepLabel(plan);
        boolean ready = plan.stage() == PlacementStage.READY && plan.learningPlan() != null;
        return new PlacementView(stageForUi(plan), label, index, total, ready,
                plan.assessedLevel(), plan.learningPlan(), ladderItem(plan), offer(plan));
    }

    private LadderItemView ladderItem(PlacementPlanState plan) {
        if (plan.stage() != PlacementStage.LADDER && plan.stage() != PlacementStage.REFINE) {
            return null;
        }
        PlacementBank.Item item = bank.byId(plan.currentItemId());
        if (item == null || item.choices() == null) {
            return null;
        }
        return new LadderItemView(item.id(), item.stem(), List.copyOf(item.choices()));
    }

    private static OfferView offer(PlacementPlanState plan) {
        if (plan.stage() != PlacementStage.OFFER) {
            return null;
        }
        String boundary = plan.boundary() == null ? "" : plan.boundary();
        String words = boundary.replace('-', '/');
        String note = words.isBlank()
                ? "This is a preliminary result. A neighbouring level is still possible."
                : "This is a preliminary result. " + words.replace("/", " or ") + " is still possible.";
        return new OfferView(plan.preliminaryLevel(), words, note);
    }

    /** Шаг 1–8 тест, 9–10 анкета, 11 план — только из plan_state, не из текста модели. */
    static int stepIndex(PlacementPlanState plan) {
        return switch (plan.stage()) {
            case TEST -> plan.testBlock() * 2 + plan.testQuestion() + 1;
            case LADDER -> Math.min(PlacementLadder.LADDER_LIMIT, plan.hits().size() + 1);
            case OFFER -> PlacementLadder.LADDER_LIMIT;
            case REFINE -> Math.min(PlacementLadder.REFINE_LIMIT, plan.refineAsked() + 1);
            case SURVEY -> 8 + plan.surveyQuestion() + 1;
            case PLAN, READY -> PLACEMENT_STEP_TOTAL;
        };
    }

    static int stepTotal(PlacementPlanState plan) {
        return switch (plan.stage()) {
            case LADDER, OFFER -> PlacementLadder.LADDER_LIMIT;
            case REFINE -> PlacementLadder.REFINE_LIMIT;
            default -> PLACEMENT_STEP_TOTAL;
        };
    }

    static String stageForUi(PlacementPlanState plan) {
        return switch (plan.stage()) {
            case TEST, LADDER, OFFER, REFINE -> PlacementStage.TEST.json();
            case SURVEY -> PlacementStage.SURVEY.json();
            case PLAN, READY -> PlacementStage.PLAN.json();
        };
    }

    private static String stepLabel(PlacementPlanState plan) {
        return switch (plan.stage()) {
            case TEST -> "Level test · " + TEST_BLOCKS[Math.min(plan.testBlock(), TEST_BLOCKS.length - 1)]
                    + " (" + (plan.testQuestion() + 1) + "/2)";
            case LADDER, OFFER -> "Level check";
            case REFINE -> "Finer check";
            case SURVEY -> "About you · " + SURVEY_TOPICS[Math.min(plan.surveyQuestion(), SURVEY_TOPICS.length - 1)];
            case PLAN -> "Your plan";
            case READY -> "Your plan is ready";
        };
    }

    /** follow_up_question последней реплики учителя (до текущего ввода ученика). */
    public String lastFollowUpQuestion(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage m = history.get(i);
            if (m.role() != Role.ASSISTANT) {
                continue;
            }
            if (m.payloadJson() != null && !m.payloadJson().isBlank()) {
                try {
                    TeacherReply reply = mapper.readValue(m.payloadJson(), TeacherReply.class);
                    if (reply.nextPrompt() != null) {
                        return reply.nextPrompt().strip();
                    }
                } catch (JacksonException ignored) {
                    // fallback to visible text
                }
            }
            return m.text() == null ? "" : m.text().strip();
        }
        return null;
    }

    static boolean countsAsPlacementAnswer(String userText, String lastFollowUpQuestion) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        String t = userText.strip();
        if (t.length() < 2) {
            return false;
        }
        if (t.equals("-") || t.equals("—") || t.equals("–")) {
            return false;
        }
        if (isServiceOrRepeatUtterance(t)) {
            return false;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("writing the question")) {
            return false;
        }
        if (lastFollowUpQuestion == null || lastFollowUpQuestion.isBlank()) {
            return false;
        }
        return true;
    }

    /** У follow_up теста должен быть вопрос или императивное задание, а не только комплимент. */
    public static boolean isValidPlacementTask(String followUp) {
        if (followUp == null || followUp.isBlank()) {
            return false;
        }
        String t = followUp.strip();
        if (isStubTeacherText(t)) {
            return false;
        }
        if (t.contains("?")) {
            return true;
        }
        return t.matches("(?s).*\\b[Aa]\\)\\s+.+\\b[Bb]\\)\\s+.+\\b[Cc]\\)\\s+.+");
    }

    /** Анкета: не reading и не грамматические A/B/C. */
    public static boolean isValidSurveyFollowUp(TeacherReply reply, PlacementPlanState plan) {
        if (reply == null || plan == null || plan.stage() != PlacementStage.SURVEY) {
            return true;
        }
        String follow = reply.nextPrompt();
        if (follow == null || follow.isBlank() || isStubTeacherText(follow)) {
            return false;
        }
        if (reply.readingPassage() != null && !reply.readingPassage().isBlank()) {
            return false;
        }
        String lower = follow.toLowerCase(Locale.ROOT);
        if (SURVEY_READING_TRAP.matcher(lower).find()) {
            return false;
        }
        if (lower.matches("(?s).*\\b[aA]\\)\\s+.+\\b[bB]\\)\\s+.+\\b[cC]\\)\\s+.+")) {
            return false;
        }
        if (plan.surveyQuestion() == 0) {
            return follow.strip().equalsIgnoreCase(SURVEY_GOAL_QUESTION.strip());
        }
        return true;
    }

    /** Задание оказалось в answer_en, а follow_up пустой или «.». */
    public static TeacherReply recoverTaskFromAnswer(TeacherReply reply) {
        if (reply == null || reply.replyEn() == null || reply.replyEn().isBlank()) {
            return null;
        }
        String follow = reply.nextPrompt() == null ? "" : reply.nextPrompt().strip();
        if (!isJunkFollowUp(follow) && isValidPlacementTask(follow)) {
            return null;
        }
        String en = reply.replyEn().strip();
        boolean hasChoices = en.matches("(?s).*\\b[A-D]\\)\\s+.+");
        if (!hasChoices && !en.contains("?")) {
            return null;
        }
        String ack = "Let's continue.";
        String task = en;
        var split = java.util.regex.Pattern.compile("(?s)^(.{0,120}?[.!?])\\s+(.+)$").matcher(en);
        if (split.matches() && !split.group(1).matches("(?s).*\\b[A-D]\\)\\s+.+")
                && (split.group(2).contains("?") || split.group(2).matches("(?s).*\\b[A-D]\\)\\s+.+"))) {
            ack = split.group(1).strip();
            task = split.group(2).strip();
        }
        if (!isValidPlacementTask(task)) {
            return null;
        }
        return new TeacherReply(ack, reply.replyRu(), null, null, task,
                reply.cardsToReview(), reply.mode(), reply.cefr(), reply.supportLevel(), reply.readingPassage());
    }

    static boolean isJunkFollowUp(String follow) {
        if (follow == null || follow.isBlank() || follow.length() < 2) {
            return true;
        }
        String t = follow.strip();
        if (t.equals(".") || t.equals("-") || t.equals("—") || t.equals("–")) {
            return true;
        }
        return t.toLowerCase(Locale.ROOT).contains("writing the question");
    }

    /** Постобработка ответа модели под этап placement (вопрос, reading, пустой follow_up на плане). */
    public static TeacherReply polishPlacementReply(TeacherReply parsed, PlacementPlanState plan) {
        if (parsed == null || plan == null) {
            return parsed;
        }
        return switch (plan.stage()) {
            case SURVEY -> polishSurveyReply(parsed, plan);
            case PLAN, READY -> copyWithPrompt(parsed, "", null);
            default -> parsed;
        };
    }

    private static TeacherReply polishSurveyReply(TeacherReply parsed, PlacementPlanState plan) {
        if (plan.surveyQuestion() == 0) {
            String en = parsed.replyEn();
            if (en == null || en.isBlank() || isStubTeacherText(en)) {
                en = "Thanks!";
            }
            return new TeacherReply(en, parsed.replyRu(), null, null, SURVEY_GOAL_QUESTION,
                    parsed.cardsToReview(), parsed.mode(), parsed.cefr(), parsed.supportLevel(), null);
        }
        if (parsed.readingPassage() != null && !parsed.readingPassage().isBlank()) {
            return copyWithPrompt(parsed, parsed.nextPrompt(), null);
        }
        return parsed;
    }

    private static TeacherReply copyWithPrompt(TeacherReply from, String nextPrompt, String readingPassage) {
        return new TeacherReply(from.replyEn(), from.replyRu(), from.correction(), from.explanation(),
                nextPrompt, from.cardsToReview(), from.mode(), from.cefr(), from.supportLevel(), readingPassage);
    }

    /** Не занижать уровень при связных ответах с Perfect / conditional в тесте. */
    public static String floorAssessedLevel(String modelLevel, List<ChatMessage> history) {
        if (modelLevel == null || modelLevel.isBlank()) {
            return modelLevel;
        }
        Cefr level;
        try {
            level = Cefr.parse(modelLevel);
        } catch (IllegalArgumentException e) {
            return modelLevel;
        }
        if (level.ordinal() >= Cefr.B1.ordinal()) {
            return level.name();
        }
        if (!suggestsAtLeastB1(history)) {
            return level.name();
        }
        return Cefr.B1.name();
    }

    static boolean suggestsAtLeastB1(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        for (ChatMessage m : history) {
            if (m.role() != Role.USER || m.text() == null) {
                continue;
            }
            String t = m.text().strip();
            if (t.length() < 12 || t.equals("-") || t.equals("—")) {
                continue;
            }
            String lower = t.toLowerCase(Locale.ROOT);
            if (PERFECT_OR_PARTICIPLE.matcher(t).find()
                    || CONDITIONAL_CLAUSE.matcher(t).find()
                    || lower.contains(" because ")
                    || lower.contains(" although ")
                    || t.split("\\s+").length >= 14) {
                return true;
            }
        }
        return false;
    }

    public static TeacherReply mergeInvalidFollowUp(TeacherReply reply) {
        if (reply == null) {
            return null;
        }
        String follow = reply.nextPrompt();
        if (follow == null || follow.isBlank()) {
            return reply;
        }
        String en = reply.replyEn() == null ? "" : reply.replyEn().strip();
        String merged = en.isEmpty() ? follow.strip() : en + " " + follow.strip();
        return new TeacherReply(merged, reply.replyRu(), reply.correction(), reply.explanation(),
                "", reply.cardsToReview(), reply.mode(), reply.cefr(), reply.supportLevel(),
                reply.readingPassage());
    }

    public static boolean isStubTeacherText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("lost my train of thought")
                || lower.contains("say that again")
                || lower.contains("sorry");
    }

    /** Резервный вопрос, если модель дважды не дала задание. */
    public static TeacherReply withReservedTask(TeacherReply reply, PlacementPlanState plan, String cefr) {
        int block = plan == null ? 0 : Math.min(Math.max(plan.testBlock(), 0), 3);
        boolean higher = cefr != null && (cefr.startsWith("B2") || cefr.startsWith("C"));
        String question = switch (block) {
            case 0 -> higher
                    ? "Which sentence is correct? A) If I had known, I would have called. B) If I would know, I had called. C) If I knew, I would called."
                    : "Choose the correct sentence. A) She don't like coffee. B) She doesn't like coffee. C) She not like coffee.";
            case 1 -> higher
                    ? "Which word fits best? The results were ___. A) conclusive B) conclude C) conclusion."
                    : "Choose the best word. I ___ to the shop yesterday. A) go B) went C) goed.";
            case 2 -> "What did Mia do after she missed the train?";
            default -> higher
                    ? "Do you think working from home improves focus, and why?"
                    : "What do you usually do after work?";
        };
        String passage = block == 2
                ? "Mia missed the morning train, so she walked to work and arrived late. Her manager asked why she was late, and Mia explained calmly. They agreed she would start earlier the next day."
                : null;
        String en = reply == null || reply.replyEn() == null || isStubTeacherText(reply.replyEn())
                ? "Let's try this question."
                : reply.replyEn();
        return new TeacherReply(en, reply == null ? null : reply.replyRu(), null, null, question,
                List.of(), reply == null ? null : reply.mode(), reply == null ? null : reply.cefr(),
                reply == null ? null : reply.supportLevel(), passage);
    }

    static boolean isServiceOrRepeatUtterance(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.length() <= 3) {
            return true;
        }
        return lower.contains("repeat the question")
                || lower.contains("say that again")
                || lower.contains("what was the question")
                || lower.contains("pardon?")
                || lower.contains("повтор")
                || lower.contains("не понял")
                || lower.contains("ещё раз")
                || lower.equals("?")
                || lower.equals("??");
    }

    /** После содержательной реплики ученика сдвигаем шаг (кроме этапа PLAN/READY). */
    public LessonSession advanceAfterUser(LessonSession session, String userText, String lastFollowUpQuestion) {
        if (session.mode() != LessonMode.PLACEMENT || !countsAsPlacementAnswer(userText, lastFollowUpQuestion)) {
            return session;
        }
        PlacementPlanState plan = session.placementPlan();
        if (plan == null || plan.stage() == PlacementStage.PLAN || plan.stage() == PlacementStage.READY) {
            return session;
        }
        if (plan.stage() == PlacementStage.TEST && !isValidPlacementTask(lastFollowUpQuestion)) {
            return session;
        }
        PlacementPlanState next = switch (plan.stage()) {
            case TEST -> advanceTest(plan);
            case SURVEY -> advanceSurvey(plan);
            default -> plan;
        };
        if (next == plan) {
            return session;
        }
        sessions.updatePlanState(session.id(), next);
        return sessions.find(session.id()).orElse(session.withPlanState(next));
    }

    private static PlacementPlanState advanceTest(PlacementPlanState plan) {
        int q = plan.testQuestion() + 1;
        int block = plan.testBlock();
        if (q >= 2) {
            q = 0;
            block++;
        }
        if (block >= 4) {
            return plan.withStage(PlacementStage.SURVEY);
        }
        return plan.withTest(block, q);
    }

    private static PlacementPlanState advanceSurvey(PlacementPlanState plan) {
        int s = plan.surveyQuestion() + 1;
        if (s >= 2) {
            return plan.withStage(PlacementStage.PLAN);
        }
        return plan.withSurvey(s);
    }

    public LessonSession ingestModelMeta(LessonSession session, String rawJson) {
        return ingestModelMeta(session, rawJson, List.of());
    }

    public LessonSession ingestModelMeta(LessonSession session, String rawJson, List<ChatMessage> history) {
        if (session.mode() != LessonMode.PLACEMENT) {
            return session;
        }
        PlacementPlanState plan = session.placementPlan();
        if (plan == null) {
            return session;
        }
        PlacementMetaParser.Parsed parsed = metaParser.parse(rawJson);
        if (parsed == null) {
            return session;
        }
        PlacementPlanState next = plan;
        if ((next.assessedLevel() == null || next.assessedLevel().isBlank())
                && parsed.assessedLevel() != null && !parsed.assessedLevel().isBlank()) {
            try {
                String level = floorAssessedLevel(parsed.assessedLevel(), history);
                next = next.withAssessedLevel(Cefr.parse(level).name());
            } catch (IllegalArgumentException ignored) {
                // модель прислала не CEFR — уровень не меняем
            }
        }
        if (parsed.learningPlan() != null && plan.stage() == PlacementStage.PLAN) {
            UserProfile profile = profiles.getOrCreateDefault();
            LearningPlan normalized;
            try {
                normalized = normalizePlan(parsed.learningPlan(), profile);
            } catch (RuntimeException ex) {
                normalized = defaultLearningPlan(profile, next.assessedLevel() == null ? Cefr.B1.name() : next.assessedLevel());
            }
            String level = next.assessedLevel() != null ? next.assessedLevel() : Cefr.B1.name();
            next = next.withLearningPlan(normalized, level);
        }
        if (next != plan) {
            sessions.updatePlanState(session.id(), next);
            return sessions.find(session.id()).orElse(session.withPlanState(next));
        }
        return session;
    }

    public LearningPlan defaultLearningPlan(UserProfile profile, String cefr) {
        List<Integer> days = profile.studyWeekdays();
        int goal = profile.dailyGoalMin();
        LocalDate targetEnd = profile.targetEndDate();
        List<LearningPlan.MonthSlot> slots = buildMonthSlots(days, targetEnd);
        String endIso = targetEnd == null ? null : targetEnd.toString();
        return new LearningPlan(days, goal, slots, endIso);
    }

    public LessonSession submitLadderAnswer(LessonSession session, String itemId, String choice) {
        requirePlacement(session);
        PlacementPlanState next = PlacementLadder.answer(session.placementPlan(), bank, itemId, choice);
        return storePlan(session, next);
    }

    public LessonSession acceptPreliminary(LessonSession session) {
        requirePlacement(session);
        return storePlan(session, PlacementLadder.accept(session.placementPlan()));
    }

    public LessonSession startRefine(LessonSession session) {
        requirePlacement(session);
        return storePlan(session, PlacementLadder.beginRefine(session.placementPlan(), bank));
    }

    private static void requirePlacement(LessonSession session) {
        if (session.mode() != LessonMode.PLACEMENT || session.placementPlan() == null) {
            throw new IllegalArgumentException("Session is not a placement onboarding");
        }
    }

    private LessonSession storePlan(LessonSession session, PlacementPlanState next) {
        sessions.updatePlanState(session.id(), next);
        if (next.stage() == PlacementStage.SURVEY && next.assessedLevel() != null && !next.assessedLevel().isBlank()) {
            sessions.updateCefr(session.id(), Cefr.parse(next.assessedLevel()));
        }
        return sessions.find(session.id()).orElse(session.withPlanState(next));
    }

    public LessonSession applyManualLevel(LessonSession session, UserProfile profile, String cefr) {
        if (session.mode() != LessonMode.PLACEMENT) {
            return session;
        }
        String level = Cefr.parse(cefr).name();
        LearningPlan plan = defaultLearningPlan(profile, level);
        PlacementPlanState ready = PlacementPlanState.start()
                .withStage(PlacementStage.READY)
                .withLearningPlan(plan, level);
        sessions.updatePlanState(session.id(), ready);
        sessions.updateCefr(session.id(), Cefr.parse(level));
        return sessions.find(session.id()).orElse(session.withPlanState(ready));
    }

    private LearningPlan normalizePlan(LearningPlan raw, UserProfile profile) {
        List<Integer> days = ScheduleService.normalizeWeekdays(profile.studyWeekdays());
        int goal = DailyGoalMinutes.normalize(profile.dailyGoalMin());
        List<LearningPlan.MonthSlot> slots = raw.monthSlots() == null ? List.of() : raw.monthSlots();
        LocalDate targetEnd = profile.targetEndDate();
        if (slots.isEmpty()) {
            slots = buildMonthSlots(days, targetEnd);
        } else {
            slots = slots.stream()
                    .map(s -> s.date() == null ? null : parseIsoDate(s.date()) == null ? null
                            : new LearningPlan.MonthSlot(parseIsoDate(s.date()).toString(), s.activityType()))
                    .filter(s -> s != null && isStudyDay(LocalDate.parse(s.date()), days))
                    .filter(s -> targetEnd == null || !LocalDate.parse(s.date()).isAfter(targetEnd))
                    .toList();
            if (slots.isEmpty()) {
                slots = buildMonthSlots(days, targetEnd);
            }
        }
        String endIso = targetEnd == null ? null : targetEnd.toString();
        LocalDate fromModel = parseIsoDate(raw.targetEndDate());
        if (fromModel != null) {
            endIso = fromModel.toString();
        }
        return new LearningPlan(days, goal, slots, endIso);
    }

    private static LocalDate parseIsoDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    private List<LearningPlan.MonthSlot> buildMonthSlots(List<Integer> studyDays, LocalDate targetEnd) {
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);
        LocalDate end = ym.atEndOfMonth();
        if (targetEnd != null && targetEnd.isBefore(end)) {
            end = targetEnd;
        }
        List<LearningPlan.MonthSlot> out = new ArrayList<>();
        for (LocalDate d = today; !d.isAfter(end); d = d.plusDays(1)) {
            if (targetEnd != null && d.isAfter(targetEnd)) {
                break;
            }
            if (!studyDays.contains(d.getDayOfWeek().getValue())) {
                continue;
            }
            var type = defaultActivityType(d).json();
            out.add(new LearningPlan.MonthSlot(d.toString(), type));
        }
        return out;
    }

    public String stepPromptBlock(PlacementPlanState plan) {
        return switch (plan.stage()) {
            case TEST -> testStepPrompt(plan);
            case LADDER, OFFER, REFINE -> """
                    PLACEMENT STEP: Closed questions are shown by the app. answer_en empty. follow_up_question empty.
                    """;
            case SURVEY -> surveyStepPrompt(plan);
            case PLAN -> """
                    PLACEMENT STEP: All test and survey answers are collected.
                    The level is already decided: %s. Put that exact code in placement_meta.assessed_level. Do not change it.
                    Infer CEFR A1–C2 from the conversation only when the decided level above is empty. Fill placement_meta.assessed_level.
                    CEFR rubric (assessed_level — judge grammar and coherence, not spelling alone):
                    - A1–A2: single words or memorised simple phrases; no complex sentences or linkers (because, although, which).
                    - B1: connected multi-clause sentences, subordination, can express an opinion; minor typos or ending slips are fine.
                    - B2–C1: argues clearly, uses advanced grammar and precise vocabulary.
                    Do NOT downgrade to A2 because of typos or speech-to-text (STT) glitches; weight sentence structure and whether ideas hang together.
                    Build placement_meta.learning_plan: study_weekdays (1=Mon..7=Sun), daily_goal_min (5|10|15|20|25|30|45|60),
                    target_end_date (YYYY-MM-DD, from profile when set — do not invent),
                    month_slots for each study day from today through target_end_date or month end ({date: YYYY-MM-DD, activity_type: review|grammar|speaking|listening|conversation}).
                    Use the learner's confirmed study_weekdays, daily_goal_min, and target_end_date from the survey (already saved in profile); do not invent 1 day per week.
                    LANGUAGE: teacher lines in English ONLY (never Spanish or other languages).
                    In answer_en, summarise the level and plan in 2–3 short sentences; follow_up_question empty.
                    """.formatted(plan.assessedLevel() == null || plan.assessedLevel().isBlank()
                    ? "empty" : plan.assessedLevel());
            case READY -> """
                    PLACEMENT STEP: Plan is ready. Encourage the learner to tap Apply plan on screen. follow_up_question empty.
                    """;
        };
    }

    private static String surveyStepPrompt(PlacementPlanState plan) {
        int idx = Math.min(plan.surveyQuestion(), SURVEY_QUESTIONS.length - 1);
        String extra = idx == 1
                ? """
                
                The learner uses the schedule widget (target timeline, study days, minutes per day). Accept their short English summary as the answer.
                """
                : "";
        String exactQ = idx == 0
                ? """
                Put this EXACT text in follow_up_question (word for word, English only): %s
                """.formatted(SURVEY_GOAL_QUESTION)
                : "Put this in follow_up_question: %s".formatted(SURVEY_QUESTIONS[idx]);
        return """
                PLACEMENT STEP: About you — question %d of 2.
                IMPORTANT: The level test is FINISHED. Do NOT ask any English grammar or vocabulary questions.
                LANGUAGE RULE: Write BOTH answer_en and follow_up_question strictly in ENGLISH ONLY. Never use Spanish or any other language.
                %s
                answer_en: brief acknowledgment only — no new question there.%s
                """.formatted(plan.surveyQuestion() + 1, exactQ, extra);
    }

    private static String testStepPrompt(PlacementPlanState plan) {
        int block = Math.min(plan.testBlock(), 3);
        StringBuilder sb = new StringBuilder("""
                PLACEMENT STEP: Level test, block %s, question %d of 2 in this block.
                LANGUAGE: teacher lines in English ONLY — never Spanish or other languages, regardless of how the learner replies.
                answer_en: one short reaction to the learner's last message only — never a question, never their words invented back.
                Forbidden in answer_en: empty announcements with no task (e.g. "Now let's move on to grammar").
                follow_up_question: must contain the full task or question — never leave it empty unless the step says so.
                Never answer your own question in the same turn (bad: "Question? Great! Next question?").
                Do NOT use A1-level chit-chat anywhere in the test (forbidden e.g. "What time do you ride your bike?").
                If the learner answers in full sentences without major breakdowns, each next question must increase difficulty (toward B2/C1: conditionals, Perfect tenses, opinion with reasons).
                Adapt difficulty to previous answers; do not stay at one CEFR band.
                Brief correction only if the answer blocks judging level.
                """.formatted(TEST_BLOCKS[block], plan.testQuestion() + 1));
        if (block == 2) {
            sb.append("""
                    
                    Reading comprehension (block 2): put a short passage of 3–5 sentences in reading_passage.
                    Put ONLY the comprehension question in follow_up_question (the UI field next_prompt). It must end with "?" or offer options A) B) C).
                    Forbidden: a passage with no question; forbidden: apologies or "say that again" instead of a task.
                    Do NOT ask to read aloud, recite, or rewrite/copy the text.
                    """);
        }
        return sb.toString();
    }

    static String blockNameForDate(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH);
    }

    static boolean isStudyDay(LocalDate date, List<Integer> days) {
        return days.contains(date.getDayOfWeek().getValue());
    }

    static LocalDate monthEnd() {
        return YearMonth.now().atEndOfMonth();
    }

    public static DayOfWeek toDayOfWeek(int isoDow) {
        return DayOfWeek.of(isoDow);
    }

    private static ActivityType defaultActivityType(LocalDate date) {
        return switch (date.getDayOfWeek().getValue() % 4) {
            case 0 -> ActivityType.LISTENING;
            case 2 -> ActivityType.SPEAKING;
            default -> ActivityType.CONVERSATION;
        };
    }
}