package com.sanosysalvos.ms_chat.dto;

/**
 * Respuesta de GET /internal/mascotas/{id}/chat en ms-mascotas. Nunca se expone al frontend.
 * duenoEmail solo sirve para avisarle por correo que tiene un mensaje nuevo.
 */
public record DuenoMascotaDto(
        String mascotaId,
        String nombreMascota,
        String duenoSub,
        String duenoEmail) {

    public DuenoMascotaDto(String mascotaId, String nombreMascota, String duenoSub) {
        this(mascotaId, nombreMascota, duenoSub, null);
    }
}
