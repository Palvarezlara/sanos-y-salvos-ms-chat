package com.sanosysalvos.ms_chat.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sanosysalvos.ms_chat.dto.ConversacionDto;
import com.sanosysalvos.ms_chat.dto.IniciarConversacionRequest;
import com.sanosysalvos.ms_chat.dto.MensajeDto;
import com.sanosysalvos.ms_chat.service.ChatService;

import jakarta.validation.Valid;

/**
 * API REST del chat (historial y conversaciones). El envío en tiempo real va por
 * WebSocket (ver ChatWebSocketHandler). Todo se autoriza con el JWT de Cognito.
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/conversaciones")
    public ResponseEntity<ConversacionDto> iniciar(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody IniciarConversacionRequest solicitud) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(chatService.iniciarConversacion(jwt.getSubject(), solicitud.mascotaId()));
    }

    @GetMapping("/conversaciones")
    public List<ConversacionDto> listar(@AuthenticationPrincipal Jwt jwt) {
        return chatService.listar(jwt.getSubject());
    }

    @GetMapping("/conversaciones/{id}/mensajes")
    public List<MensajeDto> mensajes(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant antes,
            @RequestParam(defaultValue = "50") int limite) {
        return chatService.listarMensajes(jwt.getSubject(), id, antes, limite);
    }
}
