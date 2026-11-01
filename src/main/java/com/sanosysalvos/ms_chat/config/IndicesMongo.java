package com.sanosysalvos.ms_chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

import com.sanosysalvos.ms_chat.model.Conversacion;
import com.sanosysalvos.ms_chat.model.Mensaje;

/**
 * Crea los índices al arrancar. Los dos TTL son lo que borra los mensajes (y las
 * conversaciones sin actividad) pasado el plazo de retención, sin tareas
 * programadas: minimización de datos, Ley 21.719.
 *
 * Si se cambia chat.retencion con los índices ya creados, Mongo rechaza el cambio:
 * hay que eliminar el índice TTL a mano (o usar collMod) y reiniciar.
 */
@Component
public class IndicesMongo implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IndicesMongo.class);

    private final MongoTemplate mongo;
    private final ChatProperties props;

    public IndicesMongo(MongoTemplate mongo, ChatProperties props) {
        this.mongo = mongo;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.crearIndices()) {
            return;
        }
        try {
            mongo.indexOps(Mensaje.class).createIndex(
                    new Index().on("enviadoEn", Sort.Direction.ASC).expire(props.retencion()).named("ttl_enviado_en"));
            mongo.indexOps(Mensaje.class).createIndex(
                    new Index().on("conversacionId", Sort.Direction.ASC).on("enviadoEn", Sort.Direction.DESC).named("conversacion_fecha"));
            mongo.indexOps(Conversacion.class).createIndex(
                    new Index().on("clave", Sort.Direction.ASC).unique().named("uk_clave"));
            mongo.indexOps(Conversacion.class).createIndex(
                    new Index().on("participantes", Sort.Direction.ASC).named("participantes"));
            mongo.indexOps(Conversacion.class).createIndex(
                    new Index().on("ultimoMensajeEn", Sort.Direction.ASC).expire(props.retencion()).named("ttl_ultimo_mensaje"));
            log.info("Indices de Mongo listos (retencion de mensajes: {})", props.retencion());
        } catch (Exception e) {
            log.error("No se pudieron crear los indices de Mongo; el chat funciona pero sin vencimiento automatico", e);
        }
    }
}
