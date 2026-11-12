package study.ai_foreign_language_teacher.nlp;

import java.util.List;

/** Проверяемый слой «знание правил»: детерминированный поиск ошибок. */
public interface GrammarChecker {

    /** Никогда не бросает исключений: если сервис недоступен, возвращает пустой список. */
    List<LtMatch> check(String text);

    Status status();

    record Status(boolean up, String detail) {
    }
}
