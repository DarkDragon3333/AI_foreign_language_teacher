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
import study.ai_foreign_language_teacher.api.Dto.PlacementLadderAnswer;
import study.ai_foreign_language_teacher.api.Dto.MessageView;
import study.ai_foreign_language_teacher.api.Dto.SessionView;
import study.ai_foreign_language_teacher.api.Dto.StartSessionRequest;
import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.session.LessonService;
import study.ai_foreign_language_teacher.session.StructuredLessonService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final LessonService lessons;
    private final ObjectMapper mapper;
    private final StructuredLessonService structured;

    public SessionController(LessonService lessons, ObjectMapper mapper, StructuredLessonService structured) {
        this.lessons = lessons;
        this.mapper = mapper;
        this.structured = structured;
    }

    private SessionView view(LessonSession s) {
        return SessionView.of(s, LessonService.SessionExtras.of(s, lessons));
    }

    /** Начать урок. Все поля необязательны: уровень берётся из профиля, режим — conversation. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView start(@RequestBody(required = false) StartSessionRequest request) {
        StartSessionRequest r = request == null ? new StartSessionRequest(null, null, null, null, null) : request;
        return view(lessons.startSession(r.cefr(), r.topic(), r.mode(), r.model(), r.durationMin()));
    }

    @GetMapping
    public List<SessionView> recent() {
        return lessons.recentSessions(20).stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable long id) {
        return view(lessons.getSession(id));
    }

    @GetMapping("/{id}/messages")
    public List<MessageView> messages(@PathVariable long id) {
        return lessons.history(id).stream().map(m -> MessageView.of(m, reply(m))).toList();
    }

    /** Слайдер уровня и кнопки «проще / сложнее» меняют CEFR текущего урока. */
    @PutMapping("/{id}/level")
    public SessionView level(@PathVariable long id, @RequestBody LevelRequest request) {
        return view(lessons.changeLevel(id, request.cefr()));
    }

    @PostMapping("/{id}/end")
    public SessionView end(@PathVariable long id) {
        return view(lessons.endSession(id));
    }

    @PostMapping("/{id}/placement/ladder")
    public SessionView placementLadder(@PathVariable long id, @RequestBody PlacementLadderAnswer request) {
        if (request == null || request.itemId() == null || request.itemId().isBlank()) {
            throw new IllegalArgumentException("itemId is required");
        }
        return view(lessons.answerPlacementLadder(id, request.itemId(), request.choice()));
    }

    @PostMapping("/{id}/placement/ladder/accept")
    public SessionView placementLadderAccept(@PathVariable long id) {
        return view(lessons.acceptPlacementLevel(id));
    }

    @PostMapping("/{id}/placement/ladder/refine")
    public SessionView placementLadderRefine(@PathVariable long id) {
        return view(lessons.refinePlacement(id));
    }

  /** Пропуск теста: ручной CEFR и типовой план. */
    @PostMapping("/{id}/placement/manual")
    public SessionView placementManual(@PathVariable long id, @RequestBody LevelRequest request) {
        if (request.cefr() == null || request.cefr().isBlank()) {
            throw new IllegalArgumentException("cefr is required");
        }
        return view(lessons.applyPlacementManual(id, request.cefr()));
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
