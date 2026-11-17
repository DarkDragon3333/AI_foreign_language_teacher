package study.ai_foreign_language_teacher.memory;

import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonSession;

import java.util.List;
import java.util.Optional;

public interface SessionRepository {

    /** @param model модель для этого урока; null — модель по умолчанию */
    LessonSession create(long userId, LessonMode mode, String topic, Cefr cefr, String model);

    Optional<LessonSession> find(long id);

    List<LessonSession> recent(long userId, int limit);

    void updateSupportLevel(long id, int supportLevel);

    void updateCefr(long id, Cefr cefr);

    void end(long id);
}
