package study.ai_foreign_language_teacher.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import study.ai_foreign_language_teacher.speech.SttClient;

import java.io.IOException;
import java.util.Map;

/** «Нажми и скажи»: запись с микрофона становится текстом, ход урока дальше обычный. */
@RestController
@RequestMapping("/api/audio")
public class AudioController {

    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private final SttClient stt;

    public AudioController(SttClient stt) {
        this.stt = stt;
    }

    @PostMapping("/stt")
    public Map<String, String> transcribe(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Пустая запись");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Запись слишком длинная");
        }
        String text = stt.transcribe(file.getBytes(), file.getContentType());
        if (text.isBlank()) {
            throw new IllegalArgumentException("Речь не распознана. Скажите фразу ещё раз.");
        }
        return Map.of("text", text);
    }
}
