package study.ai_foreign_language_teacher.export;

import org.springframework.stereotype.Service;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.FsrsState;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.ReviewRepository;
import study.ai_foreign_language_teacher.memory.rules.CardCatalog;
import study.ai_foreign_language_teacher.memory.rules.ReviewService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AnkiExportService {

    private final ProfileRepository profiles;
    private final ReviewRepository reviews;
    private final CardCatalog catalog;
    private final ReviewService reviewQueue;

    public AnkiExportService(ProfileRepository profiles, ReviewRepository reviews, CardCatalog catalog,
                             ReviewService reviewQueue) {
        this.profiles = profiles;
        this.reviews = reviews;
        this.catalog = catalog;
        this.reviewQueue = reviewQueue;
    }

    /**
     * @param scope due — сегодняшняя очередь FSRS; fsrs — все карточки из повторения; catalog — весь учебник по уровню профиля
     */
    public byte[] csv(String scope) {
        UserProfile profile = profiles.getOrCreateDefault();
        String mode = scope == null || scope.isBlank() ? "fsrs" : scope.trim().toLowerCase();
        List<RuleCard> cards = switch (mode) {
            case "due" -> reviewQueue.queue().cards();
            case "catalog" -> catalogForLevel(profile.cefr());
            case "fsrs" -> cardsFromFsrs(profile.id());
            default -> throw new IllegalArgumentException("scope must be due, fsrs or catalog");
        };
        return AnkiCsv.export(cards);
    }

    private List<RuleCard> cardsFromFsrs(long userId) {
        Set<String> slugs = new LinkedHashSet<>();
        for (FsrsState state : reviews.allForUser(userId)) {
            slugs.add(state.slug());
        }
        List<RuleCard> cards = new ArrayList<>();
        for (String slug : slugs) {
            catalog.find(slug).ifPresent(cards::add);
        }
        cards.sort(Comparator.comparing(RuleCard::slug));
        return List.copyOf(cards);
    }

    private List<RuleCard> catalogForLevel(Cefr level) {
        return catalog.all().stream()
                .filter(c -> Cefr.parse(c.cefrMin()).ordinal() <= level.ordinal()
                        && Cefr.parse(c.cefrMax()).ordinal() >= level.ordinal())
                .toList();
    }

    public int count(String scope) {
        UserProfile profile = profiles.getOrCreateDefault();
        String mode = scope == null || scope.isBlank() ? "fsrs" : scope.trim().toLowerCase();
        return switch (mode) {
            case "due" -> reviewQueue.queue().due();
            case "catalog" -> catalogForLevel(profile.cefr()).size();
            case "fsrs" -> reviews.allForUser(profile.id()).size();
            default -> 0;
        };
    }
}