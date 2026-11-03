package com.sanosysalvos.ms_chat.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class ConversacionTest {

    private static final Instant AHORA = Instant.parse("2026-11-03T12:00:00Z");
    private static final Duration INTERVALO = Duration.ofMinutes(30);

    private Conversacion nueva() {
        return new Conversacion("m1", "Firu", "dueno", "interesado", AHORA);
    }

    @Test
    void registrarCorreosGuardaCadaUnoYAvisaSoloSiHuboCambios() {
        Conversacion c = nueva();

        assertThat(c.registrarCorreos("d@example.com", "i@example.com")).isTrue();
        assertThat(c.registrarCorreos("d@example.com", "i@example.com")).isFalse();
        assertThat(c.registrarCorreos(null, null)).isFalse();
        assertThat(c.emailDe("dueno")).isEqualTo("d@example.com");
        assertThat(c.emailDe("interesado")).isEqualTo("i@example.com");
    }

    @Test
    void unCorreoNuloNoBorraElQueYaEstaba() {
        Conversacion c = nueva();
        c.registrarCorreos("d@example.com", "i@example.com");

        c.registrarCorreos(null, "nuevo@example.com");

        assertThat(c.emailDe("dueno")).isEqualTo("d@example.com");
        assertThat(c.emailDe("interesado")).isEqualTo("nuevo@example.com");
    }

    @Test
    void emailDeUnaPersonaAjenaOSinCorreoEsNulo() {
        Conversacion c = nueva();

        assertThat(c.emailDe("dueno")).isNull();
        assertThat(c.emailDe(null)).isNull();
    }

    @Test
    void elIntervaloEntreAvisosSeCuentaPorPersona() {
        Conversacion c = nueva();

        assertThat(c.puedeAvisar("dueno", AHORA, INTERVALO)).isTrue();

        c.registrarAviso("dueno", AHORA);

        assertThat(c.puedeAvisar("dueno", AHORA.plus(Duration.ofMinutes(29)), INTERVALO)).isFalse();
        assertThat(c.puedeAvisar("dueno", AHORA.plus(INTERVALO), INTERVALO)).isTrue();
        /* el aviso al dueño no cuenta para el interesado */
        assertThat(c.puedeAvisar("interesado", AHORA, INTERVALO)).isTrue();
    }
}
