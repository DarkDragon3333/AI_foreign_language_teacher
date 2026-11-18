package study.ai_foreign_language_teacher.memory.inmemory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.PracticeRun;
import study.ai_foreign_language_teacher.memory.PracticeRunRepository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("memory")
public class InMemoryPracticeRunRepository implements PracticeRunRepository {

    private final AtomicLong seq = new AtomicLong();
    private final Map<Long, PracticeRun> runs = new ConcurrentHashMap<>();

    @Override
    public PracticeRun create(long userId, String kind, String cefr, String stateJson) {
        PracticeRun run = new PracticeRun(seq.incrementAndGet(), userId, kind, Cefr.parse(cefr), stateJson,
                Instant.now(), null);
        runs.put(run.id(), run);
        return run;
    }

    @Override
    public Optional<PracticeRun> find(long id) {
        return Optional.ofNullable(runs.get(id));
    }

    @Override
    public PracticeRun save(PracticeRun run) {
        runs.put(run.id(), run);
        return run;
    }
}