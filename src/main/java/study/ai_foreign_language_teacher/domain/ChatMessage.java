package study.ai_foreign_language_teacher.domain;

import java.time.Instant;

/**
 * Реплика урока. Для учителя в payloadJson лежит полный JSON-ответ по контракту,
 * а в text — то, что видит ученик (reply_en + next_prompt).
 */
public record ChatMessage(
        long id,
        long sessionId,
        Role role,
        String text,
        String payloadJson,
        Instant createdAt) {
}
