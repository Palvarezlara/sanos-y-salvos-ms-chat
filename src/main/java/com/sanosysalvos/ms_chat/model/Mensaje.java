package com.sanosysalvos.ms_chat.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Un mensaje de chat. Se borra solo al vencer el índice TTL sobre enviadoEn (ver IndicesMongo). */
@Document("mensajes")
public class Mensaje {

    @Id
    private String id;
    private String conversacionId;
    private String remitenteSub;
    private String texto;
    private Instant enviadoEn;

    protected Mensaje() {
    }

    public Mensaje(String conversacionId, String remitenteSub, String texto, Instant enviadoEn) {
        this.conversacionId = conversacionId;
        this.remitenteSub = remitenteSub;
        this.texto = texto;
        this.enviadoEn = enviadoEn;
    }

    public String getId() {
        return id;
    }

    public String getConversacionId() {
        return conversacionId;
    }

    public String getRemitenteSub() {
        return remitenteSub;
    }

    public String getTexto() {
        return texto;
    }

    public Instant getEnviadoEn() {
        return enviadoEn;
    }
}
