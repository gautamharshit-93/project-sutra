# SUTRA — specification compliance

Checked against *Project SUTRA Final Specification* (sections 2–9) on the code as of 09 Nov 2026.
Every rule is met; nothing is partial.

## 2. Technology stack

| Rule | Where |
|------|-------|
| React frontend | `frontend/` React 18 + Vite |
| Java Spring Boot, REST, JSON | `backend/`: Spring Boot 4.1 microservices behind a gateway: `auth`, `masterdata`, `rfq`, `quotation`, `document`, `extraction`, `comparison`, `approval`, `reporting`, `audit`; shared `sutra-api` (contracts) and `sutra-common` (see `docs/SUTRA_PROJECT_GUIDE.md`, layer 4) |
| PostgreSQL | PostgreSQL 18; one schema per service, Flyway migrations in `database/<service>/` |
| Python document processing (PDF, Excel, Word) | `ai/` FastAPI: `/parse` (file to text), `/extract` (rule-based reader), `/match` (item matcher) |
| OCR for scanned PDFs | Apache PDFBox renders pages, local Tesseract OCR (English + Hindi) reads them: `PdfOcrReader` |
| JUnit + Mockito | JUnit 6 + Mockito 5: 256 backend tests |

## 3.1 Administrator and master data

| Rule | Where |
|------|-------|
| CRUD entities and sub-entities | `EntityController` (+ `/sub-entities`); UI *Entities*, and **+ New** inside *New RFQ*. Codes are generated from the name when left blank. Delete is refused while sub-entities, customers or RFQs use the entity |
| CRUD customers (with contact details) | `CustomerController`; UI *Customers* |
| CRUD vendors (profile and contact details) | `VendorController` (edit, deactivate, delete); vendors are created by approving a portal sign-up (`/register`, `VendorSignupService`) and keep their own profile (`/api/vendor-profile`) |
| Users and roles; access by permission | `UserController` (create, edit, deactivate, delete, assign roles, reset password, approve/reject vendor sign-ups). Four roles: Administrator, Buyer, Vendor, Manager |

## 3.2 Requests and quotations

| Rule | Where |
|------|-------|
| Create purchase request (RFQ) | `PurchaseRequestService`; one-step create with items (`POST /purchase-requests/with-items`); RFQ read from a dropped document or pasted text (`RfqDraftService`); statuses Draft → Open → Under review → Approved / Rejected / Cancelled |
| Items: description, quantity, unit, specifications | `request_item` (+ optional item code) |
| Upload PDF / Excel / Word linked to vendor and request | Vendor portal (`VendorPortalService.submit`) or the buyer on a vendor's behalf (`POST /purchase-requests/{id}/quotations`); one quotation per vendor per RFQ (service check + `uq_quotation_vendor_per_request`) |
| File validation with clear messages | `FileContentValidator` (document-service): extension + magic bytes + Office package structure, size limit, readable content |
| Original files stored in PostgreSQL with name, format, size, time, quotation | `document.document` (BYTEA, SHA-256, `uploaded_at`, owner) in document-service, referenced by `quotation.quotation_file` (FK to the quotation and to the document) |
| Manual entry and correction | Quotation header and line endpoints; edited lines are marked |
| Statuses Uploaded, Processing, Needs review, Ready for comparison, Failed | `QuotationStatus` + CHECK constraint. Read quotations are ready at once; *Needs review* only when a line has no quantity or price |
| Quotation deadline | `purchase_request.quotations_due`; the vendor portal refuses quotations after it (`RFQ_DUE_DATE_PASSED`) |

## 3.3 Extraction and comparison

| Rule | Where |
|------|-------|
| Extract items, quantities, prices, discounts, taxes, totals, validity, delivery and payment terms | `GroqQuotationExtractor` (strict JSON schema `ExtractionSchema`), rule-based backup `RulesQuotationExtractor` (`ai/app/rules.py`), chosen by `ExtractionEngines` |
| Match equivalent items; flag unmatched or missing | `HybridItemMatcher` (item code, then description/specification), `QuotationValidator` |
| Side-by-side comparison: prices, missing information, terms | `ComparisonEngine`, advisory `VendorGrader`; UI `ComparisonMatrix`, CSV export |
| Totals calculated by the backend, not AI | `QuotationCalculator` (`BigDecimal`, HALF_UP); printed totals are only compared |
| Flag uncertain or conflicting values; never auto-approve | `quotation_flag` (notes shown with the comparison), `TermsCheck`; awards only through `ApprovalService` by a manager |
| Keep file and status on failure; retry or manual entry | `FAILED` + reason, file kept, *Read again* (`/reprocess`), manual lines |

## 3.4 Review and approval

| Rule | Where |
|------|-------|
| Authorised users review details and comparisons | `@PreAuthorize`, `RecordAccessPolicy` |
| Approve / reject with user, time and reason | `approval` table; reason required when not choosing the lowest complete quotation; award summary PDF (`AwardSummaryService`) |
| History of changes | Audit events from every service (`AuditService` → outbox → `audit_log`); RFQ *History* tab and *Audit log* page |

