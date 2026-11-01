package com.sanosysalvos.ms_chat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.sanosysalvos.ms_chat.ws.ChatWebSocketHandler;
import com.sanosysalvos.ms_chat.ws.TicketHandshakeInterceptor;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final TicketHandshakeInterceptor interceptor;
    private final ChatProperties props;

    public WebSocketConfig(ChatWebSocketHandler handler, TicketHandshakeInterceptor interceptor, ChatProperties props) {
        this.handler = handler;
        this.interceptor = interceptor;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat")
                .addInterceptors(interceptor)
                /* solo los orígenes del frontend; el ticket en la URL ya hace inviable un cross-site hijacking, esto es defensa en profundidad */
                .setAllowedOriginPatterns(props.allowedOrigins().toArray(String[]::new));
    }
}
