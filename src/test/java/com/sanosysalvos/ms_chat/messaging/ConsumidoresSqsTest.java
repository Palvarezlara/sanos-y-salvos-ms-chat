package com.sanosysalvos.ms_chat.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import com.sanosysalvos.ms_chat.service.ChatService;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class ConsumidoresSqsTest {

    private static class Consumidor extends SqsPollingConsumer {
        Consumidor(SqsClient sqs, String url) {
            super(sqs, url, true);
        }

        @Override
        protected void procesar(String cuerpoMensaje) {
        }
    }

    @Test
    void sinUrlDeColaElConsumidorNoArranca() {
        Consumidor consumidor = new Consumidor(mock(SqsClient.class), "  ");

        consumidor.iniciar();

        assertThat(consumidor.estaIniciado()).isFalse();
        assertThat(consumidor.estaSano(Duration.ofSeconds(90))).isFalse();
    }

    @Test
    void conColaYSondeosExitososEstaSano() throws Exception {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(inv -> {
            Thread.sleep(20);
            return ReceiveMessageResponse.builder().build();
        });
        Consumidor consumidor = new Consumidor(sqs, "http://dummy/cola");
        try {
            consumidor.iniciar();
            Thread.sleep(150);

            assertThat(consumidor.estaIniciado()).isTrue();
            assertThat(consumidor.estaSano(ConsumidoresSqsHealthIndicator.TOLERANCIA)).isTrue();
        } finally {
            consumidor.detener();
        }
    }

    @Test
    void conUnaColaInaccesibleDejaDeEstarSano() throws Exception {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenThrow(new RuntimeException("cola inexistente"));
        Consumidor consumidor = new Consumidor(sqs, "http://dummy/cola-mala");
        try {
            consumidor.iniciar();
            Thread.sleep(100);

            assertThat(consumidor.estaSano(Duration.ZERO)).isFalse();
        } finally {
            consumidor.detener();
        }
    }

    @Test
    void elHealthEstaDownSiAlgunConsumidorIniciadoEstaCaido() {
        SqsPollingConsumer sano = mock(SqsPollingConsumer.class);
        when(sano.estaIniciado()).thenReturn(true);
        when(sano.estaSano(any())).thenReturn(true);
        SqsPollingConsumer caido = mock(SqsPollingConsumer.class);
        when(caido.estaIniciado()).thenReturn(true);
        when(caido.estaSano(any())).thenReturn(false);
        when(caido.nombre()).thenReturn("MascotaEliminadaListener");

        var salud = new ConsumidoresSqsHealthIndicator(List.of(sano, caido)).health();

        assertThat(salud.getStatus()).isEqualTo(Status.DOWN);
        assertThat(salud.getDetails()).containsEntry("consumidoresCaidos", List.of("MascotaEliminadaListener"));
    }

    @Test
    void elHealthIgnoraLosConsumidoresApagadosYEstaUpSiTodoFunciona() {
        SqsPollingConsumer apagado = mock(SqsPollingConsumer.class);
        when(apagado.estaIniciado()).thenReturn(false);

        assertThat(new ConsumidoresSqsHealthIndicator(List.of(apagado)).health().getStatus()).isEqualTo(Status.UP);
        assertThat(new ConsumidoresSqsHealthIndicator(List.of()).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void alEliminarseUnReporteSeBorranSusConversaciones() {
        ChatService chat = mock(ChatService.class);
        MascotaEliminadaListener listener = new MascotaEliminadaListener(mock(SqsClient.class), "http://dummy/cola", false, chat);

        listener.procesar("{\"mascotaId\":\"mascota-7\"}");

        verify(chat).eliminarPorMascota("mascota-7");
    }

    @Test
    void unEventoSinMascotaIdSeDescartaEnVezDeReintentarse() {
        ChatService chat = mock(ChatService.class);
        MascotaEliminadaListener listener = new MascotaEliminadaListener(mock(SqsClient.class), "http://dummy/cola", false, chat);

        listener.procesar("{}");
        listener.procesar("{\"mascotaId\":\"  \"}");

        verify(chat, never()).eliminarPorMascota(any());
    }
}
