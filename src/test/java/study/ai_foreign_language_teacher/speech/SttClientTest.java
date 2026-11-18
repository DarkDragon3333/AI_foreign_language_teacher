package study.ai_foreign_language_teacher.speech;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SttClientTest {

    @Test
    void dropsCommasWhisperInsertsOnPauses() {
        assertEquals("My Gym Near My House.", SttClient.withoutDictatedCommas("My Gym, Near, My House."));
    }
}
