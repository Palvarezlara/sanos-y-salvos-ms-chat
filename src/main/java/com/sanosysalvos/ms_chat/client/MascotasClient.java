package com.sanosysalvos.ms_chat.client;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.sanosysalvos.ms_chat.config.ChatProperties;
import com.sanosysalvos.ms_chat.dto.DuenoMascotaDto;
import com.sanosysalvos.ms_chat.exception.ServicioNoDisponibleException;

/**
 * Resuelve quién es el dueño de un reporte consultando el endpoint interno de
 * ms-mascotas, sin acceder a su base de datos (una BD por microservicio).
 *
 * ms-mascotas exige el secreto de servicio en X-Internal-Token (INTERNAL_API_TOKEN) en /internal/**.
 */
@Component
public class MascotasClient {

    private static final Logger log = LoggerFactory.getLogger(MascotasClient.class);

    private final RestClient restClient;

    public MascotasClient(ChatProperties props, @Value("${internal.api-token:}") String tokenInterno) {
        // Sin el secreto, ms-mascotas rechaza todo con 401 y parecia una caida del servicio: se falla al arrancar
        if (tokenInterno == null || tokenInterno.strip().length() < 32) {
            throw new IllegalStateException(
                    "INTERNAL_API_TOKEN no esta definido (minimo 32 caracteres): ms-mascotas rechazaria las consultas internas");
        }
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(3));
        fabrica.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(props.mascotasBaseUrl())
                .requestFactory(fabrica)
                .defaultHeader("X-Internal-Token", tokenInterno.strip())
                .build();
    }

    public Optional<DuenoMascotaDto> obtenerDueno(String mascotaId) {
        try {
            /* ms-mascotas sirve todo bajo /api/v1 (SPRING_MVC_SERVLET_PATH), igual que el cliente de ms-alertas */
            return Optional.ofNullable(restClient.get()
                    .uri("/api/v1/internal/mascotas/{id}/chat", mascotaId)
                    .retrieve()
                    .body(DuenoMascotaDto.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            // es configuracion (secreto distinto en ms-mascotas), no una caida: se deja claro en el log
            log.error("ms-mascotas rechazo la credencial de servicio (HTTP {}): revisar INTERNAL_API_TOKEN en ambos servicios",
                    e.getStatusCode().value());
            throw new ServicioNoDisponibleException("ms-mascotas rechazo la credencial de servicio", e);
        } catch (RestClientException e) {
            throw new ServicioNoDisponibleException("No se pudo consultar ms-mascotas", e);
        }
    }
}
