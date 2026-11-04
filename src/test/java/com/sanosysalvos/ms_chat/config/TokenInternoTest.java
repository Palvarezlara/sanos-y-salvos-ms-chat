package com.sanosysalvos.ms_chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TokenInternoTest {

    private static final String SECRETO = "secreto-de-servicio-de-prueba-de-al-menos-32-caracteres";

    @Test
    void sinSecretoOConUnoCortoElServicioNoArranca() {
        for (String malo : new String[] { null, "", "   ", "corto" }) {
            assertThatThrownBy(() -> new TokenInterno(malo)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("INTERNAL_API_TOKEN");
        }
    }

    @Test
    void soloAceptaElSecretoExacto() {
        TokenInterno token = new TokenInterno(SECRETO);

        assertThat(token.valido(SECRETO)).isTrue();
        assertThat(token.valido(" " + SECRETO + " ")).isTrue();
        assertThat(token.valido(SECRETO + "x")).isFalse();
        assertThat(token.valido(SECRETO.substring(1))).isFalse();
        assertThat(token.valido("")).isFalse();
        assertThat(token.valido(null)).isFalse();
    }
}
