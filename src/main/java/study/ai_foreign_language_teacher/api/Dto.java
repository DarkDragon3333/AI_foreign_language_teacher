package study.ai_foreign_language_teacher.api;

import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.ErrorEvent;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.Role;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.domain.TeacherReply;
import study.ai_foreign_language_teacher.domain.UserProfile;

import java.time.Instant;
import java.util.List;

/** Формы запросов и ответов REST API. */
public final class Dto {

    private Dto() {
    }

    public record StartSessionRequest(String cefr, String topic, String mode, String model) {
    }

    public record ChatRequest(Long sessionId, String text, Boolean hint) {
    }

    public record LevelRequest(String cefr) {
    }

    public record ProfileRequest(String displayName, String cefr, Boolean voiceEnabled) {
    }

    public record SessionView(long id, String mode, String topic, String cefr, int supportLevel,
                              Instant startedAt, Instant endedAt, String model) {
        static SessionView of(LessonSession s) {
            return new SessionView(s.id(), s.mode().json(), s.topic(), s.cefr().name(), s.supportLevel(),
                    s.startedAt(), s.endedAt(), s.model());
        }
    }

    /** reply заполнен только у реплик учителя. */
    public record MessageView(long id, String role, String text, TeacherReply reply, Instant createdAt) {
        static MessageView of(ChatMessage m, TeacherReply reply) {
            return new MessageView(m.id(), m.role() == Role.USER ? "user" : "teacher", m.text(), reply, m.createdAt());
        }
    }

    public record ProfileView(long id, String displayName, String nativeLang, String targetLang, String cefr,
                              boolean voiceEnabled, List<String> levels, String model, String note) {
        static ProfileView of(UserProfile p, String model) {
            String note = switch (p.cefr()) {
                case C1, C2 -> "На уровнях C1–C2 стиль небольшой локальной модели упрощён: нюансы и идиомы она передаёт хуже, чем живой преподаватель.";
                default -> null;
            };
            return new ProfileView(p.id(), p.displayName(), p.nativeLang(), p.targetLang(), p.cefr().name(),
                    p.voiceEnabled(), List.of("A1", "A2", "B1", "B2", "C1", "C2"), model, note);
        }
    }

    public record JournalEntry(String ruleId, String source, String category, String title, String example,
                               int count, Instant lastSeen) {
        static JournalEntry of(ErrorEvent e) {
            return new JournalEntry(e.ruleId(), e.source(), e.category(), e.title(), e.sampleSpan(), e.count(), e.lastSeen());
        }
    }

    public record ReviewCard(String slug, String title, String titleRu, String cefr, String rule, String ruleRu,
                             String exampleOk, String exampleBad, String trap) {
        static ReviewCard of(RuleCard card) {
            String cefr = card.cefrMin().equals(card.cefrMax()) ? card.cefrMin() : card.cefrMin() + "–" + card.cefrMax();
            return new ReviewCard(card.slug(), card.title(), card.titleRu(), cefr, card.rule(), card.ruleRu(),
                    card.exampleOk(), card.exampleBad(), card.l1Trap());
        }
    }

    public record ReviewQueue(int due, java.util.List<ReviewCard> cards) {
    }

    public record RateRequest(int rating) {
    }

    public record RateResult(String slug, Instant due, long intervalDays, int remaining) {
    }

    public record ErrorView(String error) {
    }
}
