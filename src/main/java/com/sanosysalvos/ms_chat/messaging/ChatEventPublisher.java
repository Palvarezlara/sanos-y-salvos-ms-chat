package com.sanosysalvos.ms_chat.messaging;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.sanosysalvos.ms_chat.dto.AvisoMensajeEvento;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publica en el topic SNS compartido ("sanos-eventos") con el tipo de evento como atributo:
 * es lo que usan las suscripciones SQS para filtrar y que cada cola reciba solo lo suyo.
 * Sin topic configurado no publica (el chat funciona igual, solo sin avisos por correo).
 */
@Component
public class ChatEventPublisher {

    public static final String TIPO_MENSAJE_NUEVO = "chat.mensaje.nuevo";
    private static final Logger log = LoggerFactory.getLogger(ChatEventPublisher.class);

    private final SnsClient snsClient;
    private final String topicArn;
    private final JsonMapper json = JsonMapper.builder().build();

    public ChatEventPublisher(SnsClient snsClient, @Value("${aws.sns.topic-arn:}") String topicArn) {
        this.snsClient = snsClient;
        this.topicArn = topicArn;
    }

    public boolean habilitado() {
        return topicArn != null && !topicArn.isBlank();
    }

    public void publicarMensajeNuevo(AvisoMensajeEvento evento) {
        if (!habilitado()) {
            log.debug("aws.sns.topic-arn no configurado: no se publican avisos de chat");
            return;
        }
        snsClient.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .message(json.writeValueAsString(evento))
                .messageAttributes(Map.of("tipoEvento", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(TIPO_MENSAJE_NUEVO)
                        .build()))
                .build());
    }
}
