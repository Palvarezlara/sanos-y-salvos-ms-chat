package com.sanosysalvos.ms_chat.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.sanosysalvos.ms_chat.client.MascotasClient;
import com.sanosysalvos.ms_chat.config.ChatProperties;
import com.sanosysalvos.ms_chat.dto.ConversacionDto;
import com.sanosysalvos.ms_chat.dto.DuenoMascotaDto;
import com.sanosysalvos.ms_chat.dto.MensajeDto;
import com.sanosysalvos.ms_chat.exception.RecursoNoEncontradoException;
import com.sanosysalvos.ms_chat.exception.SolicitudInvalidaException;
import com.sanosysalvos.ms_chat.model.Conversacion;
import com.sanosysalvos.ms_chat.model.Mensaje;
import com.sanosysalvos.ms_chat.repository.ConversacionRepository;
import com.sanosysalvos.ms_chat.repository.MensajeRepository;

@Service
public class ChatService {

    private static final int LIMITE_MAXIMO = 100;

    private final ConversacionRepository conversaciones;
    private final MensajeRepository mensajes;
    private final MascotasClient mascotas;
    private final ChatProperties props;
    private final Clock clock;
    private final LimitadorUso limiteConversaciones;

    public ChatService(ConversacionRepository conversaciones, MensajeRepository mensajes,
            MascotasClient mascotas, ChatProperties props, Clock clock,
            @Qualifier("limiteConversaciones") LimitadorUso limiteConversaciones) {
        this.conversaciones = conversaciones;
        this.mensajes = mensajes;
        this.mascotas = mascotas;
        this.props = props;
        this.clock = clock;
        this.limiteConversaciones = limiteConversaciones;
    }

    /** Resultado de guardar un mensaje: a quiénes hay que entregárselo. */
    public record ResultadoEnvio(Mensaje mensaje, List<String> participantes, Conversacion conversacion) {
    }

    public ConversacionDto iniciarConversacion(String sub, String mascotaId) {
        return iniciarConversacion(sub, mascotaId, null);
    }

