package com.sanosysalvos.ms_chat.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sanosysalvos.ms_chat.config.TokenInterno;
import com.sanosysalvos.ms_chat.dto.ConversacionDto;
import com.sanosysalvos.ms_chat.dto.IniciarConversacionRequest;
import com.sanosysalvos.ms_chat.dto.MensajeDto;
import com.sanosysalvos.ms_chat.service.ChatService;
import com.sanosysalvos.ms_chat.service.LimitadorUso;

import jakarta.validation.Valid;

/**
 * API REST del chat (historial y conversaciones). El envío en tiempo real va por
 * WebSocket (ver ChatWebSocketHandler). Todo se autoriza con el JWT de Cognito.
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final TokenInterno tokenInterno;
    private final LimitadorUso limiteRest;

    public ChatController(ChatService chatService, TokenInterno tokenInterno,
            @Qualifier("limiteRest") LimitadorUso limiteRest) {
        this.chatService = chatService;
        this.tokenInterno = tokenInterno;
        this.limiteRest = limiteRest;
    }

    @PostMapping("/conversaciones")
    public ResponseEntity<ConversacionDto> iniciar(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @Valid @RequestBody IniciarConversacionRequest solicitud) {
        limiteRest.registrar(jwt.getSubject());
        /* X-User-Email lo pone el BFF a partir del id_token ya validado, junto con el secreto de servicio. Sin ese
         * secreto se ignora: llamando directo a este servicio cualquiera podría poner el correo de otra persona
         * y hacer que la cuenta oficial le escriba cada vez que le respondan. */
        String correoConfiable = tokenInterno.valido(token) ? email : null;
        return ResponseEntity.status(HttpStatus.OK)
                .body(chatService.iniciarConversacion(jwt.getSubject(), solicitud.mascotaId(), correoConfiable));
    }

    @GetMapping("/conversaciones")
    public List<ConversacionDto> listar(@AuthenticationPrincipal Jwt jwt) {
        limiteRest.registrar(jwt.getSubject());
        return chatService.listar(jwt.getSubject());
    }

    @GetMapping("/conversaciones/{id}/mensajes")
    public List<MensajeDto> mensajes(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant antes,
            @RequestParam(defaultValue = "50") int limite) {
        limiteRest.registrar(jwt.getSubject());
        return chatService.listarMensajes(jwt.getSubject(), id, antes, limite);
    }

    /**
     * Cualquiera de los dos participantes puede borrar la conversación (mensajes y correos guardados).
     * Con bloquear=true queda una marca sin datos que impide que la otra persona vuelva a escribir.
     */
    @DeleteMapping("/conversaciones/{id}")
    public ResponseEntity<Void> eliminar(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(defaultValue = "false") boolean bloquear) {
        limiteRest.registrar(jwt.getSubject());
        chatService.eliminarConversacion(jwt.getSubject(), id, bloquear);
        return ResponseEntity.noContent().build();
    }
}
