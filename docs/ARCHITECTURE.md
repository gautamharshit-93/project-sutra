# SUTRA architecture

## System

```mermaid
flowchart LR
    browser["Browser<br/>React 18 + Vite<br/>:5173"]
    gw["Gateway<br/>:8080<br/>routing, CORS, token checks,<br/>sign-in rate limit"]
    auth["auth-service<br/>:8081<br/>users, roles, sign-in,<br/>vendor sign-ups"]
    md["masterdata-service<br/>:8082<br/>entities, sub-entities,<br/>customers, vendors"]
    proc["procurement-service<br/>:8083<br/>RFQs, quotations, reading,<br/>comparison, award, assistant"]
    audit["audit-service<br/>:8084<br/>audit log"]
    py["Python document service<br/>:8000<br/>file to text, rule-based reader,<br/>item matcher"]
    ocr["Tesseract OCR<br/>(local)"]
    groq["Groq API<br/>(free tier)"]
    db[("PostgreSQL 18<br/>sutra_db<br/>schemas: auth, masterdata,<br/>procurement, audit")]

    browser -->|"/api/**"| gw
    gw --> auth & md & proc & audit
    proc --> md
    auth --> md
    proc --> py
    proc --> ocr
    proc --> groq
    auth -. outbox .-> audit
    md -. outbox .-> audit
    proc -. outbox .-> audit
    auth & md & proc & audit --> db
```

* The browser talks only to the gateway. The gateway checks each token's signature and that the user is still active
  and unchanged, limits sign-in and vendor sign-up to 20 requests a minute per IP address, and never forwards
  `/internal/**` (service-to-service calls, signed with short-lived service tokens).
* Every service owns one schema and applies it with Flyway from `database/<service>/`. References into another
  service's schema are real foreign keys (see *Data model*), created by whichever service migrates last.
* Every change writes an audit event into the service's own `audit_outbox` table in the same transaction; a relay
  delivers it to the audit service, so no audit event is lost when the audit service is down.
* Groq only reads documents and answers questions. Every amount is recalculated in Java (`QuotationCalculator`,
  `BigDecimal`), and only a manager records an award.

## Main flow

```mermaid
sequenceDiagram
    actor V as Vendor
    actor A as Administrator
    actor B as Buyer
    actor M as Manager
    participant S as SUTRA

    V->>S: Register company (/register)
    A->>S: Approve sign-up (creates vendor, activates login)
    B->>S: Drop RFQ document (or paste into Sahayak)
    S-->>B: Draft: title, dates, items (read by Groq)
    B->>S: Create RFQ (+ open to vendors), quotations due by date
    V->>S: Send quotation (PDF / Excel / Word, one per RFQ)
    S->>S: Read (text / OCR -> Groq, rule-based backup), match items,<br/>recalculate totals, note anything to check
    S-->>B: Badge: RFQ ready to compare
    B->>S: Compare now (closes the RFQ to new quotations)
    S-->>M: Badge: RFQ waiting for an award
    M->>S: Award (reason required if not the lowest)
    S-->>V: Awarded / Not awarded
    M->>S: Download award summary PDF
```

## Data model

Solid lines are foreign keys inside one schema; the labels marked *cross-schema* are foreign keys between the
services' schemas (`database/masterdata/V2`, `auth/V4`, `procurement/V4`). All have `ON DELETE RESTRICT` (or
`CASCADE` for owned children such as items and files).

