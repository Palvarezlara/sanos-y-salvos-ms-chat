package com.sanosysalvos.ms_chat.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "chat")
public record ChatProperties(
        /* clave compartida con el BFF para firmar/verificar el ticket del WebSocket; sin ella el chat rechaza toda conexión */
        String ticketSecret,
        @DefaultValue("http://localhost:5173") List<String> allowedOrigins,
        /* cuánto tiempo se conservan los mensajes (índice TTL en Mongo) */
        @DefaultValue("90d") Duration retencion,
        @DefaultValue("http://ms-mascotas:8082") String mascotasBaseUrl,
        @DefaultValue("1000") int maxLargoMensaje,
        @DefaultValue("20") int maxMensajesPorVentana,
        @DefaultValue("10") int ventanaSegundos,
        @DefaultValue("true") boolean crearIndices,
        /* cada cuánto, como máximo, se avisa por correo de mensajes nuevos en una misma conversación */
        @DefaultValue("30m") Duration avisoIntervalo) {
}
