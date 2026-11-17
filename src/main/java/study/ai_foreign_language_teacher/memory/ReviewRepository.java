package study.ai_foreign_language_teacher.memory;

import study.ai_foreign_language_teacher.domain.FsrsState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReviewRepository {

    Optional<FsrsState> find(long userId, String slug);

    void save(FsrsState state);

    /** Карточки, которые пора показать: срок не позже указанного момента. Сначала просроченные. */
    List<FsrsState> dueBy(long userId, Instant until);

    List<FsrsState> allForUser(long userId);
}
