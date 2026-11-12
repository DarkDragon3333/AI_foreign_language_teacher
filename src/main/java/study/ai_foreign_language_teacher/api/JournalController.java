package study.ai_foreign_language_teacher.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.api.Dto.JournalEntry;
import study.ai_foreign_language_teacher.session.LessonService;

import java.util.List;

/** Журнал ошибок ученика: какие правила нарушались и как часто. */
@RestController
@RequestMapping("/api/errors")
public class JournalController {

    private final LessonService lessons;

    public JournalController(LessonService lessons) {
        this.lessons = lessons;
    }

    @GetMapping
    public List<JournalEntry> journal() {
        return lessons.errorJournal(100).stream().map(JournalEntry::of).toList();
    }
}
