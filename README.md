# Idempotent Background Transaction Processing Service

A lightweight Java 17 + Spring Boot REST service implementing idempotent transaction processing, duplicate detection, deterministic ordering, retry simulation, validation, balances, status summaries, and unit tests.

## Requirements
- Java 17+
- Maven 3.9+

## Run
```bash
mvn test
mvn spring-boot:run
```
The service starts at `http://localhost:8080`.

## API
- `POST /api/transactions` — submit a JSON array for batch processing.
- `POST /api/transactions/single` — process one request.
- `GET /api/accounts/{accountId}/balance` — current account balance.
- `GET /api/accounts` — all balances.
- `GET /api/summary` — processed transaction counts by status.
- `POST /api/reset` — clear in-memory state (development/demo only).

Example:
```bash
curl -X POST http://localhost:8080/api/transactions \
  -H 'Content-Type: application/json' --data-binary @sample-input.json
```

## Design notes

### Idempotency
Two in-memory indexes are maintained: business `transactionId` and `idempotencyKey` (falling back to `requestId`). A replay is marked `DUPLICATE` before any balance mutation. This prevents repeated balance effects in the lifetime of the running process. For production, enforce unique constraints in a durable database and atomically commit the idempotency record, ledger entry, and balance update.

### Ordering strategy
Batch requests are sorted by `accountId`, then `sequenceNumber`, then `transactionId` before processing. A per-account sequence watermark prevents applying a later-arriving transaction whose sequence is less than or equal to the latest applied sequence; such a request is marked `PENDING`. This is a deliberately lightweight deterministic strategy, not a complete gap-buffering engine. A production implementation should persist expected sequence per account, buffer future sequences, alert on missing numbers, and define whether gaps block processing.

### Retry and failure handling
`TXN-2005` simulates a transient failure on attempt 1 and succeeds on attempt 2. The maximum attempt count is 2. Validation failures and insufficient-funds failures are isolated to their own records. The balance is changed only after validation and a successful business operation. Retry state is returned in the result message/status flow; retry scheduling/backoff would be implemented by a queue or scheduler in production.

### Transaction status lifecycle
The service uses `RECEIVED` (conceptual input state), `PROCESSING`, `RETRY_PENDING`, `PROCESSED`, `DUPLICATE`, `FAILED`, and `PENDING`. The in-memory demo returns the terminal status per result; durable lifecycle history is a production enhancement.

### Observability
The API returns a per-record result and `/api/summary` returns counts by status. Do not log sensitive account/customer data. Add correlation IDs, structured logs, metrics, tracing, and alerts for production.

### Concurrency and consistency
The demo serializes processing methods with `synchronized`, making the in-memory mutation sequence atomic within one application instance. This is not distributed locking and is not durable. In production use a database transaction, unique idempotency constraint, optimistic/pessimistic locking as appropriate, and an outbox for downstream side effects.

### AI-assisted development
AI assistance was used to scaffold the initial implementation, propose edge cases, and draft documentation. The implementation should be reviewed manually; automated tests are included for duplicate requests, ordering, invalid records, retries, and insufficient funds. The candidate should be prepared to explain each design choice and limitation.

## Known limitations / next steps
1. In-memory state is lost on restart and is single-instance only.
2. Sequence gaps are not buffered; `PENDING` records require operational/manual handling.
3. A production queue adapter, durable transaction store, retry scheduler with exponential backoff/jitter, dead-letter queue, metrics, tracing, authentication, and rate limiting are not included.
4. A stronger production design would store a ledger entry and idempotency key under a database transaction and use unique constraints to guarantee no duplicate effects across concurrent workers.
