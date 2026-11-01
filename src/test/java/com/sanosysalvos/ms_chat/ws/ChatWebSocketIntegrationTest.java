package com.sanosysalvos.ms_chat.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.sanosysalvos.ms_chat.TicketsDePrueba;
import com.sanosysalvos.ms_chat.client.MascotasClient;
import com.sanosysalvos.ms_chat.model.Conversacion;
import com.sanosysalvos.ms_chat.model.Mensaje;
import com.sanosysalvos.ms_chat.repository.ConversacionRepository;
import com.sanosysalvos.ms_chat.repository.MensajeRepository;

/**
 * Prueba de punta a punta del canal en tiempo real: levanta el servidor real en un
 * puerto al azar y conecta clientes WebSocket de verdad. Solo se simulan la base de
 * datos (los repositorios) y la llamada a ms-mascotas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChatWebSocketIntegrationTest {

    @Value("${local.server.port}")
    private int puerto;

    @MockitoBean
    private ConversacionRepository conversaciones;
    @MockitoBean
    private MensajeRepository mensajes;
    @MockitoBean
    private MascotasClient mascotasClient;

    /** Cliente de prueba: guarda en una cola todo lo que el servidor le manda. */
    private static final class Cliente extends TextWebSocketHandler {
        final LinkedBlockingQueue<String> recibidos = new LinkedBlockingQueue<>();
        WebSocketSession sesion;

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage mensaje) {
            recibidos.add(mensaje.getPayload());
        }

        String siguiente() throws InterruptedException {
            String m = recibidos.poll(5, TimeUnit.SECONDS);
            assertThat(m).as("el servidor debía mandar algo en 5 segundos").isNotNull();
            return m;
        }

        void enviar(String json) throws Exception {
            sesion.sendMessage(new TextMessage(json));
        }
    }

    @BeforeEach
    void conversacionDeDosPersonas() {
        Conversacion c = new Conversacion("m1", "Firu", "dueno-sub", "interesado-sub", Instant.now());
        ReflectionTestUtils.setField(c, "id", "c1");
        when(conversaciones.findById("c1")).thenReturn(Optional.of(c));
        when(conversaciones.save(any(Conversacion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mensajes.save(any(Mensaje.class))).thenAnswer(inv -> {
            Mensaje m = inv.getArgument(0);
            ReflectionTestUtils.setField(m, "id", "msg-" + UUID.randomUUID());
            return m;
        });
    }

    private Cliente conectar(String sub) throws Exception {
        Cliente cliente = new Cliente();
        String ticket = TicketsDePrueba.valido(sub, UUID.randomUUID().toString());
        cliente.sesion = new StandardWebSocketClient()
                .execute(cliente, "ws://localhost:" + puerto + "/ws/chat?ticket=" + ticket)
                .get(5, TimeUnit.SECONDS);
        return cliente;
    }

    @Test
    void unMensajeLlegaALosDosParticipantesYCadaUnoSabeCualEsSuyo() throws Exception {
        Cliente dueno = conectar("dueno-sub");
        Cliente interesado = conectar("interesado-sub");

        interesado.enviar("{\"tipo\":\"mensaje\",\"conversacionId\":\"c1\",\"texto\":\"Creo que vi a tu perro\"}");

        String paraInteresado = interesado.siguiente();
        String paraDueno = dueno.siguiente();

        assertThat(paraInteresado).contains("\"tipo\":\"mensaje\"").contains("Creo que vi a tu perro").contains("\"mio\":true");
        assertThat(paraDueno).contains("\"tipo\":\"mensaje\"").contains("Creo que vi a tu perro").contains("\"mio\":false");
        /* el sub de nadie viaja al navegador */
        assertThat(paraDueno).doesNotContain("dueno-sub").doesNotContain("interesado-sub");
    }

    @Test
    void sinTicketOConUnoInvalidoElHandshakeSeRechaza() {
        Cliente cliente = new Cliente();

        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(cliente, "ws://localhost:" + puerto + "/ws/chat")
                .get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);

        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(cliente, "ws://localhost:" + puerto + "/ws/chat?ticket=basura")
                .get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }

    @Test
    void unTicketSoloSirveUnaVez() throws Exception {
        String ticket = TicketsDePrueba.valido("dueno-sub", "ticket-de-un-solo-uso");
        String url = "ws://localhost:" + puerto + "/ws/chat?ticket=" + ticket;

        new StandardWebSocketClient().execute(new Cliente(), url).get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> new StandardWebSocketClient().execute(new Cliente(), url).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
    }

    @Test
    void unTerceroNoPuedeEscribirEnUnaConversacionAjena() throws Exception {
        Cliente intruso = conectar("intruso-sub");

        intruso.enviar("{\"tipo\":\"mensaje\",\"conversacionId\":\"c1\",\"texto\":\"hola\"}");

        assertThat(intruso.siguiente()).contains("\"tipo\":\"error\"").contains("no_encontrada");
    }

    @Test
    void rechazaMensajesVaciosFormatoInvalidoYTiposDesconocidos() throws Exception {
        Cliente dueno = conectar("dueno-sub");

        dueno.enviar("{\"tipo\":\"mensaje\",\"conversacionId\":\"c1\",\"texto\":\"   \"}");
        assertThat(dueno.siguiente()).contains("solicitud_invalida");

        dueno.enviar("esto no es json");
        assertThat(dueno.siguiente()).contains("formato_invalido");

        dueno.enviar("{\"tipo\":\"borrar-todo\"}");
        assertThat(dueno.siguiente()).contains("tipo_desconocido");
    }

    @Test
    void elPingSeRespondeConPong() throws Exception {
        Cliente dueno = conectar("dueno-sub");

        dueno.enviar("{\"tipo\":\"ping\"}");

        assertThat(dueno.siguiente()).contains("pong");
    }

    @Test
    void despuesDelLimiteDeMensajesPorVentanaSeAvisaYNoSeGuarda() throws Exception {
        Cliente dueno = conectar("dueno-sub");

        /* el límite de prueba es el valor por defecto: 20 mensajes por ventana de 10 s */
        for (int i = 0; i < 20; i++) {
            dueno.enviar("{\"tipo\":\"ping\"}");
        }
        for (int i = 0; i < 20; i++) {
            assertThat(dueno.siguiente()).contains("pong");
        }

        dueno.enviar("{\"tipo\":\"mensaje\",\"conversacionId\":\"c1\",\"texto\":\"uno de más\"}");

        assertThat(dueno.siguiente()).contains("demasiados_mensajes");
    }
}
