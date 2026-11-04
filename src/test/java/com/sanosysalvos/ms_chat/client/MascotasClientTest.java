package com.sanosysalvos.ms_chat.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sanosysalvos.ms_chat.config.ChatProperties;
import com.sanosysalvos.ms_chat.exception.ServicioNoDisponibleException;
import com.sun.net.httpserver.HttpServer;

/* Servidor HTTP real y local: se comprueba que se manda el secreto de servicio y cómo se traducen las respuestas */
class MascotasClientTest {

    private static final String TOKEN = "secreto-de-servicio-de-prueba-de-al-menos-32-caracteres";

    private HttpServer servidor;
    private int estado;
    private String cuerpo;
    private String metodoRecibido;
    private String rutaRecibida;
    private String tokenRecibido;

    @BeforeEach
    void levantar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", exchange -> {
            metodoRecibido = exchange.getRequestMethod();
            rutaRecibida = exchange.getRequestURI().getPath();
            tokenRecibido = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(estado, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        servidor.start();
    }

    @AfterEach
    void detener() {
        servidor.stop(0);
    }

    private ChatProperties props(String baseUrl) {
        return new ChatProperties("x", List.of(), Duration.ofDays(90), baseUrl, 1000, 20, 10, false, Duration.ofMinutes(30));
    }

    private MascotasClient cliente(String token) {
        return new MascotasClient(props("http://127.0.0.1:" + servidor.getAddress().getPort()), token);
    }

    @Test
    void consultaElDuenoConElSecretoDeServicio() {
        estado = 200;
        cuerpo = "{\"mascotaId\":\"m1\",\"nombreMascota\":\"Firu\",\"duenoSub\":\"sub-1\",\"duenoEmail\":\"d@example.com\"}";

        var dueno = cliente(TOKEN).obtenerDueno("m1");

        assertThat(dueno).isPresent();
        assertThat(dueno.get().duenoSub()).isEqualTo("sub-1");
        assertThat(dueno.get().duenoEmail()).isEqualTo("d@example.com");
        assertThat(metodoRecibido).isEqualTo("GET");
        assertThat(rutaRecibida).isEqualTo("/api/v1/internal/mascotas/m1/chat");
        assertThat(tokenRecibido).isEqualTo(TOKEN);
    }

    @Test
    void sinSecretoElServicioNoArranca() {
        /* sin el secreto ms-mascotas respondia 401 y parecia una caida: mejor fallar al arrancar y decir por que */
        for (String malo : new String[] { "", "   ", "corto" }) {
            assertThatThrownBy(() -> cliente(malo)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("INTERNAL_API_TOKEN");
        }
        assertThatThrownBy(() -> cliente(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unReporteInexistenteONoAprobadoDevuelveVacio() {
        estado = 404;
        cuerpo = "";

        assertThat(cliente(TOKEN).obtenerDueno("no-existe")).isEmpty();
    }

    @Test
    void unErrorDeMsMascotasSeTraduceAServicioNoDisponible() {
        estado = 500;
        cuerpo = "{}";

        assertThatThrownBy(() -> cliente(TOKEN).obtenerDueno("m1")).isInstanceOf(ServicioNoDisponibleException.class);
    }

    @Test
    void siLaCredencialEsRechazadaTambienSeTraduceAServicioNoDisponible() {
        /* 401 de ms-mascotas (secreto mal configurado): el chat no puede abrirse y no se muestra como "no encontrado" */
        estado = 401;
        cuerpo = "";

        assertThatThrownBy(() -> cliente("otro-secreto-de-servicio-igual-de-largo-que-el-real-123").obtenerDueno("m1")).isInstanceOf(ServicioNoDisponibleException.class);
    }
}
