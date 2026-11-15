package com.sanosysalvos.ms_chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import com.sanosysalvos.ms_chat.model.Conversacion;
import com.sanosysalvos.ms_chat.model.Mensaje;

/**
 * Crea los índices al arrancar, cada uno por separado: que falle uno no impide crear los demás. Los dos TTL son lo
 * que borra los mensajes (y las conversaciones sin actividad, que guardan los correos) pasado el plazo de
 * retención, sin tareas programadas: minimización de datos, Ley 21.719.
 *
 * Si se cambia chat.retencion con un índice TTL ya creado, Mongo rechaza el cambio: se elimina y se vuelve a
 * crear con el plazo nuevo. Si un TTL no se puede crear, se registra un ERROR claro: los datos no vencerían.
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
        IndexOperations mensajes = mongo.indexOps(Mensaje.class);
        IndexOperations conversaciones = mongo.indexOps(Conversacion.class);

        int fallidos = 0;
        fallidos += crearTtl(mensajes, new Index().on("enviadoEn", Sort.Direction.ASC).expire(props.retencion()).named("ttl_enviado_en"));
        fallidos += crear(mensajes, new Index().on("conversacionId", Sort.Direction.ASC).on("enviadoEn", Sort.Direction.DESC).named("conversacion_fecha"));
        fallidos += crear(conversaciones, new Index().on("clave", Sort.Direction.ASC).unique().named("uk_clave"));
        fallidos += crear(conversaciones, new Index().on("participantes", Sort.Direction.ASC).named("participantes"));
        fallidos += crear(conversaciones, new Index().on("mascotaId", Sort.Direction.ASC).named("mascota"));
        fallidos += crearTtl(conversaciones, new Index().on("ultimoMensajeEn", Sort.Direction.ASC).expire(props.retencion()).named("ttl_ultimo_mensaje"));

        if (fallidos == 0) {
            log.info("Indices de Mongo listos (retencion de mensajes: {})", props.retencion());
        } else {
            log.error("{} indice(s) de Mongo no se pudieron crear; revisar los errores anteriores", fallidos);
        }
    }

    /** 0 si quedó creado, 1 si falló. */
    private int crear(IndexOperations ops, Index indice) {
        String nombre = indice.getIndexOptions().get("name").toString();
        try {
            ops.createIndex(indice);
            return 0;
        } catch (RuntimeException e) {
            log.error("No se pudo crear el indice '{}': {}", nombre, e.getMessage());
            return 1;
        }
    }

    /* un TTL existente con otro plazo se elimina y se recrea; si aun así falla, los datos no vencerían: ERROR visible */
    private int crearTtl(IndexOperations ops, Index indice) {
        String nombre = indice.getIndexOptions().get("name").toString();
        try {
            ops.createIndex(indice);
            return 0;
        } catch (RuntimeException primero) {
            log.warn("El indice TTL '{}' ya existe con otras opciones; se recrea con la retencion actual ({})",
                    nombre, props.retencion());
            try {
                ops.dropIndex(nombre);
                ops.createIndex(indice);
                return 0;
            } catch (RuntimeException e) {
                log.error("NO se pudo crear el indice TTL '{}': los datos de chat NO vencerian solos (Ley 21.719). {}",
                        nombre, e.getMessage());
                return 1;
            }
        }
    }
}
