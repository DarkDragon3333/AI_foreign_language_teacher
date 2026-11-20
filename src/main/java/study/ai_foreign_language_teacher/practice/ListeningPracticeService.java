package study.ai_foreign_language_teacher.practice;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.domain.PracticeRun;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.llm.ListeningDrillSchema;
import study.ai_foreign_language_teacher.llm.LlmClient;
import study.ai_foreign_language_teacher.llm.LlmListeningDrill;
import study.ai_foreign_language_teacher.llm.LlmMessage;
import study.ai_foreign_language_teacher.memory.PracticeRunRepository;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ListeningPracticeService {

    private static final int MAX_CUSTOM_WORDS = 220;

    private final ProfileRepository profiles;
    private final PracticeRunRepository runs;
    private final LlmClient llm;
    private final ObjectMapper mapper;

    public ListeningPracticeService(ProfileRepository profiles, PracticeRunRepository runs, LlmClient llm,
                                    ObjectMapper mapper) {
        this.profiles = profiles;
        this.runs = runs;
        this.llm = llm;
        this.mapper = mapper;
    }

    public record QuestionView(String question, List<String> acceptable) {
    }

    public record ListeningState(String passage, List<QuestionView> questions, String warning, boolean submitted,
                                 List<Boolean> results) {
    }

    public record StartView(long runId, String passage, List<QuestionView> questions, String warning) {
    }

    public record SubmitView(int correct, int total, List<Boolean> results) {
    }

    public StartView startGenerated() {
        UserProfile profile = profiles.getOrCreateDefault();
        LlmListeningDrill drill = generate(profile);
        ListeningState state = new ListeningState(drill.passage(), mapQuestions(drill), null, false, List.of());
        PracticeRun run = runs.create(profile.id(), "listening", profile.cefr().name(), write(state));
        return new StartView(run.id(), state.passage(), state.questions(), state.warning());
    }

    public StartView startCustom(String text) {
        UserProfile profile = profiles.getOrCreateDefault();
        String passage = text == null ? "" : text.strip();
        if (passage.isBlank()) {
            throw new IllegalArgumentException("Paste some English text");
        }
        if (wordCount(passage) > MAX_CUSTOM_WORDS) {
            throw new IllegalArgumentException("Text is too long (max " + MAX_CUSTOM_WORDS + " words)");
        }
        String warning = TextComplexity.warnIfHard(passage, profile.cefr());
        LlmListeningDrill drill = generateQuestions(profile, passage);
        ListeningState state = new ListeningState(passage, mapQuestions(drill), warning, false, List.of());
        PracticeRun run = runs.create(profile.id(), "listening", profile.cefr().name(), write(state));
        return new StartView(run.id(), state.passage(), state.questions(), warning);
    }

    public SubmitView submit(long runId, List<String> answers) {
        PracticeRun run = runs.find(runId).orElseThrow(() -> new NotFoundException("Practice run " + runId));
        ListeningState state = read(run.stateJson());
        List<Boolean> results = new ArrayList<>();
        int correct = 0;
        for (int i = 0; i < state.questions().size(); i++) {
            String given = i < answers.size() && answers.get(i) != null ? answers.get(i) : "";
            boolean ok = matches(state.questions().get(i), given);
            results.add(ok);
            if (ok) {
                correct++;
            }
        }
        ListeningState next = new ListeningState(state.passage(), state.questions(), state.warning(), true, results);
        runs.save(new PracticeRun(run.id(), run.userId(), run.kind(), run.cefr(), write(next), run.startedAt(), Instant.now()));
        return new SubmitView(correct, state.questions().size(), results);
    }

    boolean matchesAcceptable(List<String> acceptable, String answer) {
        String norm = SpeechAligner.normalizeAnswer(answer);
        if (norm.isEmpty()) {
            return false;
        }
        String[] answerTokens = SpeechAligner.tokens(answer);
        for (String ok : acceptable) {
            String okNorm = SpeechAligner.normalizeAnswer(ok);
            if (okNorm.isEmpty()) {
                continue;
            }
            if (norm.equals(okNorm)) {
                return true;
            }
            String[] okTokens = SpeechAligner.tokens(ok);
            if (okTokens.length > 0 && answerEndsWith(answerTokens, okTokens)) {
                return true;
            }
        }
        return false;
    }

    private static boolean answerEndsWith(String[] answer, String[] suffix) {
        if (suffix.length > answer.length) {
            return false;
        }
        int offset = answer.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (!answer[offset + i].equals(suffix[i])) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(QuestionView q, String answer) {
        return matchesAcceptable(q.acceptable(), answer);
    }

    private LlmListeningDrill generate(UserProfile profile) {
        if (!llm.status().up()) {
            return fallback(profile);
        }
        String prompt = "Write a " + profile.cefr() + " level listening passage about 80-100 words and 3 short "
                + "comprehension questions with acceptable short answers.";
        try {
            String raw = llm.chat(List.of(LlmMessage.user(prompt)), 0.4, ListeningDrillSchema.schema(), null);
            LlmListeningDrill drill = mapper.readValue(raw, LlmListeningDrill.class);
            if (drill.passage() == null || drill.questions() == null || drill.questions().isEmpty()) {
                return fallback(profile);
            }
            return drill;
        } catch (RuntimeException e) {
            return fallback(profile);
        }
    }

    private LlmListeningDrill generateQuestions(UserProfile profile, String passage) {
        if (!llm.status().up()) {
            return new LlmListeningDrill(passage, fallback(profile).questions());
        }
        String prompt = "Passage:\n" + passage + "\n\nWrite 3 comprehension questions with short acceptable answers. "
                + "Return passage unchanged in JSON.";
        try {
            String raw = llm.chat(List.of(LlmMessage.user(prompt)), 0.3, ListeningDrillSchema.schema(), null);
            LlmListeningDrill drill = mapper.readValue(raw, LlmListeningDrill.class);
            if (drill.questions() == null || drill.questions().isEmpty()) {
                return new LlmListeningDrill(passage, fallback(profile).questions());
            }
            return new LlmListeningDrill(passage, drill.questions());
        } catch (RuntimeException e) {
            return new LlmListeningDrill(passage, fallback(profile).questions());
        }
    }

    private LlmListeningDrill fallback(UserProfile profile) {
        String passage = "Anna works at a small bookshop near the station. She opens the shop at nine and helps "
                + "customers find novels and travel guides. On Fridays she stays late to arrange new books on the shelves.";
        List<LlmListeningDrill.Question> questions = List.of(
                new LlmListeningDrill.Question("Where does Anna work?", List.of("bookshop", "a bookshop")),
                new LlmListeningDrill.Question("What time does she open?", List.of("nine", "9")),
                new LlmListeningDrill.Question("When does she stay late?", List.of("friday", "on fridays")));
        return new LlmListeningDrill(passage, questions);
    }

    private List<QuestionView> mapQuestions(LlmListeningDrill drill) {
        List<QuestionView> out = new ArrayList<>();
        for (LlmListeningDrill.Question q : drill.questions()) {
            out.add(new QuestionView(q.question(), q.acceptable() == null ? List.of() : q.acceptable()));
        }
        return out;
    }

    private int wordCount(String text) {
        return SpeechAligner.tokens(text).length;
    }

    private String write(ListeningState state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private ListeningState read(String json) {
        try {
            return mapper.readValue(json, ListeningState.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}