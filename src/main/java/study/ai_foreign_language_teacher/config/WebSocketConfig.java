package study.ai_foreign_language_teacher.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import study.ai_foreign_language_teacher.api.VoiceWebSocketHandler;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceWebSocketHandler voice;

    public WebSocketConfig(VoiceWebSocketHandler voice) {
        this.voice = voice;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voice, "/ws/voice").setAllowedOrigins("*");
    }
}