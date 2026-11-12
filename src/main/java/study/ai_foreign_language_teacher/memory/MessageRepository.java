package study.ai_foreign_language_teacher.memory;

import study.ai_foreign_language_teacher.domain.ChatMessage;
import study.ai_foreign_language_teacher.domain.Role;

import java.util.List;

public interface MessageRepository {

    ChatMessage add(long sessionId, Role role, String text, String payloadJson);

    /** Последние n реплик в хронологическом порядке. */
    List<ChatMessage> lastN(long sessionId, int n);

    List<ChatMessage> all(long sessionId);
}
