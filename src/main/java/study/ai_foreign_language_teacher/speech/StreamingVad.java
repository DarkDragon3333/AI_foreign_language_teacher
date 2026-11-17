package study.ai_foreign_language_teacher.speech;

import java.time.Duration;

/**
 * Простой энергетический VAD на 16 kHz PCM (mono int16).
 * Для одинакового поведения на всех клиентах конец фразы режет сервер; браузер лишь не шлёт явную тишину при желании.
 */
public final class StreamingVad {

    public enum Signal {
        NONE,
        SPEECH_START,
        SPEECH_END
    }

    private final int rmsThreshold;
    private final int silenceFrames;
    private final int minSpeechFrames;
    private final int maxFrames;

    private boolean inSpeech;
    private int speechFrames;
    private int silentFrames;
    private int totalFrames;

    public StreamingVad(int sampleRate, int frameSamples, Duration silence, Duration minSpeech,
                        Duration maxUtterance, int rmsThreshold) {
        this.rmsThreshold = rmsThreshold;
        this.silenceFrames = frames(sampleRate, frameSamples, silence);
        this.minSpeechFrames = frames(sampleRate, frameSamples, minSpeech);
        this.maxFrames = frames(sampleRate, frameSamples, maxUtterance);
    }

    private static int frames(int sampleRate, int frameSamples, Duration duration) {
        long frameMs = Math.max(1L, frameSamples * 1000L / sampleRate);
        return (int) Math.max(1, duration.toMillis() / frameMs);
    }

  /** @return сигнал по смене речи/тишины; speech — сейчас в фразе */
    public Signal feed(short[] frame) {
        if (frame == null || frame.length == 0) {
            return Signal.NONE;
        }
        totalFrames++;
        boolean voiced = rms(frame) >= rmsThreshold;
        if (!inSpeech) {
            if (!voiced) {
                return Signal.NONE;
            }
            inSpeech = true;
            speechFrames = 1;
            silentFrames = 0;
            return Signal.SPEECH_START;
        }
        if (voiced) {
            speechFrames++;
            silentFrames = 0;
        } else {
            silentFrames++;
        }
        if (totalFrames >= maxFrames || (silentFrames >= silenceFrames && speechFrames >= minSpeechFrames)) {
            inSpeech = false;
            speechFrames = 0;
            silentFrames = 0;
            totalFrames = 0;
            return Signal.SPEECH_END;
        }
        return Signal.NONE;
    }

    public boolean speaking() {
        return inSpeech;
    }

    public void reset() {
        inSpeech = false;
        speechFrames = 0;
        silentFrames = 0;
        totalFrames = 0;
    }

    public static int rms(short[] frame) {
        long sum = 0;
        for (short sample : frame) {
            sum += (long) sample * sample;
        }
        return (int) Math.sqrt((double) sum / frame.length);
    }
}