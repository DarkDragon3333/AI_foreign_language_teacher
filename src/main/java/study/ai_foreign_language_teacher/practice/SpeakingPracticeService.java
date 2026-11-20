package study.ai_foreign_language_teacher.practice;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.domain.PracticeRun;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.llm.LlmClient;
import study.ai_foreign_language_teacher.llm.LlmMessage;
import study.ai_foreign_language_teacher.llm.SpeakingPhraseSchema;
import study.ai_foreign_language_teacher.memory.PracticeRunRepository;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.speech.SttClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class SpeakingPracticeService {

    private final ProfileRepository profiles;
    private final PracticeRunRepository runs;
    private final LlmClient llm;
    private final ObjectMapper mapper;
    private final SttClient stt;

    public SpeakingPracticeService(ProfileRepository profiles, PracticeRunRepository runs, LlmClient llm,
                                  ObjectMapper mapper, SttClient stt) {
        this.profiles = profiles;
        this.runs = runs;
        this.llm = llm;
        this.mapper = mapper;
        this.stt = stt;
    }

    public record SpeakingState(String phrase, int attempts) {
    }

    public record PhraseView(long runId, String phrase) {
    }

    public record GradeView(String heard, List<SpeechAligner.WordMismatch> mismatches) {
    }

    public PhraseView start() {
        UserProfile profile = profiles.getOrCreateDefault();
        String phrase = generatePhrase(profile);
        SpeakingState state = new SpeakingState(phrase, 0);
        PracticeRun run = runs.create(profile.id(), "speaking", profile.cefr().name(), write(state));
        return new PhraseView(run.id(), phrase);
    }

    public PhraseView nextPhrase(long runId) {
        PracticeRun run = runs.find(runId).orElseThrow(() -> new NotFoundException("Practice run " + runId));
        UserProfile profile = profiles.getOrCreateDefault();
        String phrase = generatePhrase(profile);
        SpeakingState state = new SpeakingState(phrase, 0);
        runs.save(new PracticeRun(run.id(), run.userId(), run.kind(), run.cefr(), write(state), run.startedAt(), null));
        return new PhraseView(run.id(), phrase);
    }

    public GradeView grade(long runId, byte[] audio, String contentType) {
        PracticeRun run = runs.find(runId).orElseThrow(() -> new NotFoundException("Practice run " + runId));
        SpeakingState state = read(run.stateJson());
        String heard = stt.transcribe(audio, contentType, null);
        List<SpeechAligner.WordMismatch> mismatches = SpeechAligner.compare(state.phrase(), heard);
        SpeakingState next = new SpeakingState(state.phrase(), state.attempts() + 1);
        Instant ended = mismatches.isEmpty() ? Instant.now() : null;
        runs.save(new PracticeRun(run.id(), run.userId(), run.kind(), run.cefr(), write(next), run.startedAt(), ended));
        return new GradeView(heard, mismatches);
    }

    private String generatePhrase(UserProfile profile) {
        if (!llm.status().up()) {
            return switch (profile.cefr()) {
                case A1, A2 -> "Good morning. How are you?";
                case B1, B2 -> "I would like to book a table for two.";
                default -> "The proposal raises several ethical concerns.";
            };
        }
        String prompt = "Write one short natural English sentence for a learner at CEFR "
                + profile.cefr() + ". No quotes, no explanation.";
        try {
            String raw = llm.chat(List.of(LlmMessage.user(prompt)), 0.5, SpeakingPhraseSchema.schema(), null);
            Map<?, ?> map = mapper.readValue(raw, Map.class);
            Object phrase = map.get("phrase");
            if (phrase != null && !phrase.toString().isBlank()) {
                return phrase.toString().strip();
            }
        } catch (RuntimeException ignored) {
            /* fallback */
        }
        return "Could you help me with this, please?";
    }

    private String write(SpeakingState state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private SpeakingState read(String json) {
        try {
            return mapper.readValue(json, SpeakingState.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }
}