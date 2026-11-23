package study.ai_foreign_language_teacher.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Локальный банк закрытых пунктов проверки уровня. Ключ наружу не отдаётся. */
@Component
public class PlacementBank {

    private final List<Item> items;

    public PlacementBank() {
        this(load());
    }

    PlacementBank(List<Item> items) {
        this.items = List.copyOf(items);
    }

    public Item byId(String id) {
        if (id == null) {
            return null;
        }
        for (Item item : items) {
            if (item.id().equals(id)) {
                return item;
            }
        }
        return null;
    }

    public List<Item> ladder(String cefr) {
        return items.stream()
                .filter(item -> item.boundary() == null || item.boundary().isBlank())
                .filter(item -> cefr.equals(item.cefr()))
                .toList();
    }

    public List<Item> boundary(String boundary) {
        return items.stream()
                .filter(item -> boundary != null && boundary.equals(item.boundary()))
                .toList();
    }

    private static List<Item> load() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = PlacementBank.class.getResourceAsStream("/placement/bank.json")) {
            if (in == null) {
                throw new IllegalStateException("placement/bank.json is missing");
            }
            File root = mapper.readValue(in, File.class);
            if (root.items() == null || root.items().isEmpty()) {
                throw new IllegalStateException("placement bank is empty");
            }
            return root.items();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read placement bank", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record File(List<Item> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String id, String cefr, String skill, String boundary, String stem,
                       List<String> choices, String key) {
    }
}
