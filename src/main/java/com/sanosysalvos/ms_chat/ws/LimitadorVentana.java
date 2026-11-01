package com.sanosysalvos.ms_chat.ws;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/** Limita cuántos mensajes puede mandar una sesión dentro de una ventana de tiempo (ventana deslizante). */
class LimitadorVentana {

    private final int maximo;
    private final long ventanaMillis;
    private final Clock clock;
    private final Deque<Long> marcas = new ArrayDeque<>();

    LimitadorVentana(int maximo, Duration ventana, Clock clock) {
        this.maximo = maximo;
        this.ventanaMillis = ventana.toMillis();
        this.clock = clock;
    }

    synchronized boolean permitir() {
        long ahora = clock.millis();
        while (!marcas.isEmpty() && ahora - marcas.peekFirst() >= ventanaMillis) {
            marcas.pollFirst();
        }
        if (marcas.size() >= maximo) {
            return false;
        }
        marcas.addLast(ahora);
        return true;
    }
}
