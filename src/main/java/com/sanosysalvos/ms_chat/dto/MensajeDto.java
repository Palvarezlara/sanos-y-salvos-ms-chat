package com.sanosysalvos.ms_chat.dto;

import java.time.Instant;

/** "mio" se calcula para quien lo recibe, así el frontend nunca ve el sub del remitente. */
public record MensajeDto(
        String id,
        String conversacionId,
        boolean mio,
        String texto,
        Instant enviadoEn) {
}
