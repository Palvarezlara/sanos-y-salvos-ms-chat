package com.sanosysalvos.ms_chat.client;

import java.time.Duration;
import java.util.Optional;

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

    private final RestClient restClient;

    public MascotasClient(ChatProperties props, @Value("${internal.api-token:}") String tokenInterno) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(3));
        fabrica.setReadTimeout(Duration.ofSeconds(5));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.mascotasBaseUrl())
                .requestFactory(fabrica);
        if (tokenInterno != null && !tokenInterno.isBlank()) {
            builder.defaultHeader("X-Internal-Token", tokenInterno);
        }
        this.restClient = builder.build();
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
        } catch (RestClientException e) {
            throw new ServicioNoDisponibleException("No se pudo consultar ms-mascotas", e);
        }
    }
}
