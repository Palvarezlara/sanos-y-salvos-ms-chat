package com.sanosysalvos.ms_chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.sanosysalvos.ms_chat.exception.DemasiadasSolicitudesException;

class LimitadorUsoTest {

    static class RelojManual extends Clock {
        Instant ahora = Instant.parse("2026-11-03T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    @Test
    void limitaPorClaveSinAfectarALosDemas() {
        LimitadorUso limitador = new LimitadorUso(2, Duration.ofMinutes(1), new RelojManual());

        limitador.registrar("a");
        limitador.registrar("a");

        assertThatThrownBy(() -> limitador.registrar("a")).isInstanceOf(DemasiadasSolicitudesException.class);
        assertThatCode(() -> limitador.registrar("b")).doesNotThrowAnyException();
    }

    @Test
    void despuesDeLaVentanaVuelveAPermitirYIndicaCuantoEsperar() {
        RelojManual reloj = new RelojManual();
        LimitadorUso limitador = new LimitadorUso(1, Duration.ofMinutes(10), reloj);
        limitador.registrar("a");
        reloj.ahora = reloj.ahora.plus(Duration.ofMinutes(4));

        DemasiadasSolicitudesException ex = org.junit.jupiter.api.Assertions.assertThrows(
                DemasiadasSolicitudesException.class, () -> limitador.registrar("a"));
        assertThat(ex.getReintentarEnSegundos()).isBetween(355L, 365L);

        reloj.ahora = reloj.ahora.plus(Duration.ofMinutes(7));
        assertThatCode(() -> limitador.registrar("a")).doesNotThrowAnyException();
    }

    @Test
    void losIntentosRechazadosNoCuentan() {
        RelojManual reloj = new RelojManual();
        LimitadorUso limitador = new LimitadorUso(1, Duration.ofMinutes(1), reloj);
        limitador.registrar("a");
        for (int i = 0; i < 20; i++) {
            org.junit.jupiter.api.Assertions.assertThrows(DemasiadasSolicitudesException.class, () -> limitador.registrar("a"));
        }

        reloj.ahora = reloj.ahora.plusSeconds(61);

        assertThatCode(() -> limitador.registrar("a")).doesNotThrowAnyException();
    }
}
