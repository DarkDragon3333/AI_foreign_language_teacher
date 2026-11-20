package study.ai_foreign_language_teacher.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.api.Dto.ListeningCustomRequest;
import study.ai_foreign_language_teacher.api.Dto.ListeningStartView;
import study.ai_foreign_language_teacher.api.Dto.ListeningSubmitRequest;
import study.ai_foreign_language_teacher.api.Dto.ListeningSubmitView;
import study.ai_foreign_language_teacher.practice.ListeningPracticeService;

@RestController
@RequestMapping("/api/practice/listening")
public class PracticeListeningController {

    private final ListeningPracticeService listening;

    public PracticeListeningController(ListeningPracticeService listening) {
        this.listening = listening;
    }

    @PostMapping("/start")
    public ListeningStartView startGenerated() {
        return ListeningStartView.of(listening.startGenerated());
    }

    @PostMapping("/custom")
    public ListeningStartView startCustom(@RequestBody ListeningCustomRequest body) {
        return ListeningStartView.of(listening.startCustom(body == null ? null : body.text()));
    }

    @PostMapping("/{runId}/submit")
    public ListeningSubmitView submit(@PathVariable long runId, @RequestBody ListeningSubmitRequest body) {
        return ListeningSubmitView.of(listening.submit(runId, body == null ? null : body.answers()));
    }
}