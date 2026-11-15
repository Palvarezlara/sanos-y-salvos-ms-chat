package com.sanosysalvos.ms_chat.exception;

/** Se superó un límite de uso (conversaciones nuevas por día o peticiones REST por minuto). */
public class DemasiadasSolicitudesException extends RuntimeException {

    private final long reintentarEnSegundos;

    public DemasiadasSolicitudesException(long reintentarEnSegundos) {
        super("Demasiadas solicitudes");
        this.reintentarEnSegundos = reintentarEnSegundos;
    }

    public long getReintentarEnSegundos() {
        return reintentarEnSegundos;
    }
}
