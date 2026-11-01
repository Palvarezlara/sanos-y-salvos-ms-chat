package com.sanosysalvos.ms_chat.dto;

import java.time.Instant;

/** Lo que ve el frontend de una conversación: nunca incluye los sub ni datos de la otra persona. */
public record ConversacionDto(
        String id,
        String mascotaId,
        String mascotaNombre,
        boolean esDueno,
        Instant ultimoMensajeEn) {
}
