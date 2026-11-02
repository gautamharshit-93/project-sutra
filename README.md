# Project SUTRA — Vendor Quotation Management System

Centralises business entities, customers, vendors, purchase requests and vendor quotations. Users upload
quotations as PDF, Excel or Word files. The system extracts the details, flags anything uncertain, compares
vendors side by side and records an authorised human decision with a full audit trail.
AI assists; backend arithmetic and people decide.

```
frontend/   React 18 + Vite UI                                  http://localhost:5173
backend/    Java 17, Spring Boot 4.1 microservices              gateway http://localhost:8080
            gateway, auth-service, masterdata-service, procurement-service, audit-service, sutra-common
            archive/  version 1 monolith backend
database/   PostgreSQL 18 schema per service (Flyway) + setup scripts
            archive/  version 1 migrations
ai/         Python 3.12 FastAPI document service                http://localhost:8000
            test-data/  automotive test pack (+ v1 IT test pack), scripts/  acceptance test, data pack generator
docs/       Architecture (.docx / .pdf), acceptance results, spec compliance check
start-all.ps1 / stop-all.ps1   start or stop every service
```

Exact, verified-compatible versions of every component are listed in [VERSIONS.md](VERSIONS.md).

## How extraction works

1. A user uploads a vendor's quotation for a purchase request: one file, or several files that together form one
   quotation (for example the same offer as PDF, Word and Excel, as in `ai/test-data/`). The backend checks each file's type,
   size and real content (magic bytes, Office package structure), stores the original bytes in PostgreSQL linked to
   the same quotation, and returns immediately.
2. In the background an **extraction engine** reads the documents. Three engines can be switched per upload or retry:

   | Engine | What it is | Needs |
   |--------|------------|-------|
   | `GEMINI` | Google Gemini (`gemini-3.8-flash`, free tier, structured JSON output). PDFs, including scanned ones, are sent directly; Excel and Word are converted to text first. Requests use `store: false`. On the free tier Google may use submitted content to improve its products | `GEMINI_API_KEY` |
   | `OPENAI` | Any OpenAI-compatible chat API: Groq or OpenRouter (free tiers), a local Ollama, or OpenAI. Reads the text produced by the Python service, so scanned PDFs need Gemini | `OPENAI_MODEL` (+ `OPENAI_API_KEY`, or `OPENAI_BASE_URL` for Ollama) |
   | `RULES` | Rule-based reader in the Python service, no AI: finds the item table by its column headers and totals, dates and terms by their labels. Works on Excel, Word and text PDFs with a normal layout | Python service only |

   `AUTO` (the default) tries `SUTRA_ENGINE_ORDER` (Gemini → OpenAI-compatible → rules) and moves on when an engine is
   not configured, fails (e.g. a rate-limited free tier) or finds no line items. A rate-limited Gemini model first
   falls back to `GEMINI_FALLBACK_MODELS`; temporary errors (HTTP 503) are retried.
   All files of one quotation are read together: each line is listed once, and figures that differ between the
   files are flagged.
3. Quoted lines are matched to requested lines: exactly by item code (e.g. `IT-001`) where present, otherwise by
   description and specification (rapidfuzz in Python, with a local fallback in Java).
4. The backend **recalculates every line and total itself** and flags differences from the printed figures,
   low-confidence values, unmatched or missing items, currency, validity and missing commercial terms.
5. A reviewer corrects or enters data manually, accepts warnings and marks the quotation ready. Each extraction gets
   an **extraction-quality grade** (A-F, 100 points from fixed rules: lines matched, totals reconciled, fields found)
   that shows how much checking is needed. **Cross-check** re-reads the files with every available engine and shows
   the readings side by side, graded the same way, without saving anything; the reviewer can then re-run the
   extraction with the engine they trust.
6. The comparison ranks complete quotations on like-for-like totals and adds an **advisory vendor grade** (price 40,
   coverage 15, delivery, payment, warranty and validity 10 each, open flags 5; rules in `VendorGrader`).
   **Nothing is approved automatically.** An approver records the decision, and a reason is required when it is not
   the lowest complete quotation.

