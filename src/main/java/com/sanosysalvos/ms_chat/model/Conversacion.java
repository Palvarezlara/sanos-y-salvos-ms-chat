package com.sanosysalvos.ms_chat.model;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Una conversación entre el dueño de un reporte y una persona interesada.
 * Los participantes se identifican por su sub de Cognito; no se guarda nombre. Los correos se
 * guardan únicamente para avisar de mensajes nuevos: nunca se devuelven al frontend y se borran
 * con la conversación (índice TTL), igual que los mensajes (minimización de datos, Ley 21.719).
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
    /* solo para avisos por correo; ver comentario de la clase */
    private String emailDueno;
    private String emailInteresado;
    private Instant ultimoAvisoDueno;
    private Instant ultimoAvisoInteresado;
    /* sub de quien bloqueó: la conversación queda sin mensajes ni correos y la otra persona no puede reabrirla */
    private String bloqueadaPor;

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

    /** Guarda los correos que lleguen (no nulos y distintos de los actuales); true si cambió algo. */
    public boolean registrarCorreos(String nuevoEmailDueno, String nuevoEmailInteresado) {
        boolean cambio = false;
        if (nuevoEmailDueno != null && !nuevoEmailDueno.equals(this.emailDueno)) {
            this.emailDueno = nuevoEmailDueno;
            cambio = true;
        }
        if (nuevoEmailInteresado != null && !nuevoEmailInteresado.equals(this.emailInteresado)) {
            this.emailInteresado = nuevoEmailInteresado;
            cambio = true;
        }
        return cambio;
    }

    /** Correo de ese participante, o null si no se conoce. */
    public String emailDe(String sub) {
        if (sub == null) {
            return null;
        }
        return sub.equals(duenoSub) ? emailDueno : emailInteresado;
    }

    /** true si pasó al menos "intervalo" desde el último aviso a esa persona (o nunca se le avisó). */
    public boolean puedeAvisar(String sub, Instant ahora, java.time.Duration intervalo) {
        Instant ultimo = sub.equals(duenoSub) ? ultimoAvisoDueno : ultimoAvisoInteresado;
        return ultimo == null || !ultimo.plus(intervalo).isAfter(ahora);
    }

    public void registrarAviso(String sub, Instant ahora) {
        if (sub.equals(duenoSub)) {
            this.ultimoAvisoDueno = ahora;
        } else {
            this.ultimoAvisoInteresado = ahora;
        }
    }

    public boolean estaBloqueada() {
        return bloqueadaPor != null;
    }

    /** Deja solo la marca: borra los correos y los avisos; los mensajes los borra el servicio. */
    public void bloquear(String sub) {
        this.bloqueadaPor = sub;
        this.emailDueno = null;
        this.emailInteresado = null;
        this.ultimoAvisoDueno = null;
        this.ultimoAvisoInteresado = null;
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

    public String getBloqueadaPor() {
        return bloqueadaPor;
    }

    public Instant getUltimoMensajeEn() {
        return ultimoMensajeEn;
    }
}
