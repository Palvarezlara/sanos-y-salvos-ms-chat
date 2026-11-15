package com.sanosysalvos.ms_chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sanosysalvos.ms_chat.config.ChatProperties;
import com.sanosysalvos.ms_chat.dto.AvisoMensajeEvento;
import com.sanosysalvos.ms_chat.messaging.ChatEventPublisher;
import com.sanosysalvos.ms_chat.model.Conversacion;
import com.sanosysalvos.ms_chat.repository.ConversacionRepository;

@ExtendWith(MockitoExtension.class)
class AvisoCorreoServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-03T12:00:00Z");

    @Mock
    private ConversacionRepository conversaciones;
    @Mock
    private ChatEventPublisher publisher;

    private AvisoCorreoService servicio;
    private Conversacion conversacion;

    @BeforeEach
    void preparar() {
        ChatProperties props = new ChatProperties("x", List.of(), Duration.ofDays(90),
                "http://ms-mascotas:8082", 1000, 20, 10, false, Duration.ofMinutes(30));
        servicio = new AvisoCorreoService(conversaciones, publisher, props, Clock.fixed(AHORA, ZoneOffset.UTC), Runnable::run);
        conversacion = new Conversacion("m1", "Firu", "dueno", "interesado", AHORA.minusSeconds(60));
        conversacion.registrarCorreos("dueno@example.com", "interesado@example.com");
    }

    @BeforeEach
    void sellosLibres() {
        /* por defecto el sello atomico se obtiene (nadie avisó antes) */
        lenient().when(conversaciones.sellarAvisoDueno(any(), any(), any())).thenReturn(1L);
        lenient().when(conversaciones.sellarAvisoInteresado(any(), any(), any())).thenReturn(1L);
    }

    @Test
    void avisaAlDuenoCuandoEstaDesconectadoYElAvisoNoLlevaElTexto() {
        servicio.avisarSiCorresponde(conversacion, "interesado", sub -> false);

        ArgumentCaptor<AvisoMensajeEvento> captor = ArgumentCaptor.forClass(AvisoMensajeEvento.class);
        verify(publisher).publicarMensajeNuevo(captor.capture());
        assertThat(captor.getValue().emailDestino()).isEqualTo("dueno@example.com");
        assertThat(captor.getValue().mascotaNombre()).isEqualTo("Firu");
        /* queda sellado (atomico) para no volver a avisar enseguida */
        verify(conversaciones).sellarAvisoDueno(any(), any(), any());
    }

    @Test
    void tambienAvisaAlInteresadoCuandoElDuenoResponde() {
        servicio.avisarSiCorresponde(conversacion, "dueno", sub -> false);

        ArgumentCaptor<AvisoMensajeEvento> captor = ArgumentCaptor.forClass(AvisoMensajeEvento.class);
        verify(publisher).publicarMensajeNuevo(captor.capture());
        assertThat(captor.getValue().emailDestino()).isEqualTo("interesado@example.com");
    }

    @Test
    void siLaOtraPersonaEstaConectadaNoSeLeEnviaCorreo() {
        servicio.avisarSiCorresponde(conversacion, "interesado", sub -> sub.equals("dueno"));

        verify(publisher, never()).publicarMensajeNuevo(any());
    }

    @Test
    void sinCorreoConocidoNoAvisaYNoFalla() {
        Conversacion sinCorreos = new Conversacion("m1", "Firu", "dueno", "interesado", AHORA);

        servicio.avisarSiCorresponde(sinCorreos, "interesado", sub -> false);

        verify(publisher, never()).publicarMensajeNuevo(any());
    }

    @Test
    void noAvisaDeNuevoDentroDelIntervalo() {
        conversacion.registrarAviso("dueno", AHORA.minus(Duration.ofMinutes(10)));

        servicio.avisarSiCorresponde(conversacion, "interesado", sub -> false);

        verify(publisher, never()).publicarMensajeNuevo(any());
    }

    @Test
    void vuelveAAvisarCuandoPasoElIntervalo() {
        conversacion.registrarAviso("dueno", AHORA.minus(Duration.ofMinutes(31)));

        servicio.avisarSiCorresponde(conversacion, "interesado", sub -> false);

        verify(publisher).publicarMensajeNuevo(any());
    }

    @Test
    void siFallaElEnvioNoSeRompeElChatNiSeMarcaComoAvisado() {
        doThrow(new RuntimeException("SNS caído")).when(publisher).publicarMensajeNuevo(any());

        servicio.avisarSiCorresponde(conversacion, "interesado", sub -> false);

        /* se libera el sello: el próximo mensaje lo vuelve a intentar */
        verify(conversaciones).liberarAvisoDueno(any(), eq(AHORA));
    }

    @Test
    void dosMensajesSimultaneosNoGeneranDosCorreos() {
        /* el segundo mensaje encuentra el sello ya puesto por el primero: la actualización atómica devuelve 0 */
        when(conversaciones.sellarAvisoDueno(any(), any(), any())).thenReturn(1L, 0L);

        servicio.avisarSiCorresponde(conversacion, "interesado", sub -> false);
        servicio.avisarSiCorresponde(conversacion, "interesado", sub -> false);

        verify(publisher, times(1)).publicarMensajeNuevo(any());
    }

    @Test
    void siLaColaDeAvisosEstaLlenaSeLiberaElSelloSinRomperElChat() {
        java.util.concurrent.Executor llena = r -> {
            throw new java.util.concurrent.RejectedExecutionException("cola llena");
        };
        servicio = new AvisoCorreoService(conversaciones, publisher,
                new ChatProperties("x", List.of(), Duration.ofDays(90), "http://ms-mascotas:8082", 1000, 20, 10, false,
                        Duration.ofMinutes(30)),
                Clock.fixed(AHORA, ZoneOffset.UTC), llena);

        servicio.avisarSiCorresponde(conversacion, "interesado", sub -> false);

        verify(publisher, never()).publicarMensajeNuevo(any());
        verify(conversaciones).liberarAvisoDueno(any(), eq(AHORA));
    }

    @Test
    void laPublicacionSeHaceEnOtroHiloNoEnElDelEmisor() {
        java.util.List<Runnable> pendientes = new java.util.ArrayList<>();
        servicio = new AvisoCorreoService(conversaciones, publisher,
                new ChatProperties("x", List.of(), Duration.ofDays(90), "http://ms-mascotas:8082", 1000, 20, 10, false,
                        Duration.ofMinutes(30)),
                Clock.fixed(AHORA, ZoneOffset.UTC), pendientes::add);

        servicio.avisarSiCorresponde(conversacion, "interesado", sub -> false);

        /* todavia no se publicó: quedó encolado para el hilo de avisos */
        verify(publisher, never()).publicarMensajeNuevo(any());
        assertThat(pendientes).hasSize(1);
        pendientes.get(0).run();
        verify(publisher).publicarMensajeNuevo(any());
    }
}
