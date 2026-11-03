package com.sanosysalvos.ms_chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.sanosysalvos.ms_chat.client.MascotasClient;
import com.sanosysalvos.ms_chat.config.ChatProperties;
import com.sanosysalvos.ms_chat.dto.ConversacionDto;
import com.sanosysalvos.ms_chat.dto.DuenoMascotaDto;
import com.sanosysalvos.ms_chat.dto.MensajeDto;
import com.sanosysalvos.ms_chat.exception.RecursoNoEncontradoException;
import com.sanosysalvos.ms_chat.exception.SolicitudInvalidaException;
import com.sanosysalvos.ms_chat.model.Conversacion;
import com.sanosysalvos.ms_chat.model.Mensaje;
import com.sanosysalvos.ms_chat.repository.ConversacionRepository;
import com.sanosysalvos.ms_chat.repository.MensajeRepository;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final Instant AHORA = Instant.parse("2026-10-30T12:00:00Z");

    @Mock
    private ConversacionRepository conversaciones;
    @Mock
    private MensajeRepository mensajes;
    @Mock
    private MascotasClient mascotas;

    private ChatService servicio;

    @BeforeEach
    void preparar() {
        ChatProperties props = new ChatProperties("x", List.of(), Duration.ofDays(90),
                "http://ms-mascotas:8082", 20, 20, 10, false, Duration.ofMinutes(30));
        servicio = new ChatService(conversaciones, mensajes, mascotas, props, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private Conversacion conversacionConId(String id) {
        Conversacion c = new Conversacion("m1", "Firu", "dueno", "interesado", AHORA);
        ReflectionTestUtils.setField(c, "id", id);
        return c;
    }

    // ---------- iniciar conversación ----------

    @Test
    void creaLaConversacionSiNoExiste() {
        when(mascotas.obtenerDueno("m1")).thenReturn(Optional.of(new DuenoMascotaDto("m1", "Firu", "dueno")));
        when(conversaciones.findByClave(any())).thenReturn(Optional.empty());
        when(conversaciones.save(any(Conversacion.class))).thenAnswer(inv -> inv.getArgument(0));

        ConversacionDto dto = servicio.iniciarConversacion("interesado", "m1");

        assertThat(dto.mascotaNombre()).isEqualTo("Firu");
        assertThat(dto.esDueno()).as("quien inicia es el interesado, no el dueño").isFalse();
        verify(conversaciones).save(any(Conversacion.class));
    }

    @Test
    void reutilizaLaConversacionExistenteEnVezDeDuplicarla() {
        Conversacion existente = conversacionConId("c1");
        when(mascotas.obtenerDueno("m1")).thenReturn(Optional.of(new DuenoMascotaDto("m1", "Firu", "dueno")));
        when(conversaciones.findByClave(Conversacion.claveDe("m1", "dueno", "interesado")))
                .thenReturn(Optional.of(existente));

        ConversacionDto dto = servicio.iniciarConversacion("interesado", "m1");

        assertThat(dto.id()).isEqualTo("c1");
        verify(conversaciones, never()).save(any());
    }

    @Test
    void siDosPersonasLaCreanALaVezDevuelveLaQueGano() {
        Conversacion ganadora = conversacionConId("c-ganadora");
        when(mascotas.obtenerDueno("m1")).thenReturn(Optional.of(new DuenoMascotaDto("m1", "Firu", "dueno")));
        when(conversaciones.findByClave(any())).thenReturn(Optional.empty(), Optional.of(ganadora));
        when(conversaciones.save(any(Conversacion.class))).thenThrow(new DuplicateKeyException("duplicada"));

        assertThat(servicio.iniciarConversacion("interesado", "m1").id()).isEqualTo("c-ganadora");
    }

    @Test
    void elDuenoNoPuedeChatearConsigoMismo() {
        when(mascotas.obtenerDueno("m1")).thenReturn(Optional.of(new DuenoMascotaDto("m1", "Firu", "dueno")));

        assertThatThrownBy(() -> servicio.iniciarConversacion("dueno", "m1"))
                .isInstanceOf(SolicitudInvalidaException.class);
        verify(conversaciones, never()).save(any());
    }

    @Test
    void rechazaUnReporteInexistenteOSinDueno() {
        when(mascotas.obtenerDueno("no-existe")).thenReturn(Optional.empty());
        when(mascotas.obtenerDueno("sin-dueno")).thenReturn(Optional.of(new DuenoMascotaDto("sin-dueno", "X", " ")));

        assertThatThrownBy(() -> servicio.iniciarConversacion("interesado", "no-existe"))
                .isInstanceOf(RecursoNoEncontradoException.class);
        assertThatThrownBy(() -> servicio.iniciarConversacion("interesado", "sin-dueno"))
                .isInstanceOf(SolicitudInvalidaException.class);
    }

    // ---------- enviar mensajes ----------

    @Test
    void guardaElMensajeYDevuelveALosDosParticipantes() {
        Conversacion c = conversacionConId("c1");
        when(conversaciones.findById("c1")).thenReturn(Optional.of(c));
        when(mensajes.save(any(Mensaje.class))).thenAnswer(inv -> inv.getArgument(0));

        ChatService.ResultadoEnvio resultado = servicio.enviarMensaje("interesado", "c1", "  Hola, vi a tu perro  ");

        assertThat(resultado.mensaje().getTexto()).as("se recorta el texto").isEqualTo("Hola, vi a tu perro");
        assertThat(resultado.participantes()).containsExactlyInAnyOrder("dueno", "interesado");
        assertThat(c.getUltimoMensajeEn()).isEqualTo(AHORA);
        verify(conversaciones).save(c);
    }

    @Test
    void unUsuarioAjenoNoPuedeEscribirNiSaberQueLaConversacionExiste() {
        when(conversaciones.findById("c1")).thenReturn(Optional.of(conversacionConId("c1")));

        assertThatThrownBy(() -> servicio.enviarMensaje("intruso", "c1", "hola"))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(mensajes, never()).save(any());
    }

    @Test
    void rechazaMensajesVaciosOMuyLargos() {
        when(conversaciones.findById("c1")).thenReturn(Optional.of(conversacionConId("c1")));

        assertThatThrownBy(() -> servicio.enviarMensaje("dueno", "c1", "   "))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.enviarMensaje("dueno", "c1", null))
                .isInstanceOf(SolicitudInvalidaException.class);
        assertThatThrownBy(() -> servicio.enviarMensaje("dueno", "c1", "x".repeat(21)))
                .isInstanceOf(SolicitudInvalidaException.class);
        verify(mensajes, never()).save(any());
    }

    // ---------- historial ----------

    @Test
    void elHistorialSaleEnOrdenCronologicoYMarcaCualesSonMios() {
        Mensaje nuevo = new Mensaje("c1", "dueno", "segundo", AHORA);
        Mensaje viejo = new Mensaje("c1", "interesado", "primero", AHORA.minusSeconds(60));
        when(conversaciones.findById("c1")).thenReturn(Optional.of(conversacionConId("c1")));
        /* el repositorio los devuelve del más nuevo al más viejo (orden DESC) */
        when(mensajes.findByConversacionIdAndEnviadoEnBefore(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(nuevo, viejo));

        List<MensajeDto> historial = servicio.listarMensajes("interesado", "c1", null, 50);

        assertThat(historial).extracting(MensajeDto::texto).containsExactly("primero", "segundo");
        assertThat(historial).extracting(MensajeDto::mio).containsExactly(true, false);
    }

    @Test
    void elLimiteDelHistorialNoPasaDe100() {
        when(conversaciones.findById("c1")).thenReturn(Optional.of(conversacionConId("c1")));
        when(mensajes.findByConversacionIdAndEnviadoEnBefore(any(), any(), any(Pageable.class))).thenReturn(List.of());

        servicio.listarMensajes("dueno", "c1", null, 5000);

        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        verify(mensajes).findByConversacionIdAndEnviadoEnBefore(any(), any(), pagina.capture());
        assertThat(pagina.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void unUsuarioAjenoNoPuedeLeerElHistorial() {
        when(conversaciones.findById("c1")).thenReturn(Optional.of(conversacionConId("c1")));

        assertThatThrownBy(() -> servicio.listarMensajes("intruso", "c1", null, 50))
                .isInstanceOf(RecursoNoEncontradoException.class);
        verify(mensajes, never()).findByConversacionIdAndEnviadoEnBefore(any(), any(), any());
    }

    @Test
    void alIniciarGuardaElCorreoDelDuenoYElDelInteresadoSoloParaAvisar() {
        when(mascotas.obtenerDueno("m1")).thenReturn(
                Optional.of(new DuenoMascotaDto("m1", "Firu", "dueno", "dueno@example.com")));
        Conversacion existente = conversacionConId("c1");
        when(conversaciones.findByClave(any())).thenReturn(Optional.of(existente));

        servicio.iniciarConversacion("interesado", "m1", "  interesado@example.com ");

        assertThat(existente.emailDe("dueno")).isEqualTo("dueno@example.com");
        assertThat(existente.emailDe("interesado")).isEqualTo("interesado@example.com");
        verify(conversaciones).save(existente);
    }

    @Test
    void ignoraCorreosQueNoParecenCorreosOTraenSaltosDeLinea() {
        when(mascotas.obtenerDueno("m1")).thenReturn(Optional.of(new DuenoMascotaDto("m1", "Firu", "dueno", "no-es-correo")));
        Conversacion existente = conversacionConId("c1");
        when(conversaciones.findByClave(any())).thenReturn(Optional.of(existente));

        servicio.iniciarConversacion("interesado", "m1", "x@example.com\r\nBcc: victima@example.com");

        assertThat(existente.emailDe("dueno")).isNull();
        assertThat(existente.emailDe("interesado")).isNull();
        verify(conversaciones, never()).save(any());
    }

    @Test
    void elCorreoNuncaViajaEnLaRespuestaAlFrontend() {
        when(mascotas.obtenerDueno("m1")).thenReturn(
                Optional.of(new DuenoMascotaDto("m1", "Firu", "dueno", "dueno@example.com")));
        when(conversaciones.findByClave(any())).thenReturn(Optional.of(conversacionConId("c1")));

        ConversacionDto dto = servicio.iniciarConversacion("interesado", "m1", "interesado@example.com");

        assertThat(dto.toString()).doesNotContain("@example.com");
    }
}
