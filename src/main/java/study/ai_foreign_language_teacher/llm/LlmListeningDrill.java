package study.ai_foreign_language_teacher.llm;

import java.util.List;

public record LlmListeningDrill(String passage, List<Question> questions) {

    public record Question(String question, List<String> acceptable) {
    }
}