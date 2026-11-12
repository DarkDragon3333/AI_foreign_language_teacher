package study.ai_foreign_language_teacher;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Профиль memory: контекст поднимается без Postgres и Ollama. */
@SpringBootTest
@ActiveProfiles("memory")
class AiForeignLanguageTeacherApplicationTests {

    @Test
    void contextLoads() {
    }

}
