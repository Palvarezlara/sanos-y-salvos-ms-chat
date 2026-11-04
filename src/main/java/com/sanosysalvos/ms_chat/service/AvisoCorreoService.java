package com.sanosysalvos.ms_chat.service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 *  - como máximo un aviso cada chat.aviso-intervalo por conversación y persona, decidido con una
 *    actualización atómica en Mongo: dos mensajes simultáneos no pueden avisar los dos;
 *  - el aviso no incluye el texto del mensaje, solo el nombre de la mascota y el enlace;
 *  - la publicación en SNS se hace en otro hilo con timeout corto: un SNS lento no frena el chat;
 *  - si falla el envío, el chat sigue funcionando (solo se registra una advertencia y se libera el sello).
 */
@Service
public class AvisoCorreoService {

    private static final Logger log = LoggerFactory.getLogger(AvisoCorreoService.class);

    private final ConversacionRepository conversaciones;
    private final ChatEventPublisher publisher;
    private final ChatProperties props;
    private final Clock clock;
    private final Executor ejecutor;

    public AvisoCorreoService(ConversacionRepository conversaciones, ChatEventPublisher publisher,
            ChatProperties props, Clock clock, @Qualifier("avisosExecutor") Executor ejecutor) {
        this.conversaciones = conversaciones;
        this.publisher = publisher;
        this.props = props;
        this.clock = clock;
        this.ejecutor = ejecutor;
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

            boolean esDueno = destinatario.equals(conversacion.getDuenoSub());
            Instant limite = ahora.minus(props.avisoIntervalo());
            /* el sello atómico decide quién avisa: si otro mensaje simultáneo ya lo selló, este no envía nada */
            long sellado = esDueno
                    ? conversaciones.sellarAvisoDueno(conversacion.getId(), limite, ahora)
                    : conversaciones.sellarAvisoInteresado(conversacion.getId(), limite, ahora);
            if (sellado == 0) {
                return;
            }

            AvisoMensajeEvento evento = new AvisoMensajeEvento(conversacion.getId(), conversacion.getMascotaNombre(), email);
            try {
                ejecutor.execute(() -> publicar(conversacion.getId(), esDueno, ahora, evento));
            } catch (RuntimeException e) {
                /* cola de avisos llena: se libera el sello para que el próximo mensaje lo reintente */
                liberarSello(conversacion.getId(), esDueno, ahora);
                throw e;
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo encolar el aviso por correo de la conversación {}: {}",
                    conversacion.getId(), e.getClass().getSimpleName());
        }
    }

    private void publicar(String conversacionId, boolean esDueno, Instant sello, AvisoMensajeEvento evento) {
        try {
            publisher.publicarMensajeNuevo(evento);
            /* no se registra el correo ni el contenido */
            log.info("Aviso de mensaje nuevo encolado, conversacionId={}", conversacionId);
        } catch (RuntimeException e) {
            log.warn("No se pudo publicar el aviso por correo de la conversación {}: {}",
                    conversacionId, e.getClass().getSimpleName());
            liberarSello(conversacionId, esDueno, sello);
        }
    }

    private void liberarSello(String conversacionId, boolean esDueno, Instant sello) {
        try {
            if (esDueno) {
                conversaciones.liberarAvisoDueno(conversacionId, sello);
            } else {
                conversaciones.liberarAvisoInteresado(conversacionId, sello);
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo liberar el sello de aviso de la conversación {}", conversacionId);
        }
    }
}
