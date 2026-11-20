package study.ai_foreign_language_teacher.practice;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.domain.PracticeRun;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.llm.LlmClient;
import study.ai_foreign_language_teacher.llm.LlmMessage;
import study.ai_foreign_language_teacher.llm.LlmSpeakingPhrases;
import study.ai_foreign_language_teacher.llm.SpeakingPhrasesSchema;
import study.ai_foreign_language_teacher.memory.PracticeRunRepository;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.speech.SttClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

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

    public record SpeakingState(List<String> phrases, int index, int attempts) {
        String currentPhrase() {
            return phrases.get(index);
        }
    }

    public record PhraseView(long runId, String phrase) {
    }

    public record GradeView(String heard, List<SpeechAligner.WordMismatch> mismatches) {
    }

    public PhraseView start() {
        UserProfile profile = profiles.getOrCreateDefault();
        List<String> phrases = generatePhrases(profile);
        SpeakingState state = new SpeakingState(phrases, 0, 0);
        PracticeRun run = runs.create(profile.id(), "speaking", profile.cefr().name(), write(state));
        return new PhraseView(run.id(), phrases.getFirst());
    }

    public PhraseView nextPhrase(long runId) {
        PracticeRun run = runs.find(runId).orElseThrow(() -> new NotFoundException("Practice run " + runId));
        SpeakingState state = read(run.stateJson());
        int nextIndex = state.index() + 1;
        if (nextIndex >= state.phrases().size()) {
            throw new IllegalArgumentException("Practice already finished");
        }
        SpeakingState next = new SpeakingState(state.phrases(), nextIndex, 0);
        runs.save(new PracticeRun(run.id(), run.userId(), run.kind(), run.cefr(), write(next), run.startedAt(), null));
        return new PhraseView(run.id(), state.phrases().get(nextIndex));
    }

    public GradeView grade(long runId, byte[] audio, String contentType) {
        PracticeRun run = runs.find(runId).orElseThrow(() -> new NotFoundException("Practice run " + runId));
        SpeakingState state = read(run.stateJson());
        String heard = stt.transcribe(audio, contentType, null);
        List<SpeechAligner.WordMismatch> mismatches = SpeechAligner.compare(state.currentPhrase(), heard);
        boolean success = mismatches.isEmpty();
        Instant ended = success && state.index() >= state.phrases().size() - 1 ? Instant.now() : null;
        SpeakingState next = new SpeakingState(state.phrases(), state.index(), state.attempts() + 1);
        runs.save(new PracticeRun(run.id(), run.userId(), run.kind(), run.cefr(), write(next), run.startedAt(), ended));
        return new GradeView(heard, mismatches);
    }

    private List<String> generatePhrases(UserProfile profile) {
        if (!llm.status().up()) {
            return SpeakingPhraseSet.prepare(null, profile.cefr());
        }
        String prompt = """
                Write 6 different short natural English sentences for speaking practice at CEFR %s.
                Each sentence must be unique (different topic or wording). No quotes, no numbering.
                """.formatted(profile.cefr());
        try {
            String raw = llm.chat(List.of(LlmMessage.user(prompt)), 0.55, SpeakingPhrasesSchema.schema(), null);
            LlmSpeakingPhrases parsed = mapper.readValue(raw, LlmSpeakingPhrases.class);
            List<String> prepared = SpeakingPhraseSet.prepare(parsed.phrases(), profile.cefr());
            if (prepared.size() >= SpeakingPhraseSet.SIZE) {
                return prepared;
            }
        } catch (RuntimeException ignored) {
            /* fallback */
        }
        return SpeakingPhraseSet.prepare(null, profile.cefr());
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