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

Al aceptar, Orders propaga el mismo Bearer al namespace del proveedor: `/internal/catalog/stock/reservations` para Entra y `/aws/api/internal/catalog/stock/reservations` para Cognito. `orderId` hace
idempotente la reserva. Un lock pesimista evita dos reservas concurrentes para el mismo pedido.

Al cancelar se libera siempre, aunque el pedido tenga `stockReserved=false`: si el catálogo confirmó la reserva y
después falló el commit local, el flag quedó en `false` y esa reserva quedaría inmovilizada para siempre. El
`release` del catálogo es idempotente (responde `204` cuando no hay reserva), así que la llamada incondicional
cubre el caso huérfano. El coste asumido es que cancelar exige que el catálogo esté disponible; la reserva
idempotente por `orderId` permite reintentar sin duplicar stock.

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
