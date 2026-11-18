package study.ai_foreign_language_teacher.practice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("memory")
class GrammarPracticeServiceTest {

    @Autowired
    GrammarPracticeService grammar;

    @Test
    void startsWithoutRandomGeneratorCrash() {
        GrammarPracticeService.StartResult r = grammar.start(List.of("gap", "choice"), false);
        assertTrue(r.runId() > 0);
        assertNotNull(r.current());
        assertFalse(r.current().prompt().isBlank());
    }
}