```mermaid
erDiagram
    business_entity ||--o{ sub_entity : "has"
    business_entity ||--o{ customer : "optional owner"
    business_entity ||--o{ purchase_request : "cross-schema: buys"
    sub_entity ||--o{ purchase_request : "cross-schema: plant"
    customer ||--o{ purchase_request : "cross-schema: for"
    vendor ||--o{ quotation : "cross-schema: sends"
    vendor ||--o{ app_user : "cross-schema: vendor login"
    app_user ||--o{ user_role : "has"
    role ||--o{ user_role : "granted"
    app_user ||--o| vendor_signup : "waiting sign-up"
    app_user ||--o{ purchase_request : "cross-schema: raised"
    app_user ||--o{ approval : "cross-schema: decided"
    purchase_request ||--o{ request_item : "lists"
    purchase_request ||--o{ quotation : "receives"
    purchase_request ||--o{ comparison_report : "compared in"
    purchase_request ||--o{ approval : "awarded by"
    quotation ||--o{ quotation_file : "original files"
    quotation ||--o{ quotation_item : "quoted lines"
    quotation ||--o{ quotation_flag : "notes to check"
    request_item ||--o{ quotation_item : "matched to"
    comparison_report ||--o{ comparison_quotation : "includes"
    quotation ||--o{ comparison_quotation : "version compared"
    comparison_report ||--o{ approval : "basis of"
    quotation ||--o{ approval : "selected"

    business_entity { bigint id PK
        varchar code UK
        varchar name
        boolean active }
    sub_entity { bigint id PK
        bigint entity_id FK
        varchar code "unique per entity" }
    customer { bigint id PK
        varchar code UK
        bigint entity_id FK }
    vendor { bigint id PK
        varchar code UK
        varchar name
        varchar tax_number
        boolean active }
    app_user { bigint id PK
        varchar username UK
        varchar email UK
        bigint vendor_id FK
        boolean active
        boolean pending_approval
        boolean password_change_required }
    role { bigint id PK
        varchar name UK "ADMIN PROCUREMENT APPROVER VENDOR" }
    user_role { bigint user_id PK
        bigint role_id PK }
    vendor_signup { bigint id PK
        bigint user_id FK
        varchar company_name }
    purchase_request { bigint id PK
        varchar request_number UK
        bigint entity_id FK
        bigint sub_entity_id FK
        bigint customer_id FK
        bigint requested_by_id FK
        varchar status
        date quotations_due
        date required_by }
    request_item { bigint id PK
        bigint purchase_request_id FK
        int line_number
        numeric quantity }
    quotation { bigint id PK
        bigint purchase_request_id FK
        bigint vendor_id FK "unique with purchase_request_id"
        varchar status
        numeric grand_total }
    quotation_file { bigint id PK
        bigint quotation_id FK
        bytea content
        char sha256 }
    quotation_item { bigint id PK
        bigint quotation_id FK
        bigint request_item_id FK
        numeric unit_price
        numeric line_total }
    quotation_flag { bigint id PK
        bigint quotation_id FK
        varchar severity
        boolean resolved }
    comparison_report { bigint id PK
        bigint purchase_request_id FK
        int report_version }
    comparison_quotation { bigint comparison_report_id PK
        bigint quotation_id PK
        bigint quotation_version
        int price_rank }
    approval { bigint id PK
        bigint purchase_request_id FK
        bigint comparison_report_id FK
        bigint selected_quotation_id FK
        varchar decision
        text reason }
```

Also in every schema: `audit_outbox` (events waiting for delivery) and Flyway's `flyway_schema_history`; the audit
schema holds `audit_log`. Money is always `NUMERIC`; statuses, percentages, amounts and codes are guarded by `CHECK`
constraints; searched and joined columns are indexed.

## Code structure (each service)

```
com.sutra.<service>
  controller   REST endpoints (@RestController, @PreAuthorize)
  service      business rules and transactions
  repository   Spring Data JPA interfaces
  entity       JPA entities (BaseEntity, AuditableEntity are abstract) and enums
  request      validated input records (Bean Validation)
  response     output records (entities are never returned)
  exception    named exceptions (SutraException is the abstract base; one GlobalExceptionHandler in sutra-common)
  client       calls to other services (MasterDataClient, ProcurementClient)
  config / security / util / validation / calculation / ai   where a service needs them
```

Interfaces with more than one implementation: `QuotationExtractor` (Groq, rule-based), `ItemMatcher`,
`DocumentProcessingClient`.
