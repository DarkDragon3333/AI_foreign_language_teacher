package study.ai_foreign_language_teacher.llm;

import java.util.List;

public record LlmGrammarDrill(List<Item> exercises) {

    public record Item(String type, String prompt, List<String> choices, String answer, String rule) {
    }
}