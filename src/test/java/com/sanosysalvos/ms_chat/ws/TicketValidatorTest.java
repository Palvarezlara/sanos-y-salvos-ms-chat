package com.sanosysalvos.ms_chat.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sanosysalvos.ms_chat.TicketsDePrueba;
import com.sanosysalvos.ms_chat.config.ChatProperties;

class TicketValidatorTest {

    private static final Instant AHORA = Instant.parse("2026-10-30T12:00:00Z");
    private static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);

    private TicketValidator validadorCon(String secreto) {
        return new TicketValidator(new ChatProperties(secreto, List.of(), Duration.ofDays(90),
                "http://ms-mascotas:8082", 1000, 20, 10, false), RELOJ);
    }

    private String ticket(String secreto, String sub, String jti, Instant vence, String audiencia) throws Exception {
        return TicketsDePrueba.firmar(secreto, sub, jti, vence, audiencia);
    }

    @Test
    void aceptaUnTicketValidoYDevuelveElSub() throws Exception {
        String t = ticket(TicketsDePrueba.SECRETO, "usuario-1", "j1", AHORA.plusSeconds(30), "ms-chat");

        assertThat(validadorCon(TicketsDePrueba.SECRETO).validar(t)).contains("usuario-1");
    }

    @Test
    void rechazaReutilizarElMismoTicket() throws Exception {
        TicketValidator validador = validadorCon(TicketsDePrueba.SECRETO);
        String t = ticket(TicketsDePrueba.SECRETO, "usuario-1", "j-repetido", AHORA.plusSeconds(30), "ms-chat");

        assertThat(validador.validar(t)).isPresent();
        assertThat(validador.validar(t)).isEmpty();
    }

    @Test
    void rechazaUnTicketVencido() throws Exception {
        String t = ticket(TicketsDePrueba.SECRETO, "usuario-1", "j2", AHORA.minusSeconds(1), "ms-chat");

        assertThat(validadorCon(TicketsDePrueba.SECRETO).validar(t)).isEmpty();
    }

    @Test
    void rechazaUnTicketFirmadoConOtraClave() throws Exception {
        String t = ticket("otra-clave-distinta-de-al-menos-32-caracteres", "usuario-1", "j3",
                AHORA.plusSeconds(30), "ms-chat");

        assertThat(validadorCon(TicketsDePrueba.SECRETO).validar(t)).isEmpty();
    }

    @Test
    void rechazaUnTicketParaOtraAudiencia() throws Exception {
        String t = ticket(TicketsDePrueba.SECRETO, "usuario-1", "j4", AHORA.plusSeconds(30), "otro-servicio");

        assertThat(validadorCon(TicketsDePrueba.SECRETO).validar(t)).isEmpty();
    }

    @Test
    void rechazaTextoQueNoEsUnJwtOUnoVacio() {
        TicketValidator validador = validadorCon(TicketsDePrueba.SECRETO);

        assertThat(validador.validar("esto-no-es-un-jwt")).isEmpty();
        assertThat(validador.validar("")).isEmpty();
        assertThat(validador.validar(null)).isEmpty();
    }

    @Test
    void sinClaveConfiguradaRechazaTodaConexion() throws Exception {
        String t = ticket(TicketsDePrueba.SECRETO, "usuario-1", "j5", AHORA.plusSeconds(30), "ms-chat");

        assertThat(validadorCon(null).validar(t)).isEmpty();
    }

    @Test
    void conUnaClaveCortaRechazaTodaConexion() throws Exception {
        String t = ticket(TicketsDePrueba.SECRETO, "usuario-1", "j6", AHORA.plusSeconds(30), "ms-chat");

        assertThat(validadorCon("clave-corta").validar(t)).isEmpty();
    }
}
