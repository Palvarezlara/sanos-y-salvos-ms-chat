package com.sanosysalvos.ms_chat.dto;

/** Respuesta de GET /internal/mascotas/{id}/chat en ms-mascotas. Nunca se expone al frontend. */
public record DuenoMascotaDto(
        String mascotaId,
        String nombreMascota,
        String duenoSub) {
}
