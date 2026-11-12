package study.ai_foreign_language_teacher.domain;

public record UserProfile(
        long id,
        String displayName,
        String nativeLang,
        String targetLang,
        Cefr cefr,
        boolean voiceEnabled) {
}
