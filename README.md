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
- Solo se guarda el `sub` de cada participante: ni nombre ni correo. El frontend recibe `mio` (calculado por destinatario), nunca el sub del otro.
- Límites: 1000 caracteres por mensaje, 20 mensajes por 10 s por sesión, 5 conexiones simultáneas por usuario, frames de máx. 8 KB, orígenes restringidos.
- **Nunca se registra el contenido de los mensajes ni el ticket.**
- **Retención:** índices TTL en Mongo borran solos los mensajes (y las conversaciones sin actividad) a los `CHAT_RETENCION` (por defecto 90 días). Cambiar el plazo con los índices ya creados exige borrarlos a mano (o `collMod`).

## Límites conocidos

- Las sesiones viven en memoria: con más de una instancia, un mensaje solo llega a quienes estén en la misma instancia. Para escalar horizontalmente hace falta un bus que reparta los mensajes entre instancias (SNS/SQS o Kafka).
- No hay moderación de contenido ni aviso por correo cuando la otra persona está desconectada.
- El chat solo se abre sobre reportes aprobados (lo decide `ms-mascotas`).

## Correr en local

```bash
cp .env.example .env     # completar MONGODB_URI (…/sanos_chat), Cognito y CHAT_TICKET_SECRET
docker compose up --build
```

Requiere la red `sanos-y-salvos-network` (la crea el `config-server`) y que `ms-mascotas` y el BFF estén arriba. El BFF necesita el **mismo** `CHAT_TICKET_SECRET` y `CHAT_WS_URL`.

```bash
./mvnw test    # 33 pruebas, incluye un WebSocket real contra el servidor
```
