package com.sanosysalvos.ms_chat.service;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sanosysalvos.ms_chat.config.ChatProperties;
import com.sanosysalvos.ms_chat.dto.AvisoMensajeEvento;
import com.sanosysalvos.ms_chat.messaging.ChatEventPublisher;
import com.sanosysalvos.ms_chat.model.Conversacion;
import com.sanosysalvos.ms_chat.repository.ConversacionRepository;

/**
 * Avisa por correo a quien recibió un mensaje y no está conectado, para que no dependa de
 * tener la página abierta. Reglas para no molestar ni filtrar datos:
 *  - solo si la otra persona NO tiene una conexión abierta (si está conectada lo ve en vivo);
 *  - como máximo un aviso cada chat.aviso-intervalo por conversación y persona;
 *  - el aviso no incluye el texto del mensaje, solo el nombre de la mascota y el enlace;
 *  - si falla el envío, el chat sigue funcionando (solo se registra una advertencia).
 */
@Service
public class AvisoCorreoService {

    private static final Logger log = LoggerFactory.getLogger(AvisoCorreoService.class);

    private final ConversacionRepository conversaciones;
    private final ChatEventPublisher publisher;
    private final ChatProperties props;
    private final Clock clock;

    public AvisoCorreoService(ConversacionRepository conversaciones, ChatEventPublisher publisher,
            ChatProperties props, Clock clock) {
        this.conversaciones = conversaciones;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
    }

    public void avisarSiCorresponde(Conversacion conversacion, String remitenteSub, Predicate<String> estaConectado) {
        try {
            String destinatario = conversacion.getParticipantes().stream()
                    .filter(sub -> !sub.equals(remitenteSub))
                    .findFirst()
                    .orElse(null);
            if (destinatario == null || estaConectado.test(destinatario)) {
                return;
            }
            String email = conversacion.emailDe(destinatario);
            Instant ahora = clock.instant();
            if (email == null || !conversacion.puedeAvisar(destinatario, ahora, props.avisoIntervalo())) {
                return;
            }

            publisher.publicarMensajeNuevo(
                    new AvisoMensajeEvento(conversacion.getId(), conversacion.getMascotaNombre(), email));
            conversacion.registrarAviso(destinatario, ahora);
            conversaciones.save(conversacion);
            /* no se registra el correo ni el contenido */
            log.info("Aviso de mensaje nuevo encolado, conversacionId={}", conversacion.getId());
        } catch (RuntimeException e) {
            log.warn("No se pudo encolar el aviso por correo de la conversación {}: {}",
                    conversacion.getId(), e.getClass().getSimpleName());
        }
    }
}
