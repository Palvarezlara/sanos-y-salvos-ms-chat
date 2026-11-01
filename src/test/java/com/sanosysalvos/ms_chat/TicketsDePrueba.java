package com.sanosysalvos.ms_chat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Arma tickets con el mismo formato que el BFF, para probar el validador y el WebSocket. */
public final class TicketsDePrueba {

    public static final String SECRETO = "secreto-de-prueba-de-al-menos-32-caracteres!!";

    private TicketsDePrueba() {
    }

    public static String valido(String sub, String jti) throws JOSEException {
        return firmar(SECRETO, sub, jti, Instant.now().plusSeconds(30), "ms-chat");
    }

    public static String firmar(String secreto, String sub, String jti, Instant vence, String audiencia)
            throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .jwtID(jti)
                .audience(audiencia)
                .issueTime(new Date())
                .expirationTime(Date.from(vence))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secreto.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
