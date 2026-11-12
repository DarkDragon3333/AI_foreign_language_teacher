package study.ai_foreign_language_teacher.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import study.ai_foreign_language_teacher.api.Dto.ChatRequest;
import study.ai_foreign_language_teacher.api.Dto.ErrorView;
import study.ai_foreign_language_teacher.api.Dto.MessageView;
import study.ai_foreign_language_teacher.api.Dto.SessionView;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.llm.LlmException;
import study.ai_foreign_language_teacher.session.LessonService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Текстовый ход урока, ответ — поток SSE:
 *   event: delta  {"text": "..."}                 — куски reply_en по мере генерации
 *   event: final  {"session":…, "user":…, "teacher":…} — полный проверенный ответ
 *   event: error  {"error": "..."}
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final LessonService lessons;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(LessonService lessons) {
        this.lessons = lessons;
    }

    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        if (request.sessionId() == null) {
            throw new IllegalArgumentException("sessionId is required");
        }
        lessons.getSession(request.sessionId()); // 404 сразу, до открытия потока

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean clientGone = new AtomicBoolean(false);
        emitter.onError(e -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));

        executor.submit(() -> {
            try {
                LessonService.Turn turn = lessons.turn(request.sessionId(), request.text(),
                        Boolean.TRUE.equals(request.hint()),
                        text -> send(emitter, clientGone, "delta", Map.of("text", text)));

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("session", SessionView.of(turn.session()));
                body.put("user", turn.userMessage() == null ? null : MessageView.of(turn.userMessage(), null));
                body.put("teacher", MessageView.of(turn.teacherMessage(), turn.reply()));
                send(emitter, clientGone, "final", body);
            } catch (IllegalArgumentException | NotFoundException | LlmException e) {
                log.warn("Chat turn failed: {}", e.getMessage());
                send(emitter, clientGone, "error", new ErrorView(e.getMessage()));
            } catch (RuntimeException e) {
                log.error("Chat turn failed", e);
                send(emitter, clientGone, "error", new ErrorView("Internal error, see server log"));
            } finally {
                if (!clientGone.get()) {
                    emitter.complete();
                }
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, AtomicBoolean clientGone, String event, Object data) {
        if (clientGone.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            clientGone.set(true); // ученик закрыл вкладку — генерацию доводим до конца и сохраняем, но не шлём
        }
    }
}