    /** emailInteresado viene del id_token validado por el BFF; solo se usa para avisarle de respuestas. */
    public ConversacionDto iniciarConversacion(String sub, String mascotaId, String emailInteresado) {
        DuenoMascotaDto dueno = mascotas.obtenerDueno(mascotaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Reporte no encontrado"));
        if (dueno.duenoSub() == null || dueno.duenoSub().isBlank()) {
            throw new SolicitudInvalidaException("Este reporte no admite chat");
        }
        if (dueno.duenoSub().equals(sub)) {
            throw new SolicitudInvalidaException("No puedes iniciar un chat contigo mismo");
        }

        String clave = Conversacion.claveDe(mascotaId, dueno.duenoSub(), sub);
        Conversacion conversacion = conversaciones.findByClave(clave).orElse(null);
        if (conversacion != null && conversacion.estaBloqueada()) {
            throw new SolicitudInvalidaException("No se puede iniciar este chat");
        }
        if (conversacion == null) {
            /* solo cuenta crear una conversación nueva, no volver a abrir una existente */
            limiteConversaciones.registrar(sub);
            conversacion = crear(clave, dueno, sub);
        }
        if (conversacion.registrarCorreos(correoValido(dueno.duenoEmail()), correoValido(emailInteresado))) {
            conversaciones.save(conversacion);
        }
        return aDto(conversacion, sub);
    }

    public List<ConversacionDto> listar(String sub) {
        return conversaciones.buscarDelUsuario(sub).stream().map(c -> aDto(c, sub)).toList();
    }

    public List<MensajeDto> listarMensajes(String sub, String conversacionId, Instant antes, int limite) {
        Conversacion conversacion = buscarParaParticipante(sub, conversacionId);
        int tope = Math.max(1, Math.min(limite, LIMITE_MAXIMO));
        Instant hasta = antes != null ? antes : clock.instant().plusSeconds(60);

        List<Mensaje> pagina = new ArrayList<>(mensajes.findByConversacionIdAndEnviadoEnBefore(
                conversacion.getId(), hasta, PageRequest.of(0, tope, Sort.by(Sort.Direction.DESC, "enviadoEn"))));
        Collections.reverse(pagina);
        return pagina.stream().map(m -> aDto(m, sub)).toList();
    }

    public ResultadoEnvio enviarMensaje(String sub, String conversacionId, String texto) {
        Conversacion conversacion = buscarParaParticipante(sub, conversacionId);

        String limpio = texto == null ? "" : texto.strip();
        if (limpio.isEmpty()) {
            throw new SolicitudInvalidaException("El mensaje no puede estar vacío");
        }
        if (limpio.length() > props.maxLargoMensaje()) {
            throw new SolicitudInvalidaException(
                    "El mensaje no puede superar los " + props.maxLargoMensaje() + " caracteres");
        }

        Instant ahora = clock.instant();
        Mensaje guardado = mensajes.save(new Mensaje(conversacion.getId(), sub, limpio, ahora));
        /* atómico ($max): guardar el documento entero pisaba los cambios de otro mensaje simultáneo */
        conversaciones.registrarUltimoMensaje(conversacion.getId(), ahora);
        conversacion.registrarMensaje(ahora);
        return new ResultadoEnvio(guardado, conversacion.getParticipantes(), conversacion);
    }

    /**
     * Borra la conversación (mensajes y correos guardados). Con bloquear=true deja una marca sin datos que impide
     * que la otra persona vuelva a abrirla o a escribir. Quien no participa recibe "no encontrada".
     */
    public void eliminarConversacion(String sub, String conversacionId, boolean bloquear) {
        Conversacion conversacion = buscarParaParticipante(sub, conversacionId);
        mensajes.deleteByConversacionId(conversacion.getId());
        if (bloquear) {
            conversacion.bloquear(sub);
            conversaciones.save(conversacion);
        } else {
            conversaciones.deleteById(conversacion.getId());
        }
    }

    /** Borra todo lo de un reporte eliminado en ms-mascotas: no quedan conversaciones ni correos huérfanos. */
    public void eliminarPorMascota(String mascotaId) {
        for (Conversacion conversacion : conversaciones.findByMascotaId(mascotaId)) {
            mensajes.deleteByConversacionId(conversacion.getId());
            conversaciones.deleteById(conversacion.getId());
        }
    }

    /* null si no parece un correo: evita guardar basura o saltos de línea que acaben en un encabezado de correo */
    static String correoValido(String correo) {
        if (correo == null) {
            return null;
        }
        String limpio = correo.strip();
        if (limpio.isEmpty() || limpio.length() > 150 || !limpio.matches("[^\\s@\\r\\n]+@[^\\s@\\r\\n]+\\.[^\\s@\\r\\n]+")) {
            return null;
        }
        return limpio;
    }

    public MensajeDto aDto(Mensaje mensaje, String paraSub) {
        return new MensajeDto(mensaje.getId(), mensaje.getConversacionId(),
                mensaje.getRemitenteSub().equals(paraSub), mensaje.getTexto(), mensaje.getEnviadoEn());
    }

    private ConversacionDto aDto(Conversacion c, String paraSub) {
        return new ConversacionDto(c.getId(), c.getMascotaId(), c.getMascotaNombre(),
                c.getDuenoSub().equals(paraSub), c.getUltimoMensajeEn());
    }

    /* un usuario ajeno recibe "no encontrada" y no "prohibido", para no revelar que la conversación existe */
    private Conversacion buscarParaParticipante(String sub, String conversacionId) {
        return conversaciones.findById(conversacionId)
                .filter(c -> c.incluye(sub) && !c.estaBloqueada())
                .orElseThrow(() -> new RecursoNoEncontradoException("Conversación no encontrada"));
    }

    private Conversacion crear(String clave, DuenoMascotaDto dueno, String interesadoSub) {
        try {
            return conversaciones.save(new Conversacion(dueno.mascotaId(), dueno.nombreMascota(),
                    dueno.duenoSub(), interesadoSub, clock.instant()));
        } catch (DuplicateKeyException e) {
            /* dos personas (o dos clics) la crearon a la vez: gana la primera y esta devuelve la existente */
            return conversaciones.findByClave(clave).orElseThrow(() -> e);
        }
    }
}
