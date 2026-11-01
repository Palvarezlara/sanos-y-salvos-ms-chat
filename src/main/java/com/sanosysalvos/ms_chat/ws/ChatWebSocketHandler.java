package com.sanosysalvos.ms_chat.ws;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.sanosysalvos.ms_chat.config.ChatProperties;
import com.sanosysalvos.ms_chat.dto.MensajeDto;
import com.sanosysalvos.ms_chat.exception.RecursoNoEncontradoException;
import com.sanosysalvos.ms_chat.exception.SolicitudInvalidaException;
import com.sanosysalvos.ms_chat.service.ChatService;
import com.sanosysalvos.ms_chat.service.ChatService.ResultadoEnvio;

import tools.jackson.databind.json.JsonMapper;

/**
 * Protocolo (JSON de texto):
 *   cliente -> { "tipo": "mensaje", "conversacionId": "...", "texto": "..." }
 *   cliente -> { "tipo": "ping" }
 *   servidor -> { "tipo": "mensaje", "mensaje": { ...MensajeDto } }   (a los dos participantes)
 *   servidor -> { "tipo": "error", "codigo": "...", "mensaje": "..." }
 *   servidor -> { "tipo": "pong" }
 *
 * El registro de sesiones vive en memoria: con más de una instancia de ms-chat un
 * mensaje solo llegaría a quienes estén conectados a la misma instancia. Para
 * escalar horizontalmente haría falta un bus (SNS/SQS o Kafka) que reparta los
 * mensajes entre instancias.
 *
 * Nunca se registra el contenido de los mensajes ni el ticket.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final int MAX_SESIONES_POR_USUARIO = 5;
    private static final int MAX_BYTES_ENTRANTES = 8 * 1024;
    private static final String ATRIBUTO_SEGURA = "segura";

    private final ChatService chatService;
    private final ChatProperties props;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    private final Map<String, Set<WebSocketSession>> sesionesPorUsuario = new ConcurrentHashMap<>();
    private final Map<String, LimitadorVentana> limitadores = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatService chatService, ChatProperties props, Clock clock) {
        this.chatService = chatService;
        this.props = props;
        this.clock = clock;
    }

    /** Datos de una entrada del cliente. */
    record Entrante(String tipo, String conversacionId, String texto) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession sesion) throws Exception {
        String sub = subDe(sesion);
        /* varios hilos pueden escribir a la misma sesión (el del emisor y el de quien le responde) */
        WebSocketSession segura = new ConcurrentWebSocketSessionDecorator(sesion, 5_000, 64 * 1024);

        Set<WebSocketSession> conjunto = sesionesPorUsuario.computeIfAbsent(sub, k -> ConcurrentHashMap.newKeySet());
        if (conjunto.size() >= MAX_SESIONES_POR_USUARIO) {
            sesion.close(CloseStatus.POLICY_VIOLATION.withReason("Demasiadas conexiones abiertas"));
            return;
        }
        conjunto.add(segura);
        sesion.getAttributes().put(ATRIBUTO_SEGURA, segura);
        limitadores.put(sesion.getId(),
                new LimitadorVentana(props.maxMensajesPorVentana(), Duration.ofSeconds(props.ventanaSegundos()), clock));
    }

    @Override
    protected void handleTextMessage(WebSocketSession sesion, TextMessage mensaje) {
        WebSocketSession segura = seguraDe(sesion);
        if (segura == null) {
            return;
        }
        if (mensaje.getPayloadLength() > MAX_BYTES_ENTRANTES) {
            enviarError(segura, "mensaje_muy_grande", "El mensaje es demasiado grande");
            return;
        }

        LimitadorVentana limitador = limitadores.get(sesion.getId());
        if (limitador != null && !limitador.permitir()) {
            enviarError(segura, "demasiados_mensajes", "Estás enviando mensajes muy rápido, espera unos segundos");
            return;
        }

        Entrante entrante;
        try {
            entrante = json.readValue(mensaje.getPayload(), Entrante.class);
        } catch (RuntimeException e) {
            enviarError(segura, "formato_invalido", "El mensaje tiene un formato inválido");
            return;
        }
        if (entrante == null || entrante.tipo() == null) {
            enviarError(segura, "formato_invalido", "El mensaje tiene un formato inválido");
            return;
        }

        switch (entrante.tipo()) {
            case "ping" -> enviar(segura, Map.of("tipo", "pong"));
            case "mensaje" -> procesarMensaje(subDe(sesion), segura, entrante);
            default -> enviarError(segura, "tipo_desconocido", "Tipo de mensaje no soportado");
        }
    }

    private void procesarMensaje(String sub, WebSocketSession segura, Entrante entrante) {
        try {
            ResultadoEnvio resultado = chatService.enviarMensaje(sub, entrante.conversacionId(), entrante.texto());
            /* "mio" depende de quién lo recibe, por eso se arma una vez por participante */
            for (String participante : resultado.participantes()) {
                MensajeDto dto = chatService.aDto(resultado.mensaje(), participante);
                enviarAUsuario(participante, Map.of("tipo", "mensaje", "mensaje", dto));
            }
        } catch (SolicitudInvalidaException e) {
            enviarError(segura, "solicitud_invalida", e.getMessage());
        } catch (RecursoNoEncontradoException e) {
            enviarError(segura, "no_encontrada", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Error guardando un mensaje de chat", e);
            enviarError(segura, "error_interno", "No se pudo enviar el mensaje, intenta de nuevo");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession sesion, CloseStatus estado) {
        limitadores.remove(sesion.getId());
        Object sub = sesion.getAttributes().get(TicketHandshakeInterceptor.ATRIBUTO_SUB);
        if (sub == null) {
            return;
        }
        Set<WebSocketSession> conjunto = sesionesPorUsuario.get(sub.toString());
        if (conjunto != null) {
            conjunto.removeIf(s -> s.getId().equals(sesion.getId()));
            if (conjunto.isEmpty()) {
                sesionesPorUsuario.remove(sub.toString(), conjunto);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession sesion, Throwable error) {
        log.debug("Error de transporte en la sesion {}: {}", sesion.getId(), error.getClass().getSimpleName());
    }

    private void enviarAUsuario(String sub, Object marco) {
        Set<WebSocketSession> conjunto = sesionesPorUsuario.get(sub);
        if (conjunto != null) {
            conjunto.forEach(s -> enviar(s, marco));
        }
    }

    private void enviarError(WebSocketSession sesion, String codigo, String mensaje) {
        enviar(sesion, Map.of("tipo", "error", "codigo", codigo, "mensaje", mensaje));
    }

    private void enviar(WebSocketSession sesion, Object marco) {
        try {
            sesion.sendMessage(new TextMessage(json.writeValueAsString(marco)));
        } catch (Exception e) {
            log.debug("No se pudo enviar a la sesion {}: {}", sesion.getId(), e.getClass().getSimpleName());
        }
    }

    private String subDe(WebSocketSession sesion) {
        return (String) sesion.getAttributes().get(TicketHandshakeInterceptor.ATRIBUTO_SUB);
    }

    private WebSocketSession seguraDe(WebSocketSession sesion) {
        return (WebSocketSession) sesion.getAttributes().get(ATRIBUTO_SEGURA);
    }
}
