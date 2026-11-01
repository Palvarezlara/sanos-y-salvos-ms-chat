package com.sanosysalvos.ms_chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IniciarConversacionRequest(
        @NotBlank(message = "mascotaId es requerido")
        @Size(max = 50, message = "mascotaId no puede superar los 50 caracteres")
        String mascotaId) {
}
