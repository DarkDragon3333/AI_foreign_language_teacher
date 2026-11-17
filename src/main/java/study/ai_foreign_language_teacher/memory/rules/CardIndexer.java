package study.ai_foreign_language_teacher.memory.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.memory.EmbeddingStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** При старте кладёт текст карточек в базу и, если sidecar жив, досчитывает недостающие векторы. Урок этого не ждёт. */
@Component
public class CardIndexer {

    private static final Logger log = LoggerFactory.getLogger(CardIndexer.class);
    private static final int BATCH = 16;

    private final CardCatalog catalog;
    private final EmbeddingStore store;
    private final EmbeddingClient embeddings;

    public CardIndexer(CardCatalog catalog, EmbeddingStore store, EmbeddingClient embeddings) {
        this.catalog = catalog;
        this.store = store;
        this.embeddings = embeddings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread.startVirtualThread(this::run);
    }

    private void run() {
        try {
            for (RuleCard card : catalog.all()) {
                store.upsert(card, catalog.hash(card.slug()));
            }
            Map<String, float[]> saved = store.loadMatching(catalog.hashes());
            saved.forEach(catalog::setVector);
        } catch (RuntimeException e) {
            log.warn("Не удалось записать карточки в базу: {}", e.toString());
        }
        if (!embeddings.enabled()) {
            log.info("Карточек правил: {}. Поиск по смыслу выключен", catalog.all().size());
            return;
        }
        int quiet = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (fillMissing()) {
                    log.info("Векторы карточек готовы: {}", catalog.all().size());
                    return;
                }
            } catch (Exception e) {
                if (quiet++ % 5 == 0) {
                    log.info("Жду модель эмбеддингов: {}", e.toString());
                }
            }
            try {
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean fillMissing() throws Exception {
        List<RuleCard> missing = catalog.all().stream().filter(c -> catalog.vector(c.slug()) == null).toList();
        if (missing.isEmpty()) {
            return true;
        }
        if (!embeddings.ready()) {
            return false;
        }
        for (int from = 0; from < missing.size(); from += BATCH) {
            List<RuleCard> batch = missing.subList(from, Math.min(missing.size(), from + BATCH));
            List<String> passages = new ArrayList<>();
            for (RuleCard card : batch) {
                passages.add(card.passage());
            }
            List<float[]> vectors = embeddings.embedPassages(passages);
            for (int i = 0; i < batch.size(); i++) {
                RuleCard card = batch.get(i);
                catalog.setVector(card.slug(), vectors.get(i));
                store.saveVector(card.slug(), catalog.hash(card.slug()), vectors.get(i));
            }
        }
        return catalog.all().stream().allMatch(c -> catalog.vector(c.slug()) != null);
    }
}
