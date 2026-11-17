package study.ai_foreign_language_teacher.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.llm.LlmClient;
import study.ai_foreign_language_teacher.memory.StorageHealth;
import study.ai_foreign_language_teacher.memory.rules.EmbeddingClient;
import study.ai_foreign_language_teacher.nlp.GrammarChecker;
import study.ai_foreign_language_teacher.speech.SttClient;

import java.util.LinkedHashMap;
import java.util.Map;

/** Агрегатор: приложение healthy, только когда отвечают все зависимости (раздел 10). */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final LlmClient llm;
    private final StorageHealth storage;
    private final GrammarChecker checker;
    private final EmbeddingClient embeddings;
    private final SttClient speech;

    public HealthController(LlmClient llm, StorageHealth storage, GrammarChecker checker, EmbeddingClient embeddings,
                            SttClient speech) {
        this.llm = llm;
        this.storage = storage;
        this.checker = checker;
        this.embeddings = embeddings;
        this.speech = speech;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        LlmClient.Status ollama = llm.status();
        boolean storageUp = storage.ready();
        GrammarChecker.Status lt = checker.status();

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("ollama", component(ollama.up(), ollama.detail()));
        components.put("storage", component(storageUp, storage.kind()));
        components.put("languagetool", component(lt.up(), lt.detail()));
        // поиск карточек по смыслу не обязателен: без него урок жив, карточки находятся по id LanguageTool
        boolean embedUp = !embeddings.enabled() || embeddings.ready();
        components.put("embed", component(embedUp, embeddings.statusDetail()));
        boolean sttUp = !speech.enabled() || speech.ready();
        components.put("stt", component(sttUp, speech.statusDetail()));
        // tts добавится на шаге 6

        boolean up = ollama.up() && storageUp && lt.up();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", up ? "up" : "down");
        body.put("components", components);
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private static Map<String, Object> component(boolean up, String detail) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", up ? "up" : "down");
        map.put("detail", detail);
        return map;
    }
}
