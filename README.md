# Project SUTRA — Vendor Quotation Management System

Centralises business entities, customers, vendors, requests for quotation (RFQs) and vendor quotations. Buyers create
an RFQ (or drop the RFQ document and let it fill itself in), vendors send their quotations as PDF, Excel or Word
files through their own portal, SUTRA reads and checks them, compares the vendors side by side, and a manager
records the award with a full audit trail. AI reads; the backend calculates; people decide.

```
frontend/   React 18 + Vite UI                                  http://localhost:5173
backend/    Java 17, Spring Boot 4.1 microservices              gateway http://localhost:8080
            gateway + auth, masterdata, rfq, quotation, document, extraction, comparison, approval,
            reporting and audit services; shared libraries sutra-api (contracts) and sutra-common
database/   PostgreSQL 18: one schema per service (Flyway migrations) + setup scripts
ai/         Python 3.12 FastAPI document service                http://localhost:8000
            test-data/  test packs, scripts/  acceptance test
docs/       SUTRA_PROJECT_GUIDE.md (full project explanation and viva guide), SPEC_COMPLIANCE.md,
            ACCEPTANCE_RESULTS.md, diagrams/system-architecture.mmd (Mermaid system diagram)
start-all.ps1 / stop-all.ps1   start or stop every service
```

Exact, verified-compatible versions of every component are in [VERSIONS.md](VERSIONS.md). How the parts fit together,
the services, the database model and how they stay consistent are explained in
[docs/SUTRA_PROJECT_GUIDE.md](docs/SUTRA_PROJECT_GUIDE.md); the system diagram is
[docs/diagrams/system-architecture.mmd](docs/diagrams/system-architecture.mmd).

| Port | Service | Owns |
|------|---------|------|
| 8080 | gateway | routing, JWT check, CORS, sign-in rate limit, correlation id |
| 8081 | auth-service | users, roles, sign-in, vendor sign-ups |
| 8082 | masterdata-service | entities, sub-entities, customers, vendors |
| 8083 | rfq-service | RFQs and their items |
| 8084 | audit-service | hash-chained audit log (`GET /api/audit-logs/verify`) |
| 8085 | quotation-service | quotations, lines, review notes, vendor portal |
| 8086 | document-service | original files (content-checked, stored as BYTEA) |
| 8087 | extraction-service | reading jobs (OCR, Groq, rule-based reader), RFQ drafts, the AI |
| 8088 | comparison-service | versioned comparisons, ranking, vendor grade |
| 8089 | approval-service | award / reject decisions |
| 8090 | reporting-service | dashboard, menu badges, award summary PDF, assistant (no database) |
| 8000 | Python document reader | file to text, rule-based reader, matcher |

## Roles

Four roles only.

| Role | Does |
|------|------|
| **Administrator** (ADMIN) | Everything, including users, roles and approving vendor sign-ups |
| **Buyer** (PROCUREMENT) | The company's work: entities and sub-entities, customers, vendors' details, RFQs, quotations and comparisons (changes only RFQs they raised) |
| **Vendor** (VENDOR) | A supplier that signed up and was approved: sees open RFQs, sends its own quotations, keeps its company profile |
| **Manager** (APPROVER) | Awards or rejects compared RFQs (not ones they raised), reads the audit log |

Roles are enforced in the backend (`@PreAuthorize` on every endpoint, record-level checks in `RecordAccessPolicy`,
`ApprovalService` and the vendor portal); the UI only hides what a role cannot do.

## The flow

1. **Vendors sign up** at `/register` (company, contact, GST, login). The login stays inactive until an administrator
   clicks **Approve** under *Users & roles* (the menu shows a badge), which creates the vendor and activates the login.
2. **A buyer creates an RFQ.** Drop the RFQ document (PDF, scanned PDF, Word or Excel) on the RFQs page, or paste it
   into the assistant: Groq fills in the title, dates and every item, and the buyer checks it and clicks **Create
   RFQ** (header and all items in one step, optionally opened to vendors at once). The entity (company or plant buying)
   comes first, with **+ New** right there. A **quotations due by** date closes the vendor portal for that RFQ after
   that day.
3. **Vendors send quotations** from their portal (one quotation per vendor per RFQ, one or several files).
4. **SUTRA reads them automatically.** Excel, Word and text PDFs are turned into text by the Python service; scanned
   PDFs are rendered with Apache PDFBox and read by local Tesseract OCR (English + Hindi). Groq (`openai/gpt-oss-120b`,
   free tier, strict JSON schema) reads the text; if Groq is not configured, rate-limited or finds nothing, the
   rule-based reader in the Python service takes over. Quoted lines are matched to the requested ones by item code,
   then by description.
5. **The backend recalculates every line and total** and notes anything to check (printed totals that differ,
   unmatched or missing items, missing warranty, validity or payment terms). A quotation that was read is **ready to
   compare at once**; only a line without quantity or price (no total possible) or no lines at all needs a person.
6. **The buyer clicks Compare now.** The side-by-side comparison ranks complete quotations on like-for-like totals and
   adds an advisory vendor grade (price 40, coverage 15, delivery, payment, warranty and validity 10 each, open notes
   5). Comparing closes the RFQ to new quotations.
