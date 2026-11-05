# ms-pedidos360-orders

[![CI](https://github.com/emilio-araya/pedidos360-orders/actions/workflows/ci.yml/badge.svg)](https://github.com/emilio-araya/pedidos360-orders/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.7-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Oracle](https://img.shields.io/badge/Oracle-F80000?logo=oracle&logoColor=white)](https://www.oracle.com/database/)

Microservicio Java 17 / Spring Boot 3.5 para administración de pedidos de Pedidos360.

## Ejecutar localmente (H2)

```bash
SPRING_PROFILES_ACTIVE=local mvn spring-boot:run
```

El perfil `local` usa una base H2 en memoria aislada, aplica Flyway y valida el esquema Hibernate. La clave HMAC
(al menos 32 bytes) se configura únicamente con `LOCAL_JWT_HMAC_SECRET`. Ese mecanismo no se activa en
Oracle/cloud.

Los eventos de pedido se publican en RabbitMQ: `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`,
`RABBITMQ_PASSWORD` y `RABBITMQ_VHOST` (por defecto `localhost:5672`, `guest`, vhost `/`). Sin broker la API responde,
`/actuator/health` informa `DOWN` y los eventos esperan en la outbox. `mvn test` no necesita broker.
Los eventos de dominio se publican además en Kafka (`SPRING_KAFKA_BOOTSTRAP_SERVERS`, por defecto `localhost:9092`); ver
*Eventos de dominio en Kafka*.

Con el clúster de dos nodos de `pedidos360-infra`, el compose define además
`SPRING_RABBITMQ_ADDRESSES=rabbitmq-1:5672,rabbitmq-2:5672`: tiene prioridad sobre `RABBITMQ_HOST`/`RABBITMQ_PORT` y, si
un nodo cae, la conexión se reabre contra el otro. Las colas de este servicio son `classic` y viven en un solo nodo: si
ese nodo cae, el consumo se detiene hasta que vuelve y los eventos esperan en la outbox.

Variables locales:

- `LOCAL_JWT_HMAC_SECRET`
- `LOCAL_JWT_ISSUER` (predeterminado `https://login.microsoftonline.com/pedidos360-local/v2.0`)
- `LOCAL_JWT_AUDIENCE` (predeterminado `api://150f51db-4084-4979-b1a1-e6a6e7893a01`)
- `LOCAL_JWT_HMAC_SECRET` (debe coincidir con BFF y catálogo)
- `CATALOG_SERVICE_URL` (por defecto `http://localhost:8082`)
- `CATALOG_CONNECT_TIMEOUT_MS` y `CATALOG_READ_TIMEOUT_MS` (ver *Timeouts hacia el catálogo*)

## Paginación

`GET /api/orders` pagina en la base, no en memoria. La consulta de pedidos **no** usa `@EntityGraph` sobre
`items`: paginar un fetch de colección hace que Hibernate aplique el límite en memoria (`HHH90003004`) y
traiga la tabla completa. Los items se cargan con `@BatchSize(25)`, así una página de 20 pedidos resuelve
tres consultas: la página (`fetch first ? rows only`), el conteo y un `in (...)` para los items.

La respuesta usa un envelope propio (`content`, `page`, `size`, `totalElements`, `totalPages`) en lugar de
serializar `Page` de Spring, cuyo JSON es un detalle de implementación.

## Trazabilidad

Cada request lleva un `X-Request-Id`. Si el cliente lo envía y cumple `^[A-Za-z0-9._-]{1,64}$` se respeta; si
falta o no cumple se genera un UUID. El id se devuelve en la respuesta, se imprime en cada línea de log y se
propaga al catálogo en cada llamada HTTP, así una operación se sigue de punta a punta entre BFF, orders y
catalog. Un valor con saltos de línea se descarta a propósito: permitirlo habilitaría inyectar entradas falsas
en el log.

## Timeouts hacia el catálogo

La llamada HTTP a `catalog` tiene timeout de conexión (2000 ms) y de lectura (10000 ms). Sin el de lectura,
una respuesta que nunca llega retiene el hilo del request de forma indefinida: unas pocas llamadas colgadas
agotan el pool de Tomcat y el servicio deja de responder, incluido `/actuator/health`.

| Propiedad | Variable | Predeterminado |
|---|---|---|
| `app.catalog.connect-timeout-ms` | `CATALOG_CONNECT_TIMEOUT_MS` | `2000` |
| `app.catalog.read-timeout-ms` | `CATALOG_READ_TIMEOUT_MS` | `10000` |

Un valor no positivo falla al arrancar: en el cliente JDK, `0` significa "sin timeout" y reactivaría el
cuelgue que esta configuración evita. Los valores deben quedar por debajo del timeout del balanceador y del
`deployment circuit breaker` para que la falla se detecte antes de que el tráfico se acumule.

## Oracle

```bash
export SPRING_PROFILES_ACTIVE=oracle
export ORDERS_DB_URL='jdbc:oracle:thin:@//host:1521/service'
export ORDERS_DB_USERNAME='...'
export ORDERS_DB_PASSWORD='...'
export ENTRA_ISSUER='https://login.microsoftonline.com/<tenant>/v2.0'
export ENTRA_API_AUDIENCE='<entra-api-client-id>'
export COGNITO_ISSUER='https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example'
export COGNITO_API_AUDIENCE='<cognito-app-client-id>'
export COGNITO_JWK_SET_URI='https://cognito-idp.us-east-1.amazonaws.com/us-east-1_example/.well-known/jwks.json'
export CATALOG_SERVICE_URL='http://catalog:8082'
mvn spring-boot:run
```

Fuera de `local` el servicio usa dos cadenas: Entra para `/api/**` y Cognito para `/aws/api/**`. Valida firma, issuer, audience/client ID, `exp`, `nbf` y `token_use=access` para Cognito. El claim `roles` de Entra o `cognito:groups` se convierte en autoridades `ROLE_*`; la identidad usa `oid` para Entra y `sub` para Cognito.

## API

- `/api/orders/**` y `/aws/api/orders/**`: el personal ve todos; un cliente, solo los suyos.
- `GET /api/orders?page=0&size=20`: lista paginada. Devuelve `content`, `page`, `size`, `totalElements` y
  `totalPages`; orden fijo por `createdAt` descendente. `page` debe ser `>= 0` y `size` debe estar entre `1` y
  `100`: fuera de rango responde `400`, no recorta en silencio.
- `GET /api/orders/{id}`: el personal ve cualquiera; un cliente, solo uno propio.
- `POST /api/orders`: crea con snapshot de nombre/precio del catálogo.
- `PUT /api/orders/{id}`: reemplaza datos únicamente mientras está `CREADO`.
- `DELETE /api/orders/{id}`: cancela únicamente desde `CREADO`.
- `PATCH /api/orders/{id}/status`: aplica la máquina de estados estricta.
- En los pedidos de invitado, `OrderResponse` incluye `customerEmail`; en los pedidos con inicio de sesión se omite.

### Compra como invitado

El cliente compra sin iniciar sesión y deja solo su correo. Estas rutas existen únicamente bajo `/api` (no bajo
`/aws/api`) y no requieren `Authorization`:

- `POST /api/guest-orders`: cuerpo `{"email", "items": [{"productId", "quantity"}], "notes"?}`. El correo es
  obligatorio, con formato válido y hasta 254 caracteres; se guarda recortado y en minúsculas. Los ítems y precios
  siguen las mismas reglas que `POST /api/orders`. Responde `201` con `GuestOrderResponse`, que incluye
  `trackingToken` solo en esta respuesta.
- `GET /api/guest-orders/{token}`: devuelve `orderNumber`, `status`, `items`, `total`, `notes`,
  `cancellationReason`, `maskedEmail` (por ejemplo `l***@duocuc.cl`), `createdAt` y `updatedAt`. Nunca expone el
  `id` interno, `customerId`, `stockReserved` ni el correo completo.
- `POST /api/guest-orders/{token}/cancel`: cancela solo desde `CREADO` y escribe `pedido.cancelado` en la outbox;
  en otro estado responde `409`.
- `POST /api/guest-orders/link-requests`: cuerpo `{"email", "orderNumber"}`. Responde siempre `202` sin cuerpo,
  exista o no el pedido, para no revelar qué correos compraron. Si coinciden, rota el token (el enlace anterior deja
  de funcionar) y reenvía el correo; si el cuerpo no es válido, `400`.

El token son 256 bits de `SecureRandom` en 64 caracteres hexadecimales en minúscula. La base guarda solo su
SHA-256 (`orders.guest_token_hash`, único); el token en claro no se guarda, no se publica en RabbitMQ y no se
registra en los logs. Un token con formato inválido o desconocido responde el mismo `404`. El dueño de un pedido
de invitado es `guest:<id del pedido>`, que nunca coincide con un `oid` o `sub`, por lo que ningún cliente con
sesión puede verlo; el personal lo ve en su listado.

El voucher y el enlace se envían por SMTP después del commit, en un executor acotado: si el correo falla, el pedido
igual queda creado y la respuesta no cambia. Se configura solo por variables de entorno: `MAIL_HOST`, `MAIL_PORT`,
`MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, `MAIL_AUTH`, `MAIL_STARTTLS` y `FRONTEND_PUBLIC_URL`. En local,
`pedidos360-infra` levanta Mailpit (SMTP 1025, interfaz en `http://127.0.0.1:8025`); en AWS, las mismas variables
apuntan al SMTP de Amazon SES. Sin `MAIL_HOST` el correo solo se registra en el log, sin enlace. La suite no abre
conexiones SMTP.

Para armar el pedido, orders consulta el producto en el catálogo (`GET /api/catalog/products/{id}`, ruta pública)
sin `Authorization` cuando la request no trae JWT; si lo trae, lo reenvía como siempre.

### Reserva de stock asíncrona

Aceptar y cancelar no llaman al catálogo: escriben el cambio de estado y un evento en `outbox_event`, en la misma
transacción. `OutboxPublisher` publica los eventos pendientes en RabbitMQ con confirmación del broker, y el catálogo
responde con `stock.reservado` o `stock.reserva-fallida`.

- Al aceptar, el pedido queda `ACEPTADO` con `stockReserved=false` hasta que llega la confirmación.
- `ACEPTADO → EN_PREPARACION` responde `409` mientras el stock no esté confirmado.
- Si el catálogo rechaza la reserva, el pedido pasa a `CANCELADO` y `cancellationReason` guarda el motivo.
- Cancelar siempre emite `pedido.cancelado`. Si llega una confirmación para un pedido ya cancelado, se emite
  `pedido.reserva-huerfana` una sola vez para que el catálogo libere.
- Aceptar y cancelar ya no dependen de que el catálogo esté disponible.

El contrato de eventos, los reintentos con backoff y la DLQ están en
`pedidos360-infra/docs/MENSAJERIA_RABBITMQ.md`.

### Eventos de dominio en Kafka

Kafka convive con RabbitMQ: RabbitMQ sigue llevando los comandos de reserva de stock (sin cambios) y Kafka publica los
eventos de dominio del pedido en el topic `pedidos360.order-events`, que el catálogo consume para su proyección de
ventas por producto.

- Cada cambio de estado escribe una fila en `kafka_outbox_event` (migración `V5`) en la misma transacción: creación
  (`ORDER_CREATED`, también de invitados), edición (`ORDER_UPDATED`) y cada transición (`ORDER_ACCEPTED`,
  `ORDER_IN_PREPARATION`, `ORDER_DISPATCHED`, `ORDER_DELIVERED`, `ORDER_CANCELLED`, incluida la cancelación por
  rechazo de stock). Confirmar la reserva no cambia el estado y no emite evento.
- `KafkaOutboxPublisher` publica los pendientes por `created_at`, espera la confirmación del broker antes de marcarlos
  publicados y corta el lote ante el primer fallo. El productor usa `acks=all` e idempotencia; la clave del registro es
  el `orderId` (orden garantizado por pedido) y el valor es el JSON ya guardado en la outbox.
- Valor JSON (`schemaVersion` 1): `eventId`, `eventType`, `schemaVersion`, `orderId`, `status`, `previousStatus`
  (`null` sólo en `ORDER_CREATED`), `items` (`productId`, `quantity`) y `occurredAt` (ISO-8601 UTC).
- Garantía at-least-once: un reenvío lleva el mismo `eventId` y el consumidor lo descarta.

Configuración:

- `SPRING_KAFKA_BOOTSTRAP_SERVERS` (`spring.kafka.bootstrap-servers`, por defecto `localhost:9092`)
- `KAFKA_OUTBOX_PUBLISHER_ENABLED` (`app.kafka.outbox.publisher-enabled`, por defecto `true`; los tests lo desactivan)
- `KAFKA_OUTBOX_PUBLISHER_DELAY_MS` (por defecto `2000`) y `KAFKA_OUTBOX_SEND_TIMEOUT_MS` (por defecto `10000`)
- `KAFKA_PRODUCER_MAX_BLOCK_MS` (por defecto `10000`): tope de espera de `send()` cuando no hay broker

Sin broker la API responde y los eventos esperan en `kafka_outbox_event`. `mvn test` no necesita Kafka. El servicio no
crea el topic: lo crea `pedidos360-infra` (3 particiones). El contrato y cómo verificarlo están en
`pedidos360-infra/docs/MENSAJERIA_KAFKA.md`.

## Pruebas y contenedor

```bash
mvn verify
docker build -t pedidos360-orders .
```

Las pruebas usan H2 aislado y un mock de `CatalogClient`; no requieren Docker ni el catálogo.

| Métrica | Valor |
|---|---|
| Pruebas | 49 |
| Cobertura de líneas | 83.5% |
| Cobertura de instrucciones | 82.1% |

La CI ejecuta `mvn verify` en cada push y pull request, muestra el resumen en la página del workflow y adjunta el informe HTML de JaCoCo como artefacto. La cobertura de instrucciones es un umbral: el build falla si baja del 70%.
