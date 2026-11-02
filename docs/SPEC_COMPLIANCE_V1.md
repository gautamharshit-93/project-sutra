# SUTRA version 1 — specification compliance check

Checked against *Project SUTRA Final Specification* (sections 2–9), on the code as of 01 Nov 2026 (version 1).
Status: **Done** = implemented and tested · **Partial** = implemented with a gap · **Missing** = not implemented.

## Summary

| Area | Done | Partial | Missing |
|------|-----:|--------:|--------:|
| 2 Technology stack | 5 | 1 | 0 |
| 3.1 Admin and master data | 3 | 1 | 0 |
| 3.2 Requests and quotations | 7 | 0 | 0 |
| 3.3 Extraction and comparison | 6 | 0 | 0 |
| 3.4 Review and approval | 3 | 0 | 0 |
| 4 Database design | 6 | 0 | 0 |
| 5 Java code design | 8 | 0 | 0 |
| 6 Exceptions, validation, security | 5 | 0 | 0 |
| 7 Key workflows | 4 | 1 | 0 |
| 8 Unit testing | 5 | 1 | 0 |
| 9 Acceptance criteria | 6 | 1 | 0 |
| **Total (63 rules)** | **58 (92%)** | **5 (8%)** | **0** |

Version 1 covers every rule in the specification; five are only partly met (listed under *Gaps* below).

## 2. Technology stack

| Rule | Status | Evidence |
|------|--------|----------|
| React frontend | Done | `frontend/` React 18 + Vite |
| Java Spring Boot, REST, JSON | Done | `backend/` Spring Boot 4.1, `@RestController`, Jackson |
| PostgreSQL | Done | PostgreSQL 18, Flyway migrations in `database/migrations` |
| Python document processing (PDF, Excel, Word) | Done | `ai/` FastAPI: `/parse`, `/extract`, `/match` |
| OCR for scanned PDFs (optional) | Partial | Scanned PDFs are read by Gemini (vision), and the Python service only *detects* them (`likelyScanned`). There is no local OCR (e.g. Tesseract), so a scanned PDF cannot be read without Gemini |
| JUnit + Mockito | Done | JUnit 6 + Mockito 5, 161 backend tests |

## 3.1 Administrator and master data

| Rule | Status | Evidence |
|------|--------|----------|
| CRUD entities and sub-entities | Done | `EntityController` (+ `/sub-entities`), delete blocked while children exist |
| CRUD customers (with contact details) | Done | `CustomerController`; contact person, email, phone, address |
| CRUD vendors (profile and contact details) | Done | `VendorController`; contact, email, phone, address, tax number, website |
| Users and roles: manage access by permission | Partial | Users are fully managed (create, edit, deactivate, delete, assign roles, reset password). **Roles are a fixed set** (Admin, Procurement, Approver, Viewer) seeded by migration V2: an admin assigns them but cannot create a new role or change what a role may do |

## 3.2 Purchase request and quotation management

| Rule | Status | Evidence |
|------|--------|----------|
| Create purchase request | Done | `PurchaseRequestService`, statuses Draft → Open → Under review → Approved/Rejected/Cancelled |
| Items: description, quantity, unit, specifications | Done | `request_item` (+ optional item code) |
| Upload PDF / Excel / Word, linked to vendor and request | Done | `POST /api/purchase-requests/{id}/quotations`, one or several files per quotation |
| File validation: type, size, readability, clear message | Done | `QuotationFileValidator`: extension + magic bytes + Office package check, size limit, `.doc` guidance |
| Store original file in PostgreSQL with name, format, size, time, quotation | Done | `quotation_file` (BYTEA, SHA-256, uploaded_at, FK to quotation) |
| Manual entry and correction | Done | Header and line endpoints; lines marked "edited" |
| Statuses Uploaded, Processing, Needs Review, Ready for Comparison, Failed | Done | `QuotationStatus` + DB CHECK constraint |

## 3.3 AI/ML-based extraction and comparison

| Rule | Status | Evidence |
|------|--------|----------|
| Extract items, qty, prices, discounts, taxes, totals, validity, delivery, payment | Done | Gemini structured output, OpenAI-compatible, rule-based reader |
| Match equivalent items; flag unmatched or missing | Done | `HybridItemMatcher` (item code, then fuzzy description/spec), `UNMATCHED_ITEM` / missing-item flags |
| Side-by-side comparison: price differences, missing info, terms | Done | `ComparisonEngine`, `ComparisonMatrix` UI, CSV export |
| Totals calculated by backend, not AI | Done | `QuotationCalculator` (BigDecimal, HALF_UP); AI totals only compared |
| Flag uncertain/conflicting values; never auto-approve | Done | `quotation_flag`, low-confidence and mismatch flags; approval only by a person via `ApprovalService` |
| Keep file and status on failure; retry or manual entry | Done | `FAILED` + reason, reprocess endpoint, manual lines |

## 3.4 Review and approval

| Rule | Status | Evidence |
|------|--------|----------|
| Authorised users review details and comparisons | Done | Role checks + `RecordAccessPolicy` |
| Record approve/reject with user, timestamp, reason | Done | `approval` table; reason required when not choosing the lowest complete quotation |
| History of changes to quotations, comparisons, decisions | Done | `audit_log` via `AuditService`; History tab and Audit log page |

## 4. Database design

