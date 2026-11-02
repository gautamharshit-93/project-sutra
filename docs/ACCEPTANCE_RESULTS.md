# SUTRA acceptance results

Run 2026-11-01 23:24, against the acceptance copy of the microservice stack (http://localhost:9080, database sutra_test) with the automotive test pack in `ai/test-data/`.
**71 of 71 checks passed** in 227 s. RFQ PR-2026-00001.

Regenerate with `.\start-all.ps1 -Acceptance`, then `ai\.venv\Scripts\python ai\scripts\acceptance_test.py`.

## Comparison of the test-pack quotations

| Rank | Vendor | System grand total (INR) | Expected (ai/test-data/README.txt) | Grade | Missing information |
|---|---|---|---|---|---|
| 1 | Vertex Auto Materials (Demo) 232044 | 3,622,788.8 | 3,622,788.80 | A (92) | Warranty incomplete |
| 2 | Shakti Metals & Components (Demo) 232044 | 3,702,250.0 | 3,702,250.00 | A (94) | none |
| 3 | Pioneer Industrial Supplies (Demo) 232044 | 3,723,962.0 | 3,723,962.00 | A (92) | none |

Award: **APPROVED** Vertex Auto Materials (Demo) 232044 by manager232044 (reason: Lowest complete quotation; bearing warranty to be fixed in the PO.).


## 1. CRUD for entities, sub-entities, customers and vendors, with valid relationships enforced

| Result | Check | Detail |
|---|---|---|
| PASS | Create entity (the buying company) | AXM232044 |
| PASS | Create sub-entity linked to the entity |  |
| PASS | Duplicate sub-entity code rejected | HTTP 409 |
| PASS | Update entity |  |
| PASS | Create customer linked to the entity |  |
| PASS | Customer with unknown entity rejected | HTTP 404 ENTITY_NOT_FOUND |
| PASS | Entity with sub-entities cannot be deleted | HTTP 422 ENTITY_HAS_SUB_ENTITIES |
| PASS | Create three vendors |  |
| PASS | Update vendor |  |
| PASS | Delete vendor (then 404 VENDOR_NOT_FOUND) | HTTP 404 |

## 2. Users and roles: company logins and one vendor login per vendor

| Result | Check | Detail |
|---|---|---|
| PASS | Buyer, second buyer, manager, auditor and 3 vendor logins created |  |
| PASS | Vendor login is linked to its vendor |  |
| PASS | Vendor login without a vendor rejected | HTTP 422 |
| PASS | Vendor login cannot also have a company role | HTTP 422 VENDOR_ROLE_EXCLUSIVE |
| PASS | Non-admin cannot change master data | HTTP 403 |

## 3. RFQ published; vendors upload quotations that are stored, retrieved and linked to the right vendor

| Result | Check | Detail |
|---|---|---|
| PASS | Buyer creates the RFQ with 5 items and opens it | PR-2026-00001 |
| PASS | Vendor sees the open RFQ in the portal |  |
| PASS | Vendor cannot use the company API | HTTP 403 |
| PASS | Vendor cannot list other vendors | HTTP 403 |
| PASS | SHAKTI: vendor sends PDF + Excel as one quotation | Vendor_1_Shakti_Metals_Quotation.pdf (PDF, 3,776 B), Vendor_1_Shakti_Metals_Material_List.xlsx (XLSX, 7,101 B) |
| PASS | PIONEER: vendor sends PDF + Excel as one quotation | Vendor_2_Pioneer_Industrial_Quotation.pdf (PDF, 3,743 B), Vendor_2_Pioneer_Industrial_Material_List.xlsx (XLSX, 7,079 B) |
| PASS | VERTEX: vendor sends PDF + Excel as one quotation | Vendor_3_Vertex_Auto_Quotation.pdf (PDF, 3,709 B), Vendor_3_Vertex_Auto_Material_List.xlsx (XLSX, 7,063 B) |
| PASS | Second quotation from the same vendor rejected | HTTP 409 |
| PASS | Vendor cannot download another vendor's file | HTTP 404 |
| PASS | Original file retrieved byte-for-byte from PostgreSQL | 3709 bytes |
| PASS | Buyer sees 3 quotations, each with the right vendor, sent through the portal |  |

## 4. Invalid files and failed extraction produce clear errors without losing the upload

| Result | Check | Detail |
|---|---|---|
| PASS | Unsupported type (.txt) rejected | HTTP 415: Unsupported file type for 'notes.txt'. Upload a PDF, Excel (.xlsx, .xls) or Word (.docx) file. |
| PASS | Renamed/corrupt PDF rejected | HTTP 400: 'fake.pdf' is not a valid PDF file. It may be corrupt or renamed from another format. |
| PASS | Empty file rejected | HTTP 400: No file was uploaded, or the files are empty. |
| PASS | Unreadable document ends in FAILED with a clear message | FAILED: Every extraction engine failed. GEMINI: No readable text was found in blank_quotation.docx You can also enter the quotation manually. / RULES: No line items fou |
| PASS | Original upload kept after the failure | 36563 bytes |
| PASS | Retry offered with another engine (rule-based) | HTTP 202 |
| PASS | Manual entry after failed extraction; backend computes the total | grand total 2950.0 |

## 5. Extracted data can be reviewed or corrected, and equivalent items compared

| Result | Check | Detail |
|---|---|---|
| PASS | Engines are Gemini (free models) and the rule-based reader | GEMINI: on ['gemini-3.8-flash', 'gemini-3.5-flash-lite'], RULES: on ['rules v1'] |
| PASS | SHAKTI: read by GEMINI (READY_FOR_COMPARISON), all 5 materials matched to the RFQ | MT-101, MT-102, MT-103, MT-104, MT-105 |
| PASS | PIONEER: read by GEMINI (READY_FOR_COMPARISON), all 5 materials matched to the RFQ | MT-101, MT-102, MT-103, MT-104, MT-105 |
| PASS | VERTEX: read by GEMINI (NEEDS_REVIEW), all 5 materials matched to the RFQ | MT-101, MT-102, MT-103, MT-104, MT-105 |
| PASS | SHAKTI: rule-based reader (no AI) independently reaches the document total | 3,702,250.0 quality A |
| PASS | PIONEER: rule-based reader (no AI) independently reaches the document total | 3,723,962.0 quality A |
| PASS | VERTEX: rule-based reader (no AI) independently reaches the document total | 3,622,788.8 quality A |
| PASS | Buyer corrects a line; it is marked as edited |  |
| PASS | Another buyer cannot change this RFQ's quotations | HTTP 403 |
| PASS | Side-by-side comparison generated | version 1 |
| PASS | Every material has an offer from every vendor |  |
| PASS | RFQ stops accepting vendor quotations once compared | HTTP 422 |

## 6. Backend calculations validated, uncertain values flagged, no vendor awarded by the system

| Result | Check | Detail |
|---|---|---|
| PASS | SHAKTI: backend grand total equals the document total | system 3,702,250.0 vs README 3,702,250.00 |
| PASS | PIONEER: backend grand total equals the document total | system 3,723,962.0 vs README 3,723,962.00 |
| PASS | VERTEX: backend grand total equals the document total | system 3,622,788.8 vs README 3,622,788.80 |
| PASS | Ranking: Vertex 1, Shakti 2, Pioneer 3 | {'SHAKTI': 2, 'PIONEER': 3, 'VERTEX': 1} |
| PASS | Vertex's open bearing warranty is flagged for review | Extractor note: Warranty terms specify 'Bearing warranty to be confirmed', which makes it conditional or unspecified for other items. |
| PASS | Comparison lists it as missing information |  |
| PASS | Advisory grades explain themselves (Vertex loses points for the 50% advance) | A (92); payment: 50% advance |
| PASS | Nothing awarded automatically | UNDER_REVIEW |
| PASS | Disclaimer: the system never selects or approves |  |

## 7. Only authorised users access restricted records or award; actions are auditable

| Result | Check | Detail |
|---|---|---|
| PASS | Unauthenticated access rejected | HTTP 401 |
| PASS | Internal service APIs are not reachable through the gateway | HTTP 404 |
| PASS | Auditor cannot upload | HTTP 403 |
| PASS | Buyer cannot award | HTTP 403 |
| PASS | Auditor cannot award | HTTP 403 |
| PASS | Vendor cannot award | HTTP 403 |
| PASS | Awarding a vendor that is not the lowest needs a reason | HTTP 422 REASON_REQUIRED |
| PASS | Manager awards the RFQ (who, when, reason recorded) | APPROVED Vertex Auto Materials (Demo) 232044 by manager232044 at 2026-11-01T17:54:10.797938Z |
| PASS | RFQ closed as awarded |  |
| PASS | Vendors see the outcome (Vertex awarded, the others not) | {'SHAKTI': 'NOT_AWARDED', 'PIONEER': 'NOT_AWARDED', 'VERTEX': 'AWARDED'} |
| PASS | Buyer cannot read the full audit log | HTTP 403 |
| PASS | Auditor can read the full audit log | HTTP 200 |
| PASS | RFQ history shows every step | 21 entries; missing none |
| PASS | Extraction recorded as the system, uploads as each vendor, the award as the manager |  |
| PASS | Account locked after 5 failed sign-ins | HTTP 429: Too many failed sign-in attempts. Try again in 15 minute(s) or ask an administrator. |
| PASS | A deactivated user's open session stops working | HTTP 401 SESSION_EXPIRED |
| PASS | All services report healthy | gateway, auth service, master data service, audit service, procurement service, document-service |
