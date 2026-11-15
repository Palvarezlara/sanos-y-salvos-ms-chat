package com.sanosysalvos.ms_chat.config;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sanosysalvos.ms_chat.service.LimitadorUso;

/** Límites de uso del REST y ejecutor del aviso por correo. */
@Configuration
public class LimitesConfig {

    /** Peticiones REST por usuario y minuto (el WebSocket tiene su propio límite por sesión). */
    @Bean
    LimitadorUso limiteRest(@Value("${chat.limite-rest-por-minuto:60}") int maximo, Clock reloj) {
        return new LimitadorUso(maximo, Duration.ofMinutes(1), reloj);
    }

    /** Conversaciones nuevas por usuario cada 24 horas: sin tope, un script podía escribirle a todos los dueños. */
    @Bean
    LimitadorUso limiteConversaciones(@Value("${chat.limite-conversaciones-por-dia:20}") int maximo, Clock reloj) {
        return new LimitadorUso(maximo, Duration.ofHours(24), reloj);
    }

    /** Publicar en SNS no debe bloquear el hilo del WebSocket: una sola hebra con cola acotada. */
    @Bean(name = "avisosExecutor", destroyMethod = "shutdown")
    ThreadPoolExecutor avisosExecutor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200), r -> {
            Thread hilo = new Thread(r, "aviso-chat");
            hilo.setDaemon(true);
            return hilo;
        });
    }
}
