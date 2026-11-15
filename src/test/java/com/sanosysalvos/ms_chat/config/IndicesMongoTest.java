package com.sanosysalvos.ms_chat.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;

import com.sanosysalvos.ms_chat.model.Conversacion;
import com.sanosysalvos.ms_chat.model.Mensaje;

class IndicesMongoTest {

    private MongoTemplate mongo;
    private IndexOperations opsMensajes;
    private IndexOperations opsConversaciones;
    private IndicesMongo indices;

    @BeforeEach
    void preparar() {
        mongo = mock(MongoTemplate.class);
        opsMensajes = mock(IndexOperations.class);
        opsConversaciones = mock(IndexOperations.class);
        when(mongo.indexOps(Mensaje.class)).thenReturn(opsMensajes);
        when(mongo.indexOps(Conversacion.class)).thenReturn(opsConversaciones);
        ChatProperties props = new ChatProperties("x", List.of(), Duration.ofDays(90), "http://ms-mascotas:8082",
                1000, 20, 10, true, Duration.ofMinutes(30));
        indices = new IndicesMongo(mongo, props);
    }

    @Test
    void creaTodosLosIndices() {
        indices.run(null);

        verify(opsMensajes, times(2)).createIndex(any(Index.class));
        verify(opsConversaciones, times(4)).createIndex(any(Index.class));
    }

    @Test
    void siFallaElPrimerTtlLosDemasIndicesSeCreanIgual() {
        /* antes un solo try/catch: el fallo del TTL de mensajes dejaba sin crear la clave unica ni el otro TTL */
        when(opsMensajes.createIndex(any(Index.class))).thenThrow(new RuntimeException("IndexOptionsConflict")).thenReturn("ok");
        doThrow(new RuntimeException("no se pudo")).when(opsMensajes).dropIndex("ttl_enviado_en");

        indices.run(null);

        verify(opsConversaciones, times(4)).createIndex(any(Index.class));
        /* ttl (falla; el drop tambien falla) + conversacion_fecha */
        verify(opsMensajes, times(2)).createIndex(any(Index.class));
    }

    @Test
    void unTtlConOtroPlazoSeEliminaYSeRecreaConLaRetencionActual() {
        when(opsMensajes.createIndex(any(Index.class))).thenThrow(new RuntimeException("IndexOptionsConflict")).thenReturn("ok");

        indices.run(null);

        verify(opsMensajes).dropIndex("ttl_enviado_en");
        /* ttl (falla), ttl (recreado), conversacion_fecha */
        verify(opsMensajes, times(3)).createIndex(any(Index.class));
    }

    @Test
    void sinCrearIndicesNoTocaMongo() {
        ChatProperties props = new ChatProperties("x", List.of(), Duration.ofDays(90), "http://ms-mascotas:8082",
                1000, 20, 10, false, Duration.ofMinutes(30));

        new IndicesMongo(mongo, props).run(null);

        verify(mongo, never()).indexOps(any(Class.class));
        assertThat(props.crearIndices()).isFalse();
    }
}
