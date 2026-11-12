package study.ai_foreign_language_teacher.memory;

import study.ai_foreign_language_teacher.domain.UserProfile;

/** Приложение однопользовательское: профиль один, создаётся при первом обращении. */
public interface ProfileRepository {

    UserProfile getOrCreateDefault();

    UserProfile save(UserProfile profile);
}
