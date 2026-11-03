package com.sanosysalvos.ms_chat.dto;

/**
 * Evento "chat.mensaje.nuevo": ms-alertas lo consume y manda el correo de aviso.
 * Nunca lleva el texto del mensaje, solo lo necesario para decir "tienes un mensaje nuevo".
 */
public record AvisoMensajeEvento(
        String conversacionId,
        String mascotaNombre,
        String emailDestino) {
}