If extraction fails, or no engine finds a single line item (e.g. a blank document), the quotation becomes `FAILED`
with each engine's reason and the original file is kept, so the user can retry with another engine or type the
data in.

## Running it

Prerequisites (already installed on this machine): JDK 17+, Maven 3.9, Node 24, Python 3.12, PostgreSQL 18.

```powershell
# one time: database login and databases (as the postgres superuser)
psql -U postgres -h localhost -f database/scripts/01_create_database.sql

# one time: dependencies
cd ai;       python -m venv .venv; .\.venv\Scripts\python -m pip install -r requirements-dev.txt; cd ..
cd frontend; npm install; cd ..

# start everything (one window per service)
.\start-all.ps1

# stop everything
.\stop-all.ps1
```

Each service applies its own schema from `database/<service>/` with Flyway on start. On an empty database the auth
service creates the administrator **admin / Admin@123**: change this password after signing in.

The automotive test pack is in `ai/test-data/` (one RFQ, three vendors, each sending a PDF quotation and an Excel
material list; expected totals in `ai/test-data/README.txt`). The acceptance test runs the whole workflow on it
against a separate copy of the system on the `sutra_test` database:

```powershell
.\start-all.ps1 -Acceptance
ai\.venv\Scripts\python ai\scripts\acceptance_test.py     # writes docs/ACCEPTANCE_RESULTS.md
```

### Configuration (environment variables)

| Variable | Default | Purpose |
|----------|---------|---------|
| `GEMINI_API_KEY` | *(unset)* | Google AI Studio key (free). Without it the Gemini engine is skipped |
| `GEMINI_MODEL` | `gemini-3.8-flash` | Gemini model for extraction (any free-tier text model, e.g. `gemini-3.5-flash-lite`) |
| `GEMINI_FALLBACK_MODELS` | `gemini-3.5-flash-lite` | Models tried when `GEMINI_MODEL` is rate-limited (comma-separated) |
| `OPENAI_MODEL` / `OPENAI_API_KEY` / `OPENAI_BASE_URL` | *(unset)* / *(unset)* / `https://api.groq.com/openai/v1` | OpenAI-compatible engine; disabled until `OPENAI_MODEL` is set. For Ollama: `OPENAI_BASE_URL=http://localhost:11434/v1`, no key |
| `SUTRA_ENGINE_ORDER` | `GEMINI,OPENAI,RULES` | Engines `AUTO` tries, in order |
| `SUTRA_EXTRACTION_CONCURRENCY` | `1` | Extractions running at once (1 keeps free tiers within their rate limits) |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/sutra_db` / `sutra` / `sutra_pass` | Database |
| `JWT_SECRET` | dev value | **Set in any shared environment** (32+ characters) |
| `SUTRA_ADMIN_PASSWORD` | `Admin@123` | Password of the first administrator (empty database only) |
| `AI_SERVICE_URL` | `http://localhost:8000` | Python document service |
| `SUTRA_MAX_FILE_BYTES` | 20 MB | Upload limit |
| `CORS_ORIGINS` | `http://localhost:5173` | Allowed browser origins |

## Roles

| Role | Can |
|------|-----|
| ADMIN | Everything: master data, users and roles, any purchase request, decisions, audit log |
| PROCUREMENT | Create purchase requests; upload, review and correct quotations and generate comparisons **on their own requests** |
| APPROVER | Read everything, record approval/rejection decisions (not on requests they raised), read the audit log |
| VIEWER | Read-only |

Roles are enforced in the backend: `@PreAuthorize` on each endpoint, plus record-level checks in `RecordAccessPolicy`
and `ApprovalService`. The UI only hides what a role cannot do.

## Tests

```powershell
cd backend;  mvn test                              # JUnit 6 + Mockito unit tests, PostgreSQL integration
cd ai;       .\.venv\Scripts\python -m pytest       # file-to-text parsers, matcher, API
cd frontend; npm test                               # Vitest + React Testing Library
```

The backend integration tests use the `sutra_test` database, which is wiped and rebuilt from the migrations on
each run. `AiServiceContractIntegrationTest` runs against the real Python service, and skips itself when the
service is not running. No test calls a real AI API: `GeminiQuotationExtractorTest` and `OpenAiQuotationExtractorTest`
use a mock HTTP server, and the workflow test stubs the Gemini engine.

