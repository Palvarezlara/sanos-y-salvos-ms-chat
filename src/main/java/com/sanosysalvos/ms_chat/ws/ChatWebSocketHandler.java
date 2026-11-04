package com.sanosysalvos.ms_chat.ws;

import java.time.Clock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
import com.sanosysalvos.ms_chat.service.AvisoCorreoService;
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
    /* codigo propio (4000-4999): el frontend lo usa para pedir un ticket nuevo y reconectar */
    static final int CODIGO_SESION_EXPIRADA = 4001;

    private final ChatService chatService;
    private final AvisoCorreoService avisoCorreo;
    private final ChatProperties props;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    private final Map<String, Set<WebSocketSession>> sesionesPorUsuario = new ConcurrentHashMap<>();
    private final Map<String, LimitadorVentana> limitadores = new ConcurrentHashMap<>();
    /* momento de apertura y de la ultima actividad de cada sesion, para cerrar las vencidas */
    private final Map<String, Instant> abiertas = new ConcurrentHashMap<>();
    private final Map<String, Instant> ultimaActividad = new ConcurrentHashMap<>();
    private final Duration vidaMaxima;
    private final Duration inactividadMaxima;

    public ChatWebSocketHandler(ChatService chatService, AvisoCorreoService avisoCorreo, ChatProperties props,
            Clock clock, @Value("${chat.ws-vida-maxima-minutos:30}") long vidaMaximaMinutos,
            @Value("${chat.ws-inactividad-segundos:120}") long inactividadSegundos) {
        this.vidaMaxima = Duration.ofMinutes(vidaMaximaMinutos);
        this.inactividadMaxima = Duration.ofSeconds(inactividadSegundos);
        this.chatService = chatService;
        this.avisoCorreo = avisoCorreo;
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

        /* compute es atómico respecto de afterConnectionClosed: antes, cerrar la última pestaña borraba el conjunto
           justo cuando una pestaña nueva lo había obtenido, y esa quedaba fuera del registro sin recibir nada */
        boolean[] aceptada = { true };
        sesionesPorUsuario.compute(sub, (k, existente) -> {
            Set<WebSocketSession> conjunto = existente != null ? existente : ConcurrentHashMap.newKeySet();
            if (conjunto.size() >= MAX_SESIONES_POR_USUARIO) {
                aceptada[0] = false;
                return existente;
            }
            conjunto.add(segura);
            return conjunto;
        });
        if (!aceptada[0]) {
            sesion.close(CloseStatus.POLICY_VIOLATION.withReason("Demasiadas conexiones abiertas"));
            return;
        }
        Instant ahora = clock.instant();
        abiertas.put(sesion.getId(), ahora);
        ultimaActividad.put(sesion.getId(), ahora);
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
        ultimaActividad.put(sesion.getId(), clock.instant());
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
            /* si la otra persona no está conectada, se le avisa por correo (sin el texto del mensaje) */
            avisoCorreo.avisarSiCorresponde(resultado.conversacion(), sub, this::estaConectado);
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
        abiertas.remove(sesion.getId());
        ultimaActividad.remove(sesion.getId());
        Object sub = sesion.getAttributes().get(TicketHandshakeInterceptor.ATRIBUTO_SUB);
        if (sub == null) {
            return;
        }
        sesionesPorUsuario.computeIfPresent(sub.toString(), (k, conjunto) -> {
            conjunto.removeIf(s -> s.getId().equals(sesion.getId()));
            return conjunto.isEmpty() ? null : conjunto;
        });
    }

    /**
     * El ticket autoriza solo el apretón de manos: sin esto, tras un logout o una cuenta deshabilitada el socket
     * abierto seguía enviando y recibiendo. Se cierra a los chat.ws-vida-maxima-minutos (el cliente pide un
     * ticket nuevo y reconecta) y si no llega ningún marco, ni ping, en chat.ws-inactividad-segundos.
     */
    @Scheduled(fixedDelay = 30_000)
    void cerrarSesionesVencidas() {
        Instant ahora = clock.instant();
        sesionesPorUsuario.values().forEach(conjunto -> conjunto.forEach(sesion -> {
            Instant abierta = abiertas.get(sesion.getId());
            Instant actividad = ultimaActividad.get(sesion.getId());
            boolean vencida = abierta != null && abierta.plus(vidaMaxima).isBefore(ahora);
            boolean inactiva = actividad != null && actividad.plus(inactividadMaxima).isBefore(ahora);
            if (vencida || inactiva) {
                try {
                    sesion.close(new CloseStatus(CODIGO_SESION_EXPIRADA, "Sesion expirada, vuelve a conectar"));
                } catch (IOException e) {
                    log.debug("No se pudo cerrar la sesion {}: {}", sesion.getId(), e.getClass().getSimpleName());
                }
            }
        }));
    }

    @Override
    public void handleTransportError(WebSocketSession sesion, Throwable error) {
        log.debug("Error de transporte en la sesion {}: {}", sesion.getId(), error.getClass().getSimpleName());
    }

    private boolean estaConectado(String sub) {
        Set<WebSocketSession> conjunto = sesionesPorUsuario.get(sub);
        return conjunto != null && !conjunto.isEmpty();
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
