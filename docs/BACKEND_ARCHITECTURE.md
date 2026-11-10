# SUTRA — Target Microservice Backend (design for approval)

*9 November 2026 · Status: **approved**; phase 1 done, phases 2–7 to do (see section 9)*

This design combines two sources:

- **SUTRA's own decisions:** four roles, Groq reading, one manager awards, in-app badges, schema-per-service with cross-schema foreign keys.
- **The structure of the VQMS reference backend** (read from `Downloads/vendor-quotation-management-system-main`, not run). What we take from it:
  - one package layout repeated in every service;
  - one service per pipeline stage;
  - a correlation id on every request;
  - an outbox plus inbox for events;
  - a tamper-evident audit chain.

It deliberately does **not** copy VQMS's known problems. Each one is listed in section 8.

## 1. Why change

Today SUTRA has a gateway and four services. Three of them are small. `procurement-service` alone holds 124 files: RFQs, quotations, file storage, AI reading, validation, comparison, approval, dashboard, badges, the PDF, and the assistant. Nothing about the code shows where one stage ends and the next begins.

The target gives **every pipeline stage its own service**, each with **the same internal layout**.

## 2. Services

```
Browser (React :5173)
   │ /api/**
   ▼
gateway :8080 ── JWT check · CORS (only here) · rate limit · X-Correlation-Id · path routing
   │
   ├─ identity-service    :8081  users, roles, sign-in, vendor sign-ups                 schema identity
   ├─ masterdata-service  :8082  entities, sub-entities, customers, vendors              schema masterdata
   ├─ rfq-service         :8083  purchase requests + items, RFQ drafts                  schema rfq
   ├─ quotation-service   :8084  quotations, lines, flags, vendor portal, validation    schema quotation
   ├─ document-service    :8085  file upload/download, content checks, BYTEA storage    schema document
   ├─ extraction-service  :8086  file → structured quotation (OCR, Groq, rules, match)  schema extraction
   ├─ comparison-service  :8087  versioned comparison reports, ranking, vendor grade     schema comparison
   ├─ approval-service    :8088  award / reject decisions                               schema approval
   ├─ reporting-service   :8089  dashboard, menu badges, award summary PDF, assistant   (no tables, reads APIs)
   └─ audit-service       :8090  hash-chained audit log + verify                        schema audit
        helpers: Python document reader :8000 · Tesseract OCR · Groq API (used by extraction-service only)
One PostgreSQL 18 database, sutra_db, one schema per service, Flyway migrations inside each service.
```

