package study.ai_foreign_language_teacher.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.api.Dto.RateRequest;
import study.ai_foreign_language_teacher.api.Dto.RateResult;
import study.ai_foreign_language_teacher.api.Dto.ReviewCard;
import study.ai_foreign_language_teacher.api.Dto.ReviewQueue;
import study.ai_foreign_language_teacher.memory.rules.ReviewService;

/** Очередь FSRS: какие карточки правил повторить сегодня и как сдвинуть срок. */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    public ReviewQueue queue() {
        ReviewService.Queue queue = reviews.queue();
        return new ReviewQueue(queue.due(), queue.cards().stream().map(ReviewCard::of).toList());
    }

    @PostMapping("/{slug}")
    public RateResult rate(@PathVariable String slug, @RequestBody RateRequest body) {
        ReviewService.Rated rated = reviews.rate(slug, body == null ? 0 : body.rating());
        return new RateResult(rated.slug(), rated.due(), rated.intervalDays(), rated.remaining());
    }
}
