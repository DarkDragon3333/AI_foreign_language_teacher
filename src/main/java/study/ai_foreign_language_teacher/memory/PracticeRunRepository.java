package study.ai_foreign_language_teacher.memory;

import study.ai_foreign_language_teacher.domain.PracticeRun;

import java.util.Optional;

public interface PracticeRunRepository {

    PracticeRun create(long userId, String kind, String cefr, String stateJson);

    Optional<PracticeRun> find(long id);

    PracticeRun save(PracticeRun run);
}