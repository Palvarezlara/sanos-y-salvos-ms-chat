package com.sanosysalvos.ms_chat.ws;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** Rechaza el handshake (401) si el ticket de la URL no es válido; si lo es, deja el sub en la sesión. */
@Component
public class TicketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATRIBUTO_SUB = "sub";

    private final TicketValidator validator;

    public TicketHandshakeInterceptor(TicketValidator validator) {
        this.validator = validator;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
        Optional<String> sub = validator.validar(ticket);
        if (sub.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ATRIBUTO_SUB, sub.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) {
    }
}
