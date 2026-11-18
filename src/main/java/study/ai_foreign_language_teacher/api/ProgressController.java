package study.ai_foreign_language_teacher.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.api.Dto.DailyProgressView;
import study.ai_foreign_language_teacher.session.LessonService;

@RestController
@RequestMapping("/api/progress")
public class ProgressController {

    private final LessonService lessons;

    public ProgressController(LessonService lessons) {
        this.lessons = lessons;
    }

    @GetMapping("/today")
    public DailyProgressView today() {
        return DailyProgressView.of(lessons.todayProgress());
    }
}