| Rule | Status | Evidence |
|------|--------|----------|
| Normalised schema with PK/FK | Done | 17 tables, all suggested ones plus `quotation_flag`, `comparison_quotation`, `user_role` |
| Constraints prevent invalid data | Done | 26 CHECK constraints (statuses, non-negative amounts, percentages, decision reason) |
| Indexes on searched fields | Done | 23 indexes |
| Money as decimal | Done | NUMERIC throughout, never float |
| Original file and extracted data linked to the same quotation | Done | `quotation_file.quotation_id`, `quotation_item.quotation_id` |
| Comparison stores quotation versions | Done | `comparison_quotation.quotation_version` |

## 5. Java code design

| Rule | Status | Evidence |
|------|--------|----------|
| OOP, clear package structure | Done | `controller, service, repository, entity, request, response, exception, validation, util, config, security, calculation, ai` |
| Interfaces/abstract classes only where useful | Done | `QuotationExtractor`, `ItemMatcher`, `DocumentProcessingClient` (interfaces with several implementations); `BaseEntity`, `AuditableEntity`, `SutraException` (abstract) |
| controller | Done | 9 controllers |
| service | Done | 14 services, transactions and orchestration |
| repository | Done | Spring Data JPA |
| entity | Done | JPA entities and enums |
| request / response | Done | Java records, entities never exposed |
| AI client / file processing | Done | `com.sutra.ai`, `com.sutra.validation` |

## 6. Exception handling, validation and security

| Rule | Status | Evidence |
|------|--------|----------|
| Centralised handling, consistent errors | Done | `GlobalExceptionHandler` (12 handlers), `ApiError` with code, message, field errors, request id |
| Named exceptions | Done | All five named in the spec, plus Customer/User/PurchaseRequest not-found, BusinessRule, Forbidden, Duplicate, AccountLocked |
| Validate fields, relationships, file type/size, extracted values | Done | Bean Validation on requests, FK checks in services, file validator, extracted values range-checked |
| Authentication and role-based authorisation, record-level | Done | JWT, `@PreAuthorize`, `RecordAccessPolicy` (buyers only modify their own requests), account lock-out |
| No file contents, credentials or secrets in logs | Done | Only ids, names and sizes logged; `toString()` hides bytes and keys |

## 7. Key workflows

| Rule | Status | Evidence |
|------|--------|----------|
| Create request, add items | Done | Purchase request page |
| Upload quotations for vendor and request | Partial | The **buyer** uploads on the vendor's behalf: vendors have no login and cannot submit quotations themselves |
| Validate, store, extract, show uncertain fields | Done | Background extraction, flags on review page |
| Review/correct, then compare | Done | Review page, comparison tab |
| Record authorised decision with audit history | Done | Decision tab, History tab |

## 8. Unit testing

| Rule | Status | Evidence |
|------|--------|----------|
| Service-layer tests with JUnit + Mockito | Done | 10 service test classes with mocked repositories |
| CRUD rules and entity relationships | Done | `BusinessEntityServiceTest`, `VendorAndCustomerServiceTest`, `UserServiceTest`, `PurchaseRequestServiceTest` |
| Quotation validation, comparison logic, approval permissions | Done | `QuotationValidatorTest`, `QuotationCalculatorTest`, `ComparisonEngineTest`, `ApprovalServiceTest` |
| Custom exceptions | Done | Not-found, unsupported-file, processing, forbidden and duplicate exceptions asserted in the service tests |
| Mock repositories and AI/file-processing | Done | Mockito mocks; mock HTTP servers for Gemini and OpenAI |
| Integration tests for PostgreSQL and file processing | Partial | `QuotationWorkflowIntegrationTest` (real PostgreSQL), `AiServiceContractIntegrationTest` (real Python service). **No unit tests** for `AuditService`, `DashboardService`, `QuotationReviewService` and `ExtractionCrossCheckService` (covered only indirectly) |

## 9. Acceptance criteria

| Criterion | Status | Evidence |
|-----------|--------|----------|
| CRUD with valid relationships | Done | Acceptance section 1 (12/12) |
| Upload, store, retrieve, associate files | Done | Section 2 (6/6), SHA-256 byte match |
| Clear errors for invalid files and failed extraction, upload kept | Done | Fixed in this version: a blank document now ends `FAILED` with a reason (was the 1 failing check of 52) |
| Review/correct and compare equivalent items | Done | Section 4 |
| Backend calculations, flags, no AI approval | Done | Section 5 |
| Only authorised users; actions auditable | Done | Section 6 |
| Service-layer tests pass for normal, validation-failure and exception cases | Partial | All 161 pass; coverage gaps as in section 8 |

## Gaps to close in version 2

1. **Vendor self-service:** vendors log in, see open RFQs and submit their own quotations (section 7).
2. **Roles:** add a Vendor role, turn Viewer into a read-only Auditor, and show each role only its own menus (sections 3.1 and 6).
3. **Tests:** unit tests for `AuditService`, `DashboardService`, `QuotationReviewService` and `ExtractionCrossCheckService` (section 8).
4. **Data hygiene:** demo and acceptance data was written to the main database; tests must use their own database (section 9).
5. **Scanned PDFs without AI:** local OCR remains optional in the specification and is not planned.

Other issues found in version 1 that are outside the specification: no "Back" navigation on detail pages, a duplicate "Purchase requests" button on the dashboard, and an OpenAI engine the project does not need.
