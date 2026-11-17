package study.ai_foreign_language_teacher.speech;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingVadTest {

    @Test
    void silenceAfterSpeechEndsUtterance() {
        StreamingVad vad = new StreamingVad(16000, 320, Duration.ofMillis(200), Duration.ofMillis(100),
                Duration.ofSeconds(30), 200);
        short[] loud = loudFrame(800);
        short[] quiet = loudFrame(50);
        assertEquals(StreamingVad.Signal.SPEECH_START, vad.feed(loud));
        for (int i = 0; i < 8; i++) {
            vad.feed(loud);
        }
        StreamingVad.Signal end = StreamingVad.Signal.NONE;
        for (int i = 0; i < 30; i++) {
            end = vad.feed(quiet);
            if (end == StreamingVad.Signal.SPEECH_END) {
                break;
            }
        }
        assertEquals(StreamingVad.Signal.SPEECH_END, end);
    }

    private static short[] loudFrame(int amp) {
        short[] frame = new short[320];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (short) (amp * Math.sin(i / 12.0));
        }
        return frame;
    }
}