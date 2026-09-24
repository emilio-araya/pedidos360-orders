# ms-pedidos360-orders

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

## Oracle

```bash
export SPRING_PROFILES_ACTIVE=oracle
export ORDERS_DB_URL='jdbc:oracle:thin:@//host:1521/service'
export ORDERS_DB_USERNAME='...'
export ORDERS_DB_PASSWORD='...'
export ENTRA_ISSUER='https://login.microsoftonline.com/<tenant>/v2.0'
export ENTRA_API_AUDIENCE='150f51db-4084-4979-b1a1-e6a6e7893a01'
export CATALOG_SERVICE_URL='http://catalog:8082'
mvn spring-boot:run
```

Fuera de `local` el servicio exige issuer y audience de Entra; valida firma, `iss`, `aud`, `exp` y `nbf`.
El claim `roles` se convierte en autoridades `ROLE_*` y la identidad usa `oid`, con fallback a `sub`.

## API

- `GET /api/orders`: el personal ve todos; un cliente, solo los suyos.
- `GET /api/orders/{id}`: el personal ve cualquiera; un cliente, solo uno propio.
- `POST /api/orders`: crea con snapshot de nombre/precio del catálogo.
- `PUT /api/orders/{id}`: reemplaza datos únicamente mientras está `CREADO`.
- `DELETE /api/orders/{id}`: cancela únicamente desde `CREADO`.
- `PATCH /api/orders/{id}/status`: aplica la máquina de estados estricta.

Al aceptar se llama a `POST /internal/catalog/stock/reservations`; al cancelar stock reservado se llama a
`DELETE /internal/catalog/stock/reservations/{orderId}`. El Bearer entrante se propaga y `orderId` hace
idempotente la reserva. Un lock pesimista evita dos reservas concurrentes para el mismo pedido.

## Pruebas y contenedor

```bash
mvn test
docker build -t pedidos360-orders .
```

Las pruebas usan H2 aislado y un mock de `CatalogClient`; no requieren Docker ni el catálogo.
