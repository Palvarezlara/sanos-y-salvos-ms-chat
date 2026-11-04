package com.sanosysalvos.ms_chat.messaging;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** /actuator/health pasa a DOWN si un consumidor SQS iniciado murió o no puede leer su cola. */
@Component("consumidoresSqs")
public class ConsumidoresSqsHealthIndicator implements HealthIndicator {

    static final Duration TOLERANCIA = Duration.ofSeconds(90);

    private final List<SqsPollingConsumer> consumidores;

    public ConsumidoresSqsHealthIndicator(List<SqsPollingConsumer> consumidores) {
        this.consumidores = consumidores;
    }

    @Override
    public Health health() {
        List<String> caidos = consumidores.stream()
                .filter(SqsPollingConsumer::estaIniciado)
                .filter(c -> !c.estaSano(TOLERANCIA))
                .map(SqsPollingConsumer::nombre)
                .collect(Collectors.toList());
        return caidos.isEmpty() ? Health.up().build() : Health.down().withDetail("consumidoresCaidos", caidos).build();
    }
}
