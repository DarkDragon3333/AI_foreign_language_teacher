package study.ai_foreign_language_teacher.api;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.session.LessonService;
import study.ai_foreign_language_teacher.speech.SttClient;
import study.ai_foreign_language_teacher.speech.SttNameHints;
import study.ai_foreign_language_teacher.speech.TtsClient;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Голос без дуплексного канала: запись становится текстом, готовая реплика — звуком. */
@RestController
@RequestMapping("/api/audio")
public class AudioController {

    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_SPOKEN_CHARS = 800;

    private final SttClient stt;
    private final TtsClient tts;
    private final LessonService lessons;

    public AudioController(SttClient stt, TtsClient tts, LessonService lessons) {
        this.stt = stt;
        this.tts = tts;
        this.lessons = lessons;
    }

    @PostMapping("/stt")
    public Map<String, String> transcribe(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "sessionId", required = false) Long sessionId)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Пустая запись");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Запись слишком длинная");
        }
        String prompt = null;
        Set<String> names = Set.of();
        if (sessionId != null) {
            try {
                LessonService.SpeechContext ctx = lessons.speechContext(sessionId);
                prompt = ctx.prompt();
                names = ctx.names();
            } catch (NotFoundException ignored) {
                // урок уже закрыт — распознаём без контекста
            }
        }
        String text = SttNameHints.apply(stt.transcribe(file.getBytes(), file.getContentType(), prompt).strip(), names);
        if (text.isBlank()) {
            throw new IllegalArgumentException("Речь не распознана. Скажите фразу ещё раз.");
        }
        return Map.of("text", text);
    }

    /** Озвучить английский текст реплики. Голос выбирает сервер. */
    @PostMapping(path = "/tts", produces = "audio/wav")
    public ResponseEntity<byte[]> speak(@RequestBody Map<String, String> body) {
        String text = body == null ? "" : body.getOrDefault("text", "");
        text = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (text.isBlank()) {
            throw new IllegalArgumentException("Нечего озвучивать");
        }
        if (text.length() > MAX_SPOKEN_CHARS) {
            text = text.substring(0, MAX_SPOKEN_CHARS);
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(tts.speak(text));
    }
}
