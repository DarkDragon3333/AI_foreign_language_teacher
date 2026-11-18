package study.ai_foreign_language_teacher.api;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.api.Dto.GrammarAnswerRequest;
import study.ai_foreign_language_teacher.api.Dto.GrammarAnswerView;
import study.ai_foreign_language_teacher.api.Dto.GrammarStartRequest;
import study.ai_foreign_language_teacher.api.Dto.GrammarStartView;
import study.ai_foreign_language_teacher.practice.GrammarPracticeService;

@RestController
@RequestMapping("/api/practice/grammar")
public class PracticeGrammarController {

    private final GrammarPracticeService grammar;

    public PracticeGrammarController(GrammarPracticeService grammar) {
        this.grammar = grammar;
    }

    @PostMapping("/start")
    public GrammarStartView start(@RequestBody(required = false) GrammarStartRequest body) {
        GrammarPracticeService.StartResult r = grammar.start(
                body == null ? null : body.types(),
                body != null && Boolean.TRUE.equals(body.random()));
        return GrammarStartView.of(r);
    }

    @PostMapping("/{runId}/answer")
    public GrammarAnswerView answer(@PathVariable long runId, @RequestBody GrammarAnswerRequest body) {
        String ans = body == null ? "" : body.answer();
        return GrammarAnswerView.of(grammar.answer(runId, ans));
    }
}