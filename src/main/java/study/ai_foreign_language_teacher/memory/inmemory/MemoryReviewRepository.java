package study.ai_foreign_language_teacher.memory.inmemory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import study.ai_foreign_language_teacher.domain.FsrsState;
import study.ai_foreign_language_teacher.memory.ReviewRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("memory")
public class MemoryReviewRepository implements ReviewRepository {

    private final ConcurrentHashMap<String, FsrsState> cards = new ConcurrentHashMap<>();

    @Override
    public Optional<FsrsState> find(long userId, String slug) {
        return Optional.ofNullable(cards.get(key(userId, slug)));
    }

    @Override
    public void save(FsrsState state) {
        cards.put(key(state.userId(), state.slug()), state);
    }

    @Override
    public List<FsrsState> dueBy(long userId, Instant until) {
        String prefix = userId + ":";
        return cards.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix) && !e.getValue().due().isAfter(until))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(FsrsState::due))
                .toList();
    }

    private static String key(long userId, String slug) {
        return userId + ":" + slug;
    }
}
