package com.sanosysalvos.ms_chat.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class LimitadorVentanaTest {

    /** Reloj que se puede adelantar a mano. */
    private static final class RelojMutable extends Clock {
        private final AtomicReference<Instant> ahora = new AtomicReference<>(Instant.parse("2026-10-30T12:00:00Z"));

        void avanzar(Duration d) {
            ahora.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora.get();
        }
    }

    @Test
    void bloqueaAlSuperarElMaximoYVuelvePermitirCuandoPasaLaVentana() {
        RelojMutable reloj = new RelojMutable();
        LimitadorVentana limitador = new LimitadorVentana(3, Duration.ofSeconds(10), reloj);

        assertThat(limitador.permitir()).isTrue();
        assertThat(limitador.permitir()).isTrue();
        assertThat(limitador.permitir()).isTrue();
        assertThat(limitador.permitir()).as("el cuarto dentro de la ventana se rechaza").isFalse();

        reloj.avanzar(Duration.ofSeconds(11));

        assertThat(limitador.permitir()).as("pasada la ventana vuelve a permitir").isTrue();
    }
}
