package study.ai_foreign_language_teacher.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import study.ai_foreign_language_teacher.api.Dto.MessageView;
import study.ai_foreign_language_teacher.api.Dto.SessionView;
import study.ai_foreign_language_teacher.config.TutorProperties;
import study.ai_foreign_language_teacher.domain.NotFoundException;
import study.ai_foreign_language_teacher.llm.LlmException;
import study.ai_foreign_language_teacher.session.LessonService;
import study.ai_foreign_language_teacher.speech.SpeakableSentences;
import study.ai_foreign_language_teacher.speech.StreamingVad;
import study.ai_foreign_language_teacher.speech.SttClient;
import study.ai_foreign_language_teacher.speech.SttNameHints;
import study.ai_foreign_language_teacher.speech.TtsClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Дуплексный голос: PCM с клиента, VAD и ход урока на сервере, WAV обратно с перебиванием. */
@Component
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketHandler.class);

    private final LessonService lessons;
    private final SttClient stt;
    private final TtsClient tts;
    private final TutorProperties props;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, VoiceLink> links = new ConcurrentHashMap<>();

    public VoiceWebSocketHandler(LessonService lessons, SttClient stt, TtsClient tts,
                                 TutorProperties props, ObjectMapper mapper) {
        this.lessons = lessons;
        this.stt = stt;
        this.tts = tts;
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        VoiceLink link = links.remove(session.getId());
        if (link != null) {
            link.cancelSpeaking();
            link.busy.set(false);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = text(node, "type");
        VoiceLink link = links.get(session.getId());
        if ("hello".equals(type)) {
            if (!stt.enabled() || !tts.enabled()) {
                send(session, Map.of("type", "error", "message", "Голосовой режим недоступен: распознавание или озвучка выключены"));
                session.close(new CloseStatus(1013, "voice unavailable"));
                return;
            }
            long sessionId = node.path("sessionId").asLong(0);
            if (sessionId <= 0) {
                send(session, Map.of("type", "error", "message", "Нужен sessionId"));
                return;
            }
            try {
                lessons.getSession(sessionId);
            } catch (NotFoundException e) {
                send(session, Map.of("type", "error", "message", e.getMessage()));
                return;
            }
            TutorProperties.Voice voice = props.voice();
            StreamingVad vad = new StreamingVad(voice.sampleRate(), voice.frameSamples(), voice.silence(),
                    voice.minSpeech(), voice.maxUtterance(), voice.rmsThreshold());
            String mode = text(node, "mode");
            boolean ptt = "ptt".equalsIgnoreCase(mode);
            link = new VoiceLink(session, sessionId, vad, voice, ptt);
            links.put(session.getId(), link);
            link.send(Map.of("type", "ready", "mode", ptt ? "ptt" : "handsfree",
                    "sampleRate", voice.sampleRate()));
            link.setPhase(Phase.LISTEN);
            return;
        }
        if (link == null) {
            send(session, Map.of("type", "error", "message", "Сначала hello с sessionId"));
            return;
        }
        switch (type) {
            case "begin" -> link.runTurn("", false);
            case "ptt_start" -> link.pttActive = true;
            case "ptt_end" -> {
                link.pttActive = false;
                link.finishUtterance();
            }
            case "stop" -> session.close(CloseStatus.NORMAL);
            default -> send(session, Map.of("type", "error", "message", "Неизвестная команда: " + type));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        VoiceLink link = links.get(session.getId());
        if (link == null) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        if (payload.remaining() < 2) {
            return;
        }
        short[] frame = new short[payload.remaining() / 2];
        payload.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(frame);
        link.onPcm(frame);
    }

    private void send(WebSocketSession session, Object body) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(body)));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString("").trim();
    }

    enum Phase {
        LISTEN, THINK, SPEAK
    }

    final class VoiceLink {
        private final WebSocketSession session;
        private final long lessonSessionId;
        private final StreamingVad vad;
        private final TutorProperties.Voice cfg;
        private final boolean pttMode;
        private final AtomicBoolean busy = new AtomicBoolean(false);
        private final AtomicBoolean cancelSpeech = new AtomicBoolean(false);
        private final AtomicInteger speakGeneration = new AtomicInteger();
        private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        private final short[] frameScratch;
        private int frameFill;

        private Phase phase = Phase.LISTEN;
        private boolean pttActive;
        private int bargeInFrames;
        private boolean speaking;

        VoiceLink(WebSocketSession session, long lessonSessionId, StreamingVad vad, TutorProperties.Voice cfg,
                  boolean pttMode) {
            this.session = session;
            this.lessonSessionId = lessonSessionId;
            this.vad = vad;
            this.cfg = cfg;
            this.pttMode = pttMode;
            this.frameScratch = new short[cfg.frameSamples()];
        }

        void setPhase(Phase next) {
            phase = next;
            send(Map.of("type", "state", "phase", next.name().toLowerCase()));
        }

        void onPcm(short[] chunk) {
            if (phase == Phase.SPEAK) {
                watchBargeIn(chunk);
            }
            if (phase != Phase.LISTEN) {
                return;
            }
            if (pttMode && !pttActive) {
                return;
            }
            for (short sample : chunk) {
                frameScratch[frameFill++] = sample;
                if (frameFill < frameScratch.length) {
                    continue;
                }
                frameFill = 0;
                StreamingVad.Signal signal = vad.feed(frameScratch);
                if (signal == StreamingVad.Signal.SPEECH_START) {
                    pcm.reset();
                }
                if (vad.speaking() || signal == StreamingVad.Signal.SPEECH_START) {
                    append(frameScratch);
                }
                if (signal == StreamingVad.Signal.SPEECH_END && !pttMode) {
                    finishUtterance();
                }
            }
        }

        private void watchBargeIn(short[] chunk) {
            if (phase != Phase.SPEAK) {
                return;
            }
            int bargeFrames = (int) Math.max(1, cfg.bargeInHold().toMillis() * cfg.sampleRate()
                    / (cfg.frameSamples() * 1000L));
            int rms = StreamingVad.rms(chunk);
            if (rms >= cfg.bargeInThreshold()) {
                bargeInFrames++;
            } else {
                bargeInFrames = 0;
            }
            if (bargeInFrames >= bargeFrames) {
                cancelSpeaking();
                vad.reset();
                pcm.reset();
                frameFill = 0;
            }
        }

        private void append(short[] frame) {
            ByteBuffer buf = ByteBuffer.allocate(frame.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short sample : frame) {
                buf.putShort(sample);
            }
            try {
                pcm.write(buf.array());
            } catch (IOException e) {
                throw new IllegalStateException("PCM buffer failed", e);
            }
        }

        void finishUtterance() {
            byte[] audio = pcm.toByteArray();
            pcm.reset();
            vad.reset();
            frameFill = 0;
            if (audio.length < cfg.sampleRate() / 5 || busy.get()) {
                return;
            }
            executor.submit(() -> runWithLock(() -> {
                setPhase(Phase.THINK);
                cancelSpeech.set(false);
                LessonService.SpeechContext ctx = lessons.speechContext(lessonSessionId);
                String text = SttNameHints.apply(
                        stt.transcribePcm16(audio, cfg.sampleRate(), ctx.prompt()).strip(),
                        ctx.names());
                if (text.isBlank()) {
                    setPhase(Phase.LISTEN);
                    return;
                }
                send(Map.of("type", "transcript", "text", text));
                runTurnContent(text, false);
            }));
        }

        void runTurn(String text, boolean hint) {
            executor.submit(() -> runWithLock(() -> runTurnContent(text, hint)));
        }

        private void runWithLock(Runnable task) {
            if (!busy.compareAndSet(false, true)) {
                return;
            }
            try {
                task.run();
            } finally {
                busy.set(false);
            }
        }

        private void runTurnContent(String text, boolean hint) {
            try {
                setPhase(Phase.THINK);
                cancelSpeech.set(false);
                speaking = false;
                int gen = speakGeneration.incrementAndGet();
                SpeakableSentences sentences = new SpeakableSentences(sentence -> speakSentence(gen, sentence));
                LessonService.Turn turn = lessons.turn(lessonSessionId, text, hint, delta -> {
                    sendQuiet(Map.of("type", "delta", "text", delta));
                    sentences.accept(delta);
                });
                sentences.flush();
                if (turn.reply() != null && turn.reply().nextPrompt() != null && !turn.reply().nextPrompt().isBlank()) {
                    speakSentence(gen, turn.reply().nextPrompt().strip());
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("type", "final");
                body.put("session", SessionView.of(turn.session(),
                        LessonService.SessionExtras.of(turn.session(), lessons)));
                body.put("user", turn.userMessage() == null ? null : MessageView.of(turn.userMessage(), null));
                body.put("teacher", MessageView.of(turn.teacherMessage(), turn.reply()));
                if (turn.scenario() != null) {
                    body.put("scenario", Dto.ScenarioProgressView.of(turn.scenario()));
                }
                sendQuiet(body);
                if (cancelSpeech.get()) {
                    setPhase(Phase.LISTEN);
                } else {
                    setPhase(Phase.LISTEN);
                }
            } catch (IllegalArgumentException | NotFoundException | LlmException e) {
                sendQuiet(Map.of("type", "error", "message", e.getMessage()));
                setPhase(Phase.LISTEN);
            } catch (RuntimeException e) {
                log.error("Voice turn failed", e);
                sendQuiet(Map.of("type", "error", "message", "Внутренняя ошибка, смотрите лог сервера"));
                setPhase(Phase.LISTEN);
            }
        }

        private void speakSentence(int gen, String sentence) {
            if (cancelSpeech.get() || gen != speakGeneration.get()) {
                return;
            }
            try {
                if (!speaking) {
                    speaking = true;
                    setPhase(Phase.SPEAK);
                }
                byte[] wav = tts.speak(sentence);
                if (cancelSpeech.get() || gen != speakGeneration.get()) {
                    return;
                }
                sendQuiet(Map.of("type", "audio"));
                session.sendMessage(new BinaryMessage(wav));
            } catch (IOException e) {
                log.warn("Voice WS send failed: {}", e.toString());
            } catch (RuntimeException e) {
                sendQuiet(Map.of("type", "error", "message", e.getMessage()));
            }
        }

        void cancelSpeaking() {
            cancelSpeech.set(true);
            speakGeneration.incrementAndGet();
            bargeInFrames = 0;
            sendQuiet(Map.of("type", "cancel_audio"));
            if (phase == Phase.SPEAK) {
                setPhase(Phase.LISTEN);
            }
        }

        void send(Map<String, Object> body) {
            sendQuiet(body);
        }

        private void sendQuiet(Map<String, Object> body) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(body)));
                }
            } catch (IOException e) {
                log.debug("WS closed while sending: {}", e.toString());
            }
        }
    }
}