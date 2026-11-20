package study.ai_foreign_language_teacher.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import study.ai_foreign_language_teacher.api.Dto.SpeakingGradeView;
import study.ai_foreign_language_teacher.api.Dto.SpeakingPhraseView;
import study.ai_foreign_language_teacher.practice.SpeakingPracticeService;

import java.io.IOException;

@RestController
@RequestMapping("/api/practice/speaking")
public class PracticeSpeakingController {

    private final SpeakingPracticeService speaking;

    public PracticeSpeakingController(SpeakingPracticeService speaking) {
        this.speaking = speaking;
    }

    @PostMapping("/start")
    public SpeakingPhraseView start() {
        return SpeakingPhraseView.of(speaking.start());
    }

    @PostMapping("/{runId}/next")
    public SpeakingPhraseView next(@PathVariable long runId) {
        return SpeakingPhraseView.of(speaking.nextPhrase(runId));
    }

    @PostMapping(path = "/{runId}/speak", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SpeakingGradeView speak(@PathVariable long runId, @RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Пустая запись");
        }
        return SpeakingGradeView.of(speaking.grade(runId, file.getBytes(), file.getContentType()));
    }
}