| Service | Moves from today | Owns (tables) | Main endpoints |
| --- | --- | --- | --- |
| gateway | gateway | — | routes only |
| identity | auth-service (renamed) | app_user, role, user_role, vendor_signup | `/api/auth/**`, `/api/users/**` |
| masterdata | masterdata-service | business_entity, sub_entity, customer, vendor | `/api/entities/**`, `/api/customers/**`, `/api/vendors/**`, `/api/vendor-profile` |
| rfq | procurement: PurchaseRequest*, RfqDraftService | purchase_request, request_item | `/api/purchase-requests/**` (without quotations, comparisons, approvals) |
| quotation | procurement: Quotation*, VendorPortal*, calculation/*, QuotationReviewService | quotation, quotation_item, quotation_flag | `/api/quotations/**`, `/api/vendor-portal/**` |
| document | procurement: QuotationFile, QuotationFileValidator | document (BYTEA, sha256, owner type and id) | `/api/documents/**` |
| extraction | procurement: ai/* (Groq, rules, OCR, matcher, ExtractionEngines) | extraction_job (status, engine, error, timings) | `/api/extractions/**` (202 + status URL) |
| comparison | procurement: ComparisonService, ComparisonEngine, VendorGrader | comparison_report, comparison_quotation | `/api/comparisons/**` |
| approval | procurement: ApprovalService | approval | `/api/approvals/**` |
| reporting | procurement: Dashboard, Badge, AwardSummary, Assistant | — | `/api/dashboard/**`, `/api/reports/**`, `/api/assistant/**` |
| audit | audit-service | audit_log (+ previous_hash, hash), audit_chain_head | `/api/audit-logs/**`, `/api/audit-logs/verify` |

**Why not more services:**

- **Validation stays inside `quotation-service`.** It is an internal engine, not a service, because it owns no data. Its flags belong to the quotation, and it runs on every line edit, so a network hop would slow down every correction.
- **No recommendation or notification service** (your decision). The advisory grade belongs to comparison, and badges come from reporting.
- **No Eureka.** VQMS registers its services with Eureka but still calls fixed URLs, so the registry adds a process without changing anything. SUTRA keeps fixed `*_SERVICE_URL` settings.

## 3. Package layout (identical in every Java service)

```
com.sutra.<service>/
  <Service>Application.java
  controller/          thin REST layer: mapping, @Valid, @PreAuthorize; calls one service method
  service/             interfaces: the use cases (e.g. QuotationService)
  service/impl/        orchestration and @Transactional boundaries (QuotationServiceImpl)
  service/internal/    domain engines with no Spring web code (QuotationCalculator, QuotationValidator,
                       ComparisonEngine, VendorGrader, HybridItemMatcher …)
  client/              RestClient wrappers for other services (RfqClient, DocumentClient …)
  client/dto/          the data those services return
  entity/              JPA entities (+ enums/)
  repository/          Spring Data JPA
  dto/request/         validated input records
  dto/response/        output records (entities never leave the service)
  (mapping)            static from() factories on the response records (no separate mapper classes)
  event/               domain events written to the outbox
  config/              typed @ConfigurationProperties, security config, OpenAPI
  exception/           named exceptions for this service
src/main/resources/db/migration/   V1 domain tables, V2 outbox + inbox, V3+ changes
```

`sutra-common` keeps everything shared, so it is written once and not repeated in every service:

- JWT codec and filter, `CurrentUserProvider`, `ServiceTokenProvider`;
- the global exception handler and `ApiError`;
- `CorrelationIdFilter`, the `OutboxService` + relay, the inbox, and `RestClientFactory`.

(VQMS copies these into each service, which is how its copies drift apart.)

## 4. Data and integrity

- **Database:** one database, `sutra_db`, with one schema per service, as today.
- **Cross-schema foreign keys stay** (your decision A2). Two examples:
  - `quotation.purchase_request_id` → `rfq.purchase_request`
  - `approval.selected_quotation_id` → `quotation.quotation`
- **Start order:** services start in dependency order so the referenced tables exist: identity, masterdata, rfq, document, quotation, extraction, comparison, approval, audit, reporting.
- **No joins in Java across schemas:** code keeps `NamedRef` / `UserRef` snapshots, as now.
- **Files** move to `document.document` (BYTEA, size, SHA-256, owner type/id). `quotation` keeps the document ids.
- **Upload order:** the document is stored first, then the quotation row that references it.
- **Orphan clean-up:** a scheduled `OrphanDocumentCleanupJob` deletes documents nobody references after 24 h. This replaces the single transaction we lose by splitting.
- **Money:** `NUMERIC` everywhere. Statuses stay guarded by CHECK constraints.
- **Migrations** move from `database/<service>/` into each service's `src/main/resources/db/migration`. `database/scripts/` keeps the create, reset and backup scripts.

## 5. Security

| Layer | Rule |
| --- | --- |
| Gateway | JWT signature, expiry and `userId` claim checked; deactivated users refused (token version); only `/api/auth/login`, `/api/auth/register-vendor` and health are public; 20 requests/min/IP on the public endpoints; CORS decided here and `Origin` stripped before forwarding |
| Every service | Checks the JWT again; `@PreAuthorize` on **every** endpoint; record-level rules: a buyer changes only own RFQs; a manager does not award own RFQ; a vendor sees only its own data, identified by the `vendorId` claim |
| Service to service | Short-lived `SERVICE` tokens; `/internal/**` blocked at the gateway |
| Roles | Exactly four: `ADMIN`, `PROCUREMENT` (Buyer), `APPROVER` (Manager), `VENDOR` |

## 6. How a quotation flows

```
Vendor ─► quotation-service POST /vendor-portal/rfqs/{id}/quotations
            ├─► rfq-service      GET  /internal/rfqs/{id}            (open? due date? items)
            ├─► document-service POST /internal/documents             (check content + store, returns ids)
            ├─ save quotation (UPLOADED) + outbox event, one transaction
            └─► extraction-service POST /api/extractions  → 202 + /api/extractions/{jobId}
                  └─ background: document-service bytes → Python / OCR → Groq strict JSON (rules backup)
                     → match to RFQ items → POST quotation-service /internal/quotations/{id}/extraction
quotation-service: QuotationCalculator + QuotationValidator → flags → READY_FOR_COMPARISON / NEEDS_REVIEW / FAILED
Buyer ─► comparison-service POST /api/comparisons {rfqId}
            ├─► quotation-service GET /internal/rfqs/{id}/ready-quotations
            ├─ ComparisonEngine + VendorGrader → comparison_report vN
            └─► rfq-service PATCH /internal/rfqs/{id}/status UNDER_REVIEW
Manager ─► approval-service POST /api/approvals → rfq-service status APPROVED/REJECTED
Every write ─► own outbox ─► relay ─► audit-service /internal/audit-events (hash chained, inbox dedupe)
```

How each step is triggered:

- **Pipeline steps:** each one is triggered by an **explicit REST call**, as in VQMS. Nothing waits for an event that no one reads.
- **Audit events:** the outbox carries **only** these, and every one of them has a reader: audit-service.
- **Correlation id:** every call carries `X-Correlation-Id`, and it is written to every log line and outbox row.

## 7. What we adopt from VQMS

| Adopted | Where |
| --- | --- |
| Per-service layout with `service` / `impl` / `internal` | Every service |
| `CorrelationIdFilter` + MDC logging | sutra-common, gateway |
| Outbox with `Propagation.MANDATORY` + inbox `processed_messages` | sutra-common; inbox in audit-service |
| Relay with exponential back-off, stop batch on first failure | sutra-common `AuditOutboxRelay` |
| CORS only at the gateway, `Origin` stripped | gateway |
| Extraction as a job: 202 + status URL | extraction-service |
| Tamper-evident audit: SHA-256 chain, `GET /audit-logs/verify` | audit-service |
| Pluggable validation rules (`ValidationRule` interface: price, quantity, tax, required fields, terms) | quotation-service `service/internal/rules` |
| OpenAPI (Swagger UI) per service | every service |

## 8. VQMS problems we do not copy

| VQMS problem | In SUTRA |
| --- | --- |
| Monolith still owns half the domain | No monolith; every capability has one owner |
| Empty `quotation-service`, nested scaffold folders | Every service folder has code and tests, nothing else |
| Monolith ends with `permitAll()`, no `@PreAuthorize` | Every endpoint in every service is role-checked; a test asserts it |
| Outbox events nobody reads | Only audit events go through the outbox, and audit-service reads all of them |
| Old copies of moved controllers left behind | Code is moved, not copied; old packages are deleted in the same step |
| Shared code copied into each service | Written once in `sutra-common` |
| Stale README | README and docs rewritten in the same change |

## 9. Migration plan

Each phase ends with every test passing and the app working:

1. **Shared pieces — DONE (9 Nov 2026):**
   - `CorrelationIdFilter` (`X-Correlation-Id`, old `X-Request-Id` still accepted) on every service, in logs, error bodies and service-to-service calls.
   - Outbox writes require the caller's transaction (`Propagation.MANDATORY`) and carry the correlation id; the relay backs off exponentially (2 s to 60 s).
   - Audit service: SHA-256 hash chain, `GET /api/audit-logs/verify`, an append-only trigger, and older entries linked at start-up (verified on the real database: 24 entries, intact).
   - Flyway migrations moved into each service (`src/main/resources/db/migration`).
   - auth, masterdata and audit use the VQMS layout: `service` interfaces, `service/impl`, `service/internal`, `dto/request|response`, `entity/base`.
   - All 236 backend tests pass.
   - `auth-service` keeps its name: renaming its schema would touch every cross-schema foreign key for no gain.
2. **Documents:** split document-service out of procurement, with orphan clean-up.
3. **Extraction:** split extraction-service out (job API, 202 + polling).
4. **RFQs and quotations:** split procurement into rfq-service and quotation-service (validation engine and rules inside quotation).
5. **Comparison, approval and reporting:** split out the three services; delete procurement-service.
6. **Audit chain:** hash chain + verify endpoint; inbox dedupe.
7. **Finish:** update `start-all.ps1`, the frontend API paths, the acceptance test, README, `ARCHITECTURE.md`, the guide and the diagrams.

**Size:** about 10 services and 300+ files moved or created, with the 246 backend tests reorganised per service. That is several working sessions. The app and all tests keep working after every phase.

## 10. Open choices (defaults chosen)

| Choice | Default | Alternative |
| --- | --- | --- |
| Java version | Java 17 + Spring Boot 4.1. JDK 21 is installed, but Maven's `JAVA_HOME` points to JDK 17; switching means changing that machine setting first | Java 21 like VQMS |
| Gateway | Keep SUTRA's own servlet gateway | Spring Cloud Gateway (WebFlux) |
| API paths | Keep `/api/...` (frontend unchanged where possible) | `/api/v1/...` like VQMS |
| Service registry | None, fixed URLs | Eureka |
