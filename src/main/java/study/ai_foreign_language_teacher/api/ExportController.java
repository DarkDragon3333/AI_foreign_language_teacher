package study.ai_foreign_language_teacher.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.export.AnkiExportService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final AnkiExportService export;

    public ExportController(AnkiExportService export) {
        this.export = export;
    }

  /** Выгрузка карточек правил в CSV для импорта в Anki. */
    @GetMapping("/anki")
    public ResponseEntity<byte[]> anki(@RequestParam(defaultValue = "fsrs") String scope) {
        byte[] body = export.csv(scope);
        String file = "tutor-rules-" + scope + "-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}