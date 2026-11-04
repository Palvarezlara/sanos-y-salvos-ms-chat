# ms-chat

Chat entre usuarios de **Sanos y Salvos**: la persona que encontró (o cree haber visto) una mascota conversa en tiempo real con el dueño del reporte, sin exponerse correos ni teléfonos. Spring Boot 4 · Java 17 · WebSocket · MongoDB.

## Cómo encaja en la arquitectura

```
Navegador ──(1) POST /api/v1/chat/ticket──► BFF   valida la cookie y firma un ticket de ~30 s con el sub
Navegador ──(2) ws://…/api/v1/ws/chat?ticket=…──► ms-chat   valida el ticket y abre el canal
Navegador ──(3) REST /api/v1/chat/** (vía BFF, con JWT)────► ms-chat   historial y conversaciones
ms-chat ──► ms-mascotas  GET /internal/mascotas/{id}/chat   a quién pertenece el reporte
```

**Por qué el WebSocket no pasa por el BFF ni por API Gateway:** el gateway del proyecto es un *HTTP API* y el BFF reenvía con `RestClient` (petición/respuesta); ninguno de los dos mantiene un canal WebSocket. Tampoco se puede mandar la cookie de sesión a otro origen, de ahí el ticket.

## API REST (todo con JWT de Cognito, el sub sale siempre del token)

| Método | Ruta | Qué hace |
|---|---|---|
| `POST` | `/chat/conversaciones` `{ "mascotaId": "…" }` | Inicia (o recupera) la conversación con el dueño del reporte |
| `GET` | `/chat/conversaciones` | Mis conversaciones, la más reciente primero |
| `GET` | `/chat/conversaciones/{id}/mensajes?antes=&limite=` | Historial en orden cronológico (máx. 100) |
| `DELETE` | `/chat/conversaciones/{id}?bloquear=false` | Borra la conversación (mensajes y correos). Con `bloquear=true` queda una marca sin datos y la otra persona ya no puede escribir ni reabrirla. |

Límites de uso por usuario: 60 peticiones REST por minuto y 20 conversaciones nuevas por día (responden `429` con `Retry-After`; configurables con `CHAT_LIMITE_REST_POR_MINUTO` y `CHAT_LIMITE_CONVERSACIONES_POR_DIA`).

Quien no participa recibe `404` (no `403`) para no revelar que la conversación existe.

## Protocolo WebSocket (`/ws/chat?ticket=…`, JSON de texto)

```
cliente  → { "tipo": "mensaje", "conversacionId": "…", "texto": "…" }
cliente  → { "tipo": "ping" }
servidor → { "tipo": "mensaje", "mensaje": { id, conversacionId, mio, texto, enviadoEn } }   a los dos participantes
servidor → { "tipo": "error", "codigo": "…", "mensaje": "…" }
servidor → { "tipo": "pong" }
```

Códigos de error: `no_encontrada`, `solicitud_invalida`, `formato_invalido`, `tipo_desconocido`, `mensaje_muy_grande`, `demasiados_mensajes`, `error_interno`.

## Seguridad y privacidad