## Specification traceability

| Spec | Requirement | Where |
|------|-------------|-------|
| 2 | React, Spring Boot REST/JSON, PostgreSQL, Python processing, JUnit + Mockito | `frontend/`, `backend/`, `database/`, `ai/` |
| 3.1 | CRUD for entities and sub-entities, customers, vendors, users and roles | `EntityController`, `CustomerController`, `VendorController`, `UserController`; UI: Entities, Customers, Vendors, Users & roles |
| 3.2 | Purchase requests and items (description, quantity, unit, specifications) | `PurchaseRequestService`; UI: Purchase requests → Requested items |
| 3.2 | Upload PDF/Excel/Word for a vendor and request (one or several files per quotation) | `POST /api/purchase-requests/{id}/quotations`; UI: Quotations tab |
| 3.2 | File validation: type, size, readability, clear messages | `QuotationFileValidator` (magic bytes, Office structure, `.doc` guidance) |
| 3.2 | Original files stored in PostgreSQL with name, format, size, upload time, quotation | `quotation_file` table (BYTEA + SHA-256); files listed, downloadable, addable on the quotation page |
| 3.2 | Manual entry and correction | `QuotationService` item/header endpoints; UI: quotation review page |
| 3.2 | Statuses Uploaded, Processing, Needs Review, Ready for Comparison, Failed | `QuotationStatus` + DB CHECK constraint |
| 3.3 | Extract items, quantities, prices, discounts, taxes, totals, validity, delivery and payment terms | `ExtractionEngines` choosing `GeminiQuotationExtractor`, `OpenAiQuotationExtractor` (same `ExtractionSchema`) or `RulesQuotationExtractor` (`ai/app/rules.py`) |
| 3.3 | Cross-check engines and grade extraction quality | `ExtractionCrossCheckService`, `ExtractionQualityGrader`; UI: quotation page → Cross-check |
| 3.3 | Match equivalent items, flag unmatched or missing | `HybridItemMatcher` (item codes, then descriptions/specs), `ai/app/matcher.py`, `QuotationValidator` |
| 3.3 | Side-by-side comparison with price differences, missing info and terms | `ComparisonEngine` (+ advisory `VendorGrader`); UI: `ComparisonMatrix` |
| 3.3 | Totals calculated by the backend, not AI | `QuotationCalculator` (BigDecimal, HALF_UP) |
| 3.3 | Flag uncertain or conflicting values; never auto-approve | `quotation_flag` table, `QuotationReviewService`, `TermsCheck` (e.g. "UPS warranty not specified"); approvals only via `ApprovalService` |
| 3.3 | Keep the file and status on failure; retry or manual entry | `QuotationProcessingService` → `FAILED` + reason; reprocess endpoint |
| 3.4 | Authorised review and decision with user, timestamp and reason | `ApprovalService`, `approval` table CHECK constraints |
| 3.4 | History of important changes | `AuditService` → `audit_log`; UI: Audit log |
| 4 | Normalised schema, FKs, constraints, indexes, decimal money | `database/migrations/V1__init_schema.sql` |
| 5 | Package structure: controller, service, repository, entity, request/response, exception, validation/util/config, AI client | `backend/src/main/java/com/sutra/*` |
| 6 | Centralised exception handling and the named exceptions | `GlobalExceptionHandler`, `ApiError`, `EntityNotFoundException`, `VendorNotFoundException`, `QuotationNotFoundException`, `UnsupportedQuotationFileException`, `QuotationProcessingException` |
| 6 | Authentication and role-based authorisation | JWT (`SecurityConfig`, `JwtService`), `@PreAuthorize`, `RecordAccessPolicy` |
| 6 | No file contents, credentials or secrets in logs | Only IDs, names and sizes are logged; `toString()` of file and input objects omits bytes |
| 7 | Workflow: request → upload → extract/flag → review → compare → decide | Purchase request page with step tracker and tabs |
| 8 | Service tests with mocked repositories and AI; PostgreSQL and file-processing integration tests | `backend/src/test/java/com/sutra/**` |
| 9 | Acceptance criteria | Covered by `QuotationWorkflowIntegrationTest` end to end |
