package com.sanosysalvos.ms_chat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import com.sanosysalvos.ms_chat.exception.DemasiadasSolicitudesException;

/**
 * Ventana deslizante por clave (el sub del usuario), en memoria y de una sola instancia: con varias réplicas el
 * tope efectivo se multiplica. Los intentos rechazados no cuentan.
 */
public class LimitadorUso {

    /* tope de claves en memoria: si se llena se purgan las vencidas; si aun así no hay lugar, se rechaza lo nuevo */
    private static final int MAX_CLAVES = 50_000;

    private final int maximo;
    private final Duration ventana;
    private final Clock reloj;
    private final Map<String, Deque<Instant>> usos = new HashMap<>();

    public LimitadorUso(int maximo, Duration ventana, Clock reloj) {
        this.maximo = maximo;
        this.ventana = ventana;
        this.reloj = reloj;
    }

    /** Registra un uso o lanza DemasiadasSolicitudesException con los segundos hasta que se libere un cupo. */
    public synchronized void registrar(String clave) {
        Instant ahora = reloj.instant();
        if (usos.size() >= MAX_CLAVES && !usos.containsKey(clave)) {
            purgar(ahora);
            if (usos.size() >= MAX_CLAVES) {
                throw new DemasiadasSolicitudesException(ventana.toSeconds());
            }
        }
        Deque<Instant> usados = usos.computeIfAbsent(clave, k -> new ArrayDeque<>());
        while (!usados.isEmpty() && usados.peekFirst().plus(ventana).isBefore(ahora)) {
            usados.removeFirst();
        }
        if (usados.size() >= maximo) {
            throw new DemasiadasSolicitudesException(
                    Math.max(1, Duration.between(ahora, usados.peekFirst().plus(ventana)).toSeconds() + 1));
        }
        usados.addLast(ahora);
    }

    private void purgar(Instant ahora) {
        usos.values().removeIf(usados -> usados.isEmpty() || usados.peekLast().plus(ventana).isBefore(ahora));
    }
}
