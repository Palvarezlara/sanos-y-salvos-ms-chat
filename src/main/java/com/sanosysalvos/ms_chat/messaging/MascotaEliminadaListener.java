package com.sanosysalvos.ms_chat.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sanosysalvos.ms_chat.service.ChatService;

import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consume "mascota.eliminada" (lo publica ms-mascotas al borrar un reporte) y borra sus conversaciones, mensajes y
 * los correos guardados para los avisos: no deben quedar datos personales de un reporte que ya no existe (Ley 21.719).
 * La cola es opcional: sin URL, este consumidor no arranca.
 */
@Component
public class MascotaEliminadaListener extends SqsPollingConsumer {

    private static final Logger log = LoggerFactory.getLogger(MascotaEliminadaListener.class);

    /** Forma del evento que publica ms-mascotas. */
    record EventoEliminada(String mascotaId) {
    }

    private final ChatService chatService;
    private final JsonMapper json = JsonMapper.builder().build();

    public MascotaEliminadaListener(
            SqsClient sqsClient,
            @Value("${sqs.cola-mascota-eliminada-url:}") String queueUrl,
            @Value("${sqs.consumidor.auto-arranque:true}") boolean autoArranque,
            ChatService chatService) {
        super(sqsClient, queueUrl, autoArranque);
        this.chatService = chatService;
    }

    @Override
    protected void procesar(String cuerpoMensaje) {
        EventoEliminada evento = json.readValue(cuerpoMensaje, EventoEliminada.class);
        if (evento == null || evento.mascotaId() == null || evento.mascotaId().isBlank()) {
            /* evento mal formado: reintentarlo no lo arregla; se descarta */
            log.warn("Evento mascota.eliminada descartado: sin mascotaId");
            return;
        }
        chatService.eliminarPorMascota(evento.mascotaId());
    }
}
