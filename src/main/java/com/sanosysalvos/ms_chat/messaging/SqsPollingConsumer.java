package com.sanosysalvos.ms_chat.messaging;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * Un hilo propio por cola, con long polling. Si procesar() lanza una excepción el mensaje NO se borra: vuelve a
 * quedar visible y SQS lo manda a la cola de fallidos tras maxReceiveCount intentos. Sin URL de cola el
 * consumidor no arranca (cola opcional).
 */
public abstract class SqsPollingConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsPollingConsumer.class);
    private static final int VISIBILIDAD_SEGUNDOS = 120;

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final boolean autoArranque;

    private Thread hilo;
    private volatile boolean activo;
    private volatile boolean iniciado;
    private volatile Instant ultimoSondeoExitoso = Instant.EPOCH;

    protected SqsPollingConsumer(SqsClient sqsClient, String queueUrl, boolean autoArranque) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
        this.autoArranque = autoArranque;
    }

    @PostConstruct
    void iniciar() {
        if (!autoArranque) {
            return;
        }
        if (queueUrl == null || queueUrl.isBlank()) {
            log.warn("{} desactivado: no hay URL de cola configurada", getClass().getSimpleName());
            return;
        }
        activo = true;
        iniciado = true;
        ultimoSondeoExitoso = Instant.now();
        hilo = new Thread(this::escuchar, getClass().getSimpleName());
        hilo.setDaemon(true);
        hilo.start();
    }

    @PreDestroy
    void detener() {
        activo = false;
        if (hilo != null) {
            hilo.interrupt();
        }
    }

    private void escuchar() {
        while (activo) {
            try {
                var respuesta = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(5)
                        .waitTimeSeconds(10)
                        .visibilityTimeout(VISIBILIDAD_SEGUNDOS)
                        .build());
                ultimoSondeoExitoso = Instant.now();
                for (Message mensaje : respuesta.messages()) {
                    procesarYBorrar(mensaje);
                }
            } catch (Exception e) {
                if (activo) {
                    log.error("Error consultando la cola, reintenta en 5s", e);
                    dormir(5000);
                }
            } catch (Error e) {
                activo = false;
                log.error("Consumidor SQS detenido por un error fatal", e);
                throw e;
            }
        }
    }

    private void procesarYBorrar(Message mensaje) {
        try {
            procesar(mensaje.body());
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl).receiptHandle(mensaje.receiptHandle()).build());
        } catch (Exception e) {
            log.error("No se pudo procesar el mensaje, queda en la cola para reintento", e);
        }
    }

    private void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean estaIniciado() {
        return iniciado;
    }

    /** Hilo vivo y con un sondeo exitoso reciente. */
    public boolean estaSano(Duration tolerancia) {
        return hilo != null && hilo.isAlive() && ultimoSondeoExitoso.plus(tolerancia).isAfter(Instant.now());
    }

    public String nombre() {
        return getClass().getSimpleName();
    }

    protected abstract void procesar(String cuerpoMensaje) throws Exception;
}