7. **A manager awards it** (a reason is required when it is not the lowest complete quotation). Nothing is ever
   approved automatically. The **award summary PDF** can be downloaded from the Award tab and the award register;
   vendors see *Awarded* / *Not awarded*.

Menu badges show what is waiting: RFQs ready to compare (buyers), RFQs waiting for an award (managers), vendor
sign-ups (administrators), open RFQs not yet quoted and new outcomes (vendors).

If a quotation cannot be read at all (e.g. a blank document) it becomes `FAILED` with the reason, the original file
is kept, and the buyer can read it again or type the lines in.

## Running it

Prerequisites (installed on this machine): JDK 17+, Maven 3.9, Node 24, Python 3.12, PostgreSQL 18, Tesseract 5.

```powershell
# one time: database login and databases (as the postgres superuser)
psql -U postgres -h localhost -f database/scripts/01_create_database.sql

# one time: dependencies
cd ai;       python -m venv .venv; .\.venv\Scripts\python -m pip install -r requirements-dev.txt; cd ..
cd frontend; npm install; cd ..

# start everything (one window per service) / stop everything
.\start-all.ps1
.\stop-all.ps1
```

Each service applies its own schema with Flyway on start (`src/main/resources/db/migration` in each service);
`start-all.ps1` starts them in foreign-key order (master data, auth, document, RFQ, quotation, comparison, approval;
audit, extraction, reporting and the gateway at once), each as its built jar in a small JVM. A database from the
single procurement service of earlier versions is migrated in place: its tables are moved to the new schemas with
their data; `database/scripts/03_drop_legacy_procurement_schema.sql` then removes the empty `procurement` schema. On an empty
database the auth service creates the administrator **admin / Admin@123**, and the app makes that administrator choose
a new password at the first sign-in. To empty a database again, stop the services and run
`database/scripts/02_reset_database.sql` (take a `pg_dump` backup first).

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
| `GROQ_API_KEY` | *(unset)* | Groq key (free, console.groq.com). Without it Groq reading and the assistant are off |
| `GROQ_MODEL` / `GROQ_FALLBACK_MODELS` | `openai/gpt-oss-120b` / `openai/gpt-oss-20b,qwen/qwen3.8-27b` | Groq model, and the models tried when it is rate-limited |
| `TESSERACT_PATH` | *(auto-detected)* | Tesseract executable; `C:\Program Files\Tesseract-OCR\tesseract.exe` and the PATH are searched |
| `TESSDATA_PREFIX` / `TESSERACT_LANGUAGES` | Tesseract's folder / `eng` | OCR language files and languages. On this machine: `%LOCALAPPDATA%\Tesseract-OCR\tessdata` and `eng+hin` (Windows user settings) |
| `SUTRA_OCR_DPI` / `SUTRA_OCR_MAX_PAGES` | `300` / `30` | OCR render resolution and page limit per file |
| `SUTRA_ENGINE_ORDER` | `GROQ,RULES` | Readers tried in order |
| `SUTRA_EXTRACTION_CONCURRENCY` | `1` | Quotations read at once (1 keeps the free tier within its limits) |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/sutra_db` / `sutra` / `sutra_pass` | Database |
| `JWT_SECRET` | random, created by `start-all.ps1` | Signs sign-in tokens. The first start creates a random one and keeps it as a Windows user setting |
| `SUTRA_ADMIN_PASSWORD` | `Admin@123` | Start-up administrator password (empty database only); must be changed at first sign-in |
| `SUTRA_PUBLIC_RATE_LIMIT` | `20` | Sign-in and vendor sign-up requests per minute from one IP address |
| `CROSS_SCHEMA_FKS` | `true` | Foreign keys between the services' schemas (off only where one schema is tested alone) |
| `AI_SERVICE_URL` | `http://localhost:8000` | Python document reader (used by extraction-service) |
| `<SERVICE>_URL` / `<SERVICE>_PORT` | see the port table | Where each service listens (`RFQ_URL`, `QUOTATION_PORT`, ...); `start-all.ps1` sets them all |
| `SUTRA_ORPHAN_AFTER_MINUTES` | `60` | document-service removes uploads never attached to a quotation after this long |
| `SUTRA_MAX_FILE_BYTES` | 20 MB | Upload limit |
| `CORS_ORIGINS` | `http://localhost:5173` | Allowed browser origins |

## Tests

```powershell
cd backend;  mvn test                              # JUnit 6 + Mockito service tests, PostgreSQL integration
cd ai;       .\.venv\Scripts\python -m pytest       # file-to-text parsers, matcher, API
cd frontend; npm test                               # Vitest + React Testing Library
```

256 backend tests (every service, including PostgreSQL integration tests for the RFQ and quotation services), 38 Python tests, 54 frontend tests, and 75 end-to-end acceptance checks. Service
tests mock the repositories, the other services (their clients) and Groq, so business logic is tested without a
database or network; the acceptance test runs every service together on the `sutra_test` database.
`PythonReaderContractIntegrationTest` uses the real Python service and skips itself when it is not running;
`PdfOcrReaderTest` runs the real Tesseract and skips itself when it is not installed. No test calls a real AI API.

How each requirement of the specification is met, with the code that does it: [docs/SPEC_COMPLIANCE.md](docs/SPEC_COMPLIANCE.md).
