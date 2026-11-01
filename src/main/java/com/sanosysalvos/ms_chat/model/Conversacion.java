package com.sanosysalvos.ms_chat.model;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Una conversación entre el dueño de un reporte y una persona interesada.
 * Los participantes se identifican solo por su sub de Cognito; no se guarda
 * nombre ni correo (minimización de datos, Ley 21.719).
 */
@Document("conversaciones")
public class Conversacion {

    @Id
    private String id;
    /* mascotaId + los dos subs ordenados; con índice único evita conversaciones duplicadas */
    private String clave;
    private String mascotaId;
    private String mascotaNombre;
    private String duenoSub;
    private List<String> participantes;
    private Instant creadaEn;
    private Instant ultimoMensajeEn;

    protected Conversacion() {
    }

    public Conversacion(String mascotaId, String mascotaNombre, String duenoSub, String interesadoSub, Instant ahora) {
        this.mascotaId = mascotaId;
        this.mascotaNombre = mascotaNombre;
        this.duenoSub = duenoSub;
        this.participantes = List.of(duenoSub, interesadoSub);
        this.clave = claveDe(mascotaId, duenoSub, interesadoSub);
        this.creadaEn = ahora;
        this.ultimoMensajeEn = ahora;
    }

    public static String claveDe(String mascotaId, String subA, String subB) {
        String pareja = subA.compareTo(subB) <= 0 ? subA + ":" + subB : subB + ":" + subA;
        return mascotaId + ":" + pareja;
    }

    public boolean incluye(String sub) {
        return participantes != null && participantes.contains(sub);
    }

    public void registrarMensaje(Instant momento) {
        this.ultimoMensajeEn = momento;
    }

    public String getId() {
        return id;
    }

    public String getClave() {
        return clave;
    }

    public String getMascotaId() {
        return mascotaId;
    }

    public String getMascotaNombre() {
        return mascotaNombre;
    }

    public String getDuenoSub() {
        return duenoSub;
    }

    public List<String> getParticipantes() {
        return participantes;
    }

    public Instant getCreadaEn() {
        return creadaEn;
    }

    public Instant getUltimoMensajeEn() {
        return ultimoMensajeEn;
    }
}
