package study.ai_foreign_language_teacher.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.api.Dto.ScenarioListItem;
import study.ai_foreign_language_teacher.api.Dto.ScenarioProgressView;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.session.LessonService;
import study.ai_foreign_language_teacher.session.ScenarioService;

import java.util.List;

@RestController
@RequestMapping("/api/scenarios")
public class ScenarioController {

    private final ScenarioService scenarios;
    private final LessonService lessons;

    public ScenarioController(ScenarioService scenarios, LessonService lessons) {
        this.scenarios = scenarios;
        this.lessons = lessons;
    }

    @GetMapping
    public List<ScenarioListItem> list() {
        return scenarios.list().stream().map(ScenarioListItem::of).toList();
    }

    @GetMapping("/sessions/{sessionId}")
    public ScenarioProgressView progress(@PathVariable long sessionId) {
        var session = lessons.getSession(sessionId);
        return scenarios.progress(sessionId, session)
                .map(ScenarioProgressView::of)
                .orElseThrow(() -> new NotFoundException("Урок не в режиме ролевой игры"));
    }
}