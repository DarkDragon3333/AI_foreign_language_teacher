package study.ai_foreign_language_teacher.practice;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.GrammarExerciseType;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.domain.PracticeRun;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.llm.GrammarDrillSchema;
import study.ai_foreign_language_teacher.llm.LlmClient;
import study.ai_foreign_language_teacher.llm.LlmGrammarDrill;
import study.ai_foreign_language_teacher.llm.LlmMessage;
import study.ai_foreign_language_teacher.memory.ErrorRepository;
import study.ai_foreign_language_teacher.memory.PracticeRunRepository;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.nlp.GrammarChecker;
import study.ai_foreign_language_teacher.nlp.LtMatch;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

@Service
public class GrammarPracticeService {

    private final ProfileRepository profiles;
    private final PracticeRunRepository runs;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final GrammarChecker checker;
    private final ErrorRepository errors;
    private final Random random = new Random();

    public GrammarPracticeService(ProfileRepository profiles, PracticeRunRepository runs, LlmClient llm,
                                  ObjectMapper mapper, GrammarChecker checker, ErrorRepository errors) {
        this.profiles = profiles;
        this.runs = runs;
        this.llm = llm;
        this.mapper = mapper;
        this.checker = checker;
        this.errors = errors;
    }

    public record ExerciseView(int index, int total, String type, String prompt, List<String> choices, String rule) {
    }

    public record AnswerResult(boolean correct, String expected, ExerciseView next, boolean finished) {
    }

    public record GrammarState(List<Exercise> exercises, int index, int correct, boolean finished) {
    }

    public record Exercise(String type, String prompt, List<String> choices, String answer, String rule) {
    }

    public record StartResult(long runId, ExerciseView current) {
    }

    public StartResult start(List<String> types, boolean randomize) {
        UserProfile profile = profiles.getOrCreateDefault();
        List<GrammarExerciseType> picked = pickTypes(types, randomize);
        List<Exercise> exercises = generate(profile, picked);
        GrammarState state = new GrammarState(exercises, 0, 0, false);
        PracticeRun run = runs.create(profile.id(), "grammar", profile.cefr().name(), write(state));
        return new StartResult(run.id(), view(state));
    }

    public AnswerResult answer(long runId, String userAnswer) {
        PracticeRun run = runs.find(runId).orElseThrow(() -> new NotFoundException("Practice run " + runId));
        GrammarState state = read(run.stateJson());
        if (state.finished() || state.index() >= state.exercises().size()) {
            throw new IllegalArgumentException("Practice already finished");
        }
        Exercise ex = state.exercises().get(state.index());
        boolean correct = check(ex, userAnswer);
        if (!correct) {
            journalWrong(profiles.getOrCreateDefault().id(), userAnswer, ex.rule());
        }
        int nextIndex = state.index() + 1;
        int correctCount = state.correct() + (correct ? 1 : 0);
        boolean finished = nextIndex >= state.exercises().size();
        GrammarState next = new GrammarState(state.exercises(), nextIndex, correctCount, finished);
        Instant ended = finished ? Instant.now() : null;
        runs.save(new PracticeRun(run.id(), run.userId(), run.kind(), run.cefr(), write(next), run.startedAt(), ended));
        ExerciseView nextView = finished ? null : view(next);
        return new AnswerResult(correct, ex.answer(), nextView, finished);
    }

    private void journalWrong(long userId, String text, String rule) {
        if (text == null || text.isBlank()) {
            return;
        }
        List<LtMatch> found = checker.check(text);
        for (LtMatch m : found) {
            errors.record(userId, m.ruleId(), "lt", m.category(), m.title(), m.span());
        }
        if (found.isEmpty() && rule != null && !rule.isBlank()) {
            String slug = "GRAMMAR_" + rule.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
            errors.record(userId, slug, "llm", "Grammar", rule, text.length() > 120 ? text.substring(0, 120) : text);
        }
    }

    private boolean check(Exercise ex, String userAnswer) {
        String user = SpeechAligner.normalizeAnswer(userAnswer);
        String expected = SpeechAligner.normalizeAnswer(ex.answer());
        GrammarExerciseType type = GrammarExerciseType.parse(ex.type());
        if (type == GrammarExerciseType.CHOICE || type == GrammarExerciseType.GAP) {
            return !user.isEmpty() && user.equals(expected);
        }
        if (user.equals(expected)) {
            return true;
        }
        List<LtMatch> issues = checker.check(userAnswer == null ? "" : userAnswer);
        return issues.isEmpty() && user.length() >= expected.length() / 2;
    }

