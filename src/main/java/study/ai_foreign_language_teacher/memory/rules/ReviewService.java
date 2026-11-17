package study.ai_foreign_language_teacher.memory.rules;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.domain.FsrsState;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.domain.ReviewRating;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.ReviewRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Ошибка в уроке ставит карточку в очередь. Оценка 1–4 двигает срок по FSRS. */
@Service
public class ReviewService {

    private final ReviewRepository reviews;
    private final ProfileRepository profiles;
    private final CardCatalog catalog;

    public ReviewService(ReviewRepository reviews, ProfileRepository profiles, CardCatalog catalog) {
        this.reviews = reviews;
        this.profiles = profiles;
        this.catalog = catalog;
    }

    /** Ученик снова ошибся в правиле. Если карточка уже в сегодняшней очереди, второй раз срок не сдвигаем. */
    public void noteMistake(long userId, String slug) {
        if (catalog.find(slug).isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        Instant endOfToday = endOfToday(now);
        FsrsState existing = reviews.find(userId, slug).orElse(null);
        if (existing == null) {
            reviews.save(FsrsState.fresh(userId, slug, now));
            return;
        }
        if (!existing.due().isAfter(endOfToday)) {
            return;
        }
        if ("new".equals(existing.state()) || existing.reps() == 0) {
            reviews.save(new FsrsState(userId, slug, existing.state(), now, existing.stability(), existing.difficulty(),
                    existing.reps(), existing.lapses(), existing.lastReview()));
            return;
        }
        reviews.save(FsrsScheduler.review(existing, ReviewRating.AGAIN, now).state());
    }

    public Queue queue() {
        long userId = profiles.getOrCreateDefault().id();
        Instant end = endOfToday(Instant.now());
        List<FsrsState> due = reviews.dueBy(userId, end);
        List<RuleCard> cards = new ArrayList<>();
        for (FsrsState state : due) {
            catalog.find(state.slug()).ifPresent(cards::add);
        }
        return new Queue(cards.size(), List.copyOf(cards));
    }

    public Rated rate(String slug, int rating) {
        RuleCard card = catalog.find(slug).orElseThrow(() -> new NotFoundException("Карточка не найдена"));
        long userId = profiles.getOrCreateDefault().id();
        FsrsState state = reviews.find(userId, slug).orElseThrow(() -> new NotFoundException("Этой карточки нет в повторении"));
        Instant now = Instant.now();
        FsrsScheduler.Outcome outcome = FsrsScheduler.review(state, ReviewRating.parse(rating), now);
        reviews.save(outcome.state());
        int remaining = reviews.dueBy(userId, endOfToday(now)).size();
        return new Rated(card.slug(), outcome.state().due(), outcome.intervalDays(), remaining);
    }

    static Instant endOfToday(Instant now) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate tomorrow = now.atZone(zone).toLocalDate().plusDays(1);
        return tomorrow.atStartOfDay(zone).toInstant();
    }

    public record Queue(int due, List<RuleCard> cards) {
    }

    public record Rated(String slug, Instant due, long intervalDays, int remaining) {
    }
}