## 4. Database design

| Rule | Where |
|------|-------|
| Normalised schema with primary and foreign keys | One schema per service (auth, masterdata, rfq, quotation, document, extraction, comparison, approval, audit); 21 foreign keys between schemas plus those inside each, see `docs/SUTRA_PROJECT_GUIDE.md`, layer 6 |
| Constraints prevent invalid data | 42 CHECK constraints (statuses, amounts ≥ 0, percentages 0–100, code and email formats, due date ≤ required-by, decision reason) and 18 unique constraints |
| Indexes on searched and joined fields | Every foreign key and search column (names, statuses, vendor, entity, user) |
| Money as decimal | `NUMERIC` everywhere |
| Original file and extracted data linked to the same quotation | `quotation_file.quotation_id`, `quotation_item.quotation_id` |
| Comparison keeps the quotation versions it compared | `comparison_quotation.quotation_version` |

## 5. Java code design

| Rule | Where |
|------|-------|
| OOP, clear package structure | `controller`, `service`, `repository`, `entity`, `request`, `response`, `exception`, `client`, `config`, `security`, `util`, `validation`, `calculation`, `ai` |
| Interfaces and abstract classes where useful | Interfaces `QuotationExtractor`, `ItemMatcher`, `DocumentProcessingClient` (several implementations each); abstract `BaseEntity`, `AuditableEntity`, `SutraException` |
| Request / response objects | Java records with Bean Validation; entities are never returned |

## 6. Exceptions, validation and security

| Rule | Where |
|------|-------|
| Centralised, consistent error handling | `GlobalExceptionHandler` (sutra-common, 12 handlers) → `ApiError` with code, message, field errors and request id |
| Named exceptions | `EntityNotFoundException`, `VendorNotFoundException`, `CustomerNotFoundException`, `UserNotFoundException`, `PurchaseRequestNotFoundException`, `QuotationNotFoundException`, `UnsupportedQuotationFileException`, `InvalidQuotationFileException`, `QuotationProcessingException`, `AiServiceException`, `BusinessRuleException`, `DuplicateResourceException`, `ForbiddenOperationException`, `ResourceNotFoundException`, `ServiceUnavailableException`, `AccountLockedException` |
| Validate fields, relationships, files and extracted values | Bean Validation, service checks, database constraints, `FileContentValidator` (document-service), `RfqDraftService.parse` |
| Authentication and role-based, record-level authorisation | JWT (random `JWT_SECRET`), `@PreAuthorize`, `RecordAccessPolicy`, lock-out after 5 failed sign-ins, 20 sign-in / sign-up requests a minute per IP (`PublicEndpointRateLimiter`), vendor sign-ups need approval, the start-up administrator password must be changed |
| No file contents, credentials or secrets in logs | Only ids, names and sizes are logged; `toString()` of file and input objects omits bytes and keys |

## 7. Key workflows

| Rule | Where |
|------|-------|
| Create request, add items | *New RFQ* (drop the RFQ document, or fill in by hand), or Sahayak |
| Vendors upload quotations for the request | Vendor portal; buyer uploads for e-mailed quotations |
| Validate, store, extract, show what to check | Background reading; notes on the quotation page and in the comparison |
| Review / correct, then compare | Quotation page; *Compare now* |
| Authorised decision with audit history | *Award* tab (manager), award summary PDF, *History* |

## 8. Unit testing

| Rule | Where |
|------|-------|
| Service-layer tests with JUnit + Mockito | 256 backend tests; repositories, other services and Groq are mocked |
| CRUD rules and relationships | `BusinessEntityServiceTest`, `VendorAndCustomerServiceTest`, `SelfServiceMasterDataTest`, `UserServiceTest`, `VendorSignupServiceTest`, `PurchaseRequestServiceTest`, `NewFeaturesServiceTest` |
| Quotation validation, comparison and approval rules | `QuotationValidatorTest`, `QuotationCalculatorTest`, `QuotationReviewServiceTest`, `ComparisonEngineTest`, `ComparisonServiceTest`, `ApprovalServiceTest`, `VendorPortalServiceTest` |
| Custom exceptions | Asserted throughout the service tests |
| Mock repositories and AI / file processing | Mockito; mock HTTP server for Groq (`GroqQuotationExtractorTest`) |
| Integration tests for PostgreSQL and file processing | `RfqIntegrationTest`, `QuotationIntegrationTest` (real PostgreSQL, each in its own schema), `PythonReaderContractIntegrationTest` (real Python service), `PdfOcrReaderTest` (real Tesseract), plus the 75-check acceptance run against every service |

## 9. Acceptance criteria

`ai/scripts/acceptance_test.py` runs the whole workflow against a separate copy of the system and checks every
criterion: 75 of 75 checks pass (`docs/ACCEPTANCE_RESULTS.md`).