- Ticket: JWT HS256, audiencia `ms-chat`, ~30 s, **de un solo uso** (`jti`). Sin `CHAT_TICKET_SECRET` (o con menos de 32 caracteres) se rechaza toda conexión.
- Se guarda el `sub` de cada participante y, solo para los avisos por correo, su correo (ver más abajo): nunca el nombre. El frontend recibe `mio` (calculado por destinatario), nunca el sub del otro ni ningún correo.
- **`X-User-Email` solo se acepta con el secreto de servicio** (`X-Internal-Token` = `INTERNAL_API_TOKEN`, que el BFF manda junto con la identidad que validó). Sin él se ignora: llamando directo a este servicio cualquiera podía poner el correo de otra persona y hacer que la cuenta oficial le escribiera. El servicio no arranca sin `INTERNAL_API_TOKEN` (mín. 32 caracteres).
- **El WebSocket se cierra a los 30 min** (`CHAT_WS_VIDA_MAXIMA_MINUTOS`, código `4001`) y si no llega ningún marco, ni ping, en 2 min: el ticket solo autoriza el apretón de manos, así que tras un logout o una cuenta deshabilitada el cliente no puede reconectar. El frontend pide un ticket nuevo y reconecta solo.
- **Borrado de datos:** cualquiera de los dos participantes puede borrar la conversación, y al eliminarse un reporte en ms-mascotas (`mascota.eliminada`, cola SQS opcional `SQS_COLA_MASCOTA_ELIMINADA_URL`) se borran sus conversaciones, mensajes y correos.
- Límites: 1000 caracteres por mensaje, 20 mensajes por 10 s por sesión, 5 conexiones simultáneas por usuario, frames de máx. 8 KB, orígenes restringidos.
- **Nunca se registra el contenido de los mensajes ni el ticket.**
- **Retención:** índices TTL en Mongo borran solos los mensajes (y las conversaciones sin actividad) a los `CHAT_RETENCION` (por defecto 90 días). Cambiar el plazo con los índices ya creados exige borrarlos a mano (o `collMod`).

## Aviso por correo de mensajes nuevos

Si alguien recibe un mensaje y **no tiene el chat abierto** (ninguna conexión WebSocket), ms-chat publica el evento `chat.mensaje.nuevo` en el topic SNS `sanos-eventos`; **ms-alertas** lo consume por SQS y envía el correo ("Tienes un mensaje nuevo sobre *X*" + enlace al chat).

- El correo **no incluye el texto del mensaje**, solo el nombre de la mascota y el enlace.
- Como máximo **un aviso cada `CHAT_AVISO_INTERVALO`** (30 min por defecto) por conversación y persona, decidido con una actualización atómica en Mongo: dos mensajes simultáneos no pueden avisar los dos.
- Se publica en SNS en otro hilo y con timeout corto (2 s por intento, 5 s en total): un SNS lento no frena el chat.
- Si SNS falla, el chat sigue funcionando (se registra una advertencia y se libera el sello para reintentar con el próximo mensaje).
- **Datos:** para poder avisar se guardan los correos de los dos participantes dentro de la conversación (el del dueño viene de ms-mascotas, el de la otra persona del `id_token` validado por el BFF en el header `X-User-Email`). Nunca se devuelven al frontend y se borran junto con la conversación (índice TTL).

## Límites conocidos

- **Una sola instancia, a propósito:** las sesiones y los límites de uso viven en memoria. Con más de una instancia un mensaje solo llega a quienes estén en la misma, y quien está conectado a la otra cuenta como desconectado (recibiría un correo por un mensaje que ya vio). Para escalar hace falta un bus o Redis compartido (SNS/SQS o Kafka).
- No hay moderación ni reporte de contenido; quien recibe mensajes no deseados puede borrar y bloquear la conversación.
- El ticket del WebSocket viaja en la query string: el ALB y CloudFront no deben registrar la query string de `/ws/chat`.
- El chat solo se abre sobre reportes aprobados (lo decide `ms-mascotas`).

## Correr en local

```bash
cp .env.example .env     # completar MONGODB_URI (…/sanos_chat), Cognito y CHAT_TICKET_SECRET
docker compose up --build
```

Requiere la red `sanos-y-salvos-network` (la crea el `config-server`) y que `ms-mascotas` y el BFF estén arriba. El BFF necesita el **mismo** `CHAT_TICKET_SECRET` y `CHAT_WS_URL`.

```bash
./mvnw test    # 99 pruebas, incluye un WebSocket real y las consultas atómicas contra un Mongo real
```

Las pruebas de `ConversacionRepositoryMongoTest` usan la base `sanos_chat_test` de un Mongo en `localhost:27017` (p. ej. el contenedor `mongo-local`); si no hay uno, se omiten.

En local, la cola `cola-chat-mascota-eliminada` (consumidor de bajas) la crea el `init-sqs-sns.sh` de LocalStack de ms-alertas: reinicia `ms-alertas-localstack` para que aparezca.