    private List<Exercise> generate(UserProfile profile, List<GrammarExerciseType> types) {
        if (!llm.status().up()) {
            return fallbackExercises(types);
        }
        List<ErrorEvent> weak = errors.top(profile.id(), 3);
        String weakRules = weak.stream().map(ErrorEvent::title).reduce((a, b) -> a + "; " + b).orElse("articles");
        String typeList = types.stream().map(GrammarExerciseType::json).reduce((a, b) -> a + ", " + b).orElse("gap");
        String prompt = """
                Create 6 short English grammar exercises for CEFR %s.
                Allowed types only (every exercise type must be one of): %s.
                Weak rules to target: %s.
                gap: one ___ in the prompt, empty choices, answer is the word for the blank.
                choice: one ___ or a question; 3-4 choices; answer must exactly equal one choice string.
                rewrite: one incorrect sentence; answer is the corrected full sentence; empty choices.
                find_error: one incorrect sentence; answer is the corrected sentence; empty choices.
                Do not mix a wrong verb and a trailing ___. Keep prompts under 100 characters.
                """.formatted(profile.cefr(), typeList, weakRules);
        List<Exercise> fallback = fallbackExercises(types);
        try {
            String raw = llm.chat(List.of(LlmMessage.user(prompt)), 0.35, GrammarDrillSchema.schema(), null);
            LlmGrammarDrill drill = mapper.readValue(raw, LlmGrammarDrill.class);
            if (drill.exercises() == null || drill.exercises().isEmpty()) {
                return fallback;
            }
            List<Exercise> rawList = new ArrayList<>();
            for (LlmGrammarDrill.Item item : drill.exercises()) {
                if (item.prompt() == null || item.answer() == null) {
                    continue;
                }
                rawList.add(new Exercise(
                        item.type() == null ? "gap" : item.type(),
                        item.prompt().strip(),
                        item.choices() == null ? List.of() : item.choices(),
                        item.answer().strip(),
                        item.rule() == null ? "" : item.rule().strip()));
            }
            return GrammarDrillNormalizer.prepare(rawList, types, fallback);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private List<Exercise> fallbackExercises(List<GrammarExerciseType> types) {
        List<Exercise> base = List.of(
                new Exercise("gap", "I ___ happy today.", List.of(), "am", "to be with I"),
                new Exercise("gap", "She ___ to work every day.", List.of(), "goes", "Present Simple 3rd person"),
                new Exercise("gap", "There ___ two cats in the garden.", List.of(), "are", "there is/are"),
                new Exercise("choice", "___ you like tea?", List.of("Do", "Does", "Are"), "Do", "Questions with you"),
                new Exercise("choice", "He ___ coffee every morning.", List.of("drink", "drinks", "drinking"), "drinks", "3rd person -s"),
                new Exercise("choice", "I have lived here ___ 2020.", List.of("for", "since", "from"), "since", "since + year"),
                new Exercise("rewrite", "He don't like coffee.", List.of(), "He doesn't like coffee.", "doesn't with he"),
                new Exercise("find_error", "There is many books on the shelf.", List.of(),
                        "There are many books on the shelf.", "There are + plural"));
        Set<GrammarExerciseType> allowed = new HashSet<>(types);
        List<Exercise> filtered = base.stream()
                .filter(e -> allowed.contains(GrammarExerciseType.parse(e.type())))
                .toList();
        if (filtered.isEmpty()) {
            filtered = base;
        }
        List<Exercise> out = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            out.add(filtered.get(i % filtered.size()));
        }
        return out;
    }

    private List<GrammarExerciseType> pickTypes(List<String> types, boolean randomize) {
        List<GrammarExerciseType> all = List.of(
                GrammarExerciseType.GAP, GrammarExerciseType.CHOICE,
                GrammarExerciseType.REWRITE, GrammarExerciseType.FIND_ERROR);
        List<GrammarExerciseType> picked = new ArrayList<>();
        if (types != null) {
            for (String t : types) {
                try {
                    picked.add(GrammarExerciseType.parse(t));
                } catch (IllegalArgumentException ignored) {
                    /* skip */
                }
            }
        }
        if (picked.isEmpty()) {
            picked = new ArrayList<>(all);
        }
        if (randomize) {
            Collections.shuffle(picked, random);
        }
        return picked;
    }

    private ExerciseView view(GrammarState state) {
        if (state.finished() || state.index() >= state.exercises().size()) {
            return null;
        }
        Exercise ex = state.exercises().get(state.index());
        return new ExerciseView(state.index() + 1, state.exercises().size(), ex.type(), ex.prompt(), ex.choices(), ex.rule());
    }

    private String write(GrammarState state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private GrammarState read(String json) {
        try {
            return mapper.readValue(json, GrammarState.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}