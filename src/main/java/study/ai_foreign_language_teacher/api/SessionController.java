package study.ai_foreign_language_teacher.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.api.Dto.LevelRequest;
import study.ai_foreign_language_teacher.api.Dto.MessageView;
import study.ai_foreign_language_teacher.api.Dto.SessionView;
import study.ai_foreign_language_teacher.api.Dto.StartSessionRequest;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.session.LessonService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final LessonService lessons;
    private final ObjectMapper mapper;

    public SessionController(LessonService lessons, ObjectMapper mapper) {
        this.lessons = lessons;
        this.mapper = mapper;
    }

    /** Начать урок. Все поля необязательны: уровень берётся из профиля, режим — conversation. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView start(@RequestBody(required = false) StartSessionRequest request) {
        StartSessionRequest r = request == null ? new StartSessionRequest(null, null, null) : request;
        return SessionView.of(lessons.startSession(r.cefr(), r.topic(), r.mode()));
    }

    @GetMapping
    public List<SessionView> recent() {
        return lessons.recentSessions(20).stream().map(SessionView::of).toList();
    }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable long id) {
        return SessionView.of(lessons.getSession(id));
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable long id) {
        return lessons.history(id).stream().map(m -> MessageView.of(m, reply(m))).toList();
    }

    /** Слайдер уровня и кнопки «проще / сложнее» меняют CEFR текущего урока. */
    @PutMapping("/{id}/level")
    public SessionView level(@PathVariable long id, @RequestBody LevelRequest request) {
        return SessionView.of(lessons.changeLevel(id, request.cefr()));
    }

    @PostMapping("/{id}/end")
    public SessionView end(@PathVariable long id) {
        return SessionView.of(lessons.endSession(id));
    }

    private TeacherReply reply(ChatMessage m) {
        if (m.payloadJson() == null) {
            return null;
        }
        try {
            return mapper.readValue(m.payloadJson(), TeacherReply.class);
        } catch (JacksonException e) {
            return null;
        }
    }
}
