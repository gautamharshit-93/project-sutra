"""Acceptance test of Project SUTRA against the specification's section 9, on the microservice stack.

It runs the real workflow with the automotive test pack in ai/test-data: the admin sets up master data and logins, a buyer
publishes an RFQ, three vendors log in and send their quotations, the buyer reviews and compares them, and a
manager awards the RFQ. Every check prints PASS or FAIL; a Markdown report is written to docs/ACCEPTANCE_RESULTS.md.

It must run against the separate acceptance copy of the system, never the real one:
    .\\start-all.ps1 -Acceptance                         (gateway on :9080, database sutra_test)
    ai\\.venv\\Scripts\\python scripts\\acceptance_test.py
Each run creates its own records (suffix = run id), so it can be repeated. Python standard library only, plus
python-docx from the ai venv to build an unreadable test file.
"""
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "ai" / "test-data"
BASE = os.environ.get("SUTRA_ACCEPTANCE_URL", "http://localhost:9080")
RUN = datetime.now().strftime("%H%M%S")
PASSWORD = "Accept@1234"
PSQL = os.environ.get("PSQL", "C:/Program Files/PostgreSQL/18/bin/psql.exe")

ITEMS = [
    ("MT-101", "Cold-rolled steel sheet (CRCA), 1.2 mm", 12, "Tonne"),
    ("MT-102", "Aluminium alloy ingot ADC12", 8, "Tonne"),
    ("MT-103", "EPDM rubber compound, 70 Shore A", 1500, "Kg"),
    ("MT-104", "Deep-groove ball bearing 6204-2RS", 2000, "Nos"),
    ("MT-105", "Hex flange bolt M8 x 25, grade 8.8", 10000, "Nos"),
]
# Grand totals in ai/test-data/README.txt (INR, GST 18%)
VENDORS = [
    ("SHAKTI", "Shakti Metals & Components (Demo)", "Vendor_1_Shakti_Metals", Decimal("3702250.00")),
    ("PIONEER", "Pioneer Industrial Supplies (Demo)", "Vendor_2_Pioneer_Industrial", Decimal("3723962.00")),
    ("VERTEX", "Vertex Auto Materials (Demo)", "Vendor_3_Vertex_Auto", Decimal("3622788.80")),
]
EXPECTED = {code: total for code, _name, _stem, total in VENDORS}

results = []  # (section, check, passed, detail)
section = ""


class Api:
    """A signed-in user of the gateway. Errors come back as (status, body) from try_* calls."""

    def __init__(self, token=None):
        self.token = token

    def _send(self, method, path, data=None, headers=None, raw=False):
        req = urllib.request.Request(BASE + "/api" + path, data=data, method=method)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                body = r.read()
                return r.status, body if raw else (json.loads(body) if body else None)
        except urllib.error.HTTPError as e:
            body = e.read()
            try:
                return e.code, json.loads(body)
            except ValueError:
                return e.code, {"message": body.decode(errors="replace")[:300]}

    def try_call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        return self._send(method, path, data, {"Content-Type": "application/json", "Accept": "application/json"})

    def call(self, method, path, body=None):
        status, result = self.try_call(method, path, body)
        if status >= 300:
            raise RuntimeError(f"{method} {path} -> {status}: {result}")
        return result

    def try_upload(self, path, files):
        boundary = uuid.uuid4().hex
        parts = b""
        for f in files:
            parts += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{f.name}\"\r\n"
                      "Content-Type: application/octet-stream\r\n\r\n").encode() + f.read_bytes() + b"\r\n"
        parts += f"--{boundary}--\r\n".encode()
        return self._send("POST", path, parts, {"Content-Type": f"multipart/form-data; boundary={boundary}"})

    def upload(self, path, files):
        status, result = self.try_upload(path, files)
        if status >= 300:
            raise RuntimeError(f"upload {path} -> {status}: {result}")
        return result

    def raw(self, path):
        return self._send("GET", path, raw=True)


def login(username, password=PASSWORD):
    status, body = Api().try_call("POST", "/auth/login", {"username": username, "password": password})
    if status != 200:
        raise RuntimeError(f"login {username} -> {status}: {body}")
    return Api(body["token"])


def check(name, passed, detail=""):
    results.append((section, name, bool(passed), str(detail)))
    print(f"  [{'PASS' if passed else 'FAIL'}] {name}{' - ' + str(detail) if detail else ''}")
    return passed


def start(title):
    global section
    section = title
    print(f"\n== {title}")


def wait_processed(api, qid, timeout=420):
    deadline = time.time() + timeout
    while True:
        q = api.call("GET", f"/quotations/{qid}")
        if q["status"] not in ("UPLOADED", "PROCESSING") or time.time() > deadline:
            return q
        time.sleep(3)


def main():
    t0 = time.time()
    try:
        admin = login("admin", "Admin@123")
    except RuntimeError as e:
        sys.exit(f"Cannot sign in at {BASE} ({e}). Start the acceptance copy first: .\\start-all.ps1 -Acceptance")

    # ------------------------------------------------------------------ 1
    start("1. CRUD for entities, sub-entities, customers and vendors, with valid relationships enforced")
    entity = admin.call("POST", "/entities", {"code": f"AXM{RUN}", "name": f"Axiom Auto Components {RUN}",
                                              "address": "Chakan MIDC, Pune"})
    check("Create entity (the buying company)", entity["id"], entity["code"])
    sub = admin.call("POST", f"/entities/{entity['id']}/sub-entities", {"code": "PLANT1", "name": "Chakan plant"})
    check("Create sub-entity linked to the entity", sub["entityId"] == entity["id"])
    status, body = admin.try_call("POST", f"/entities/{entity['id']}/sub-entities", {"code": "PLANT1", "name": "Again"})
    check("Duplicate sub-entity code rejected", status == 409, f"HTTP {status}")
    entity = admin.call("PUT", f"/entities/{entity['id']}", {"code": entity["code"], "name": entity["name"],
                                                             "description": "Brake and suspension parts", "active": True})
    check("Update entity", entity["description"] == "Brake and suspension parts")
    customer = admin.call("POST", "/customers", {"code": f"OEM{RUN}", "name": f"Northline Cars {RUN}",
                                                 "entityId": entity["id"], "email": "buying@northline.example"})
    check("Create customer linked to the entity", customer["entityId"] == entity["id"])
    status, body = admin.try_call("POST", "/customers", {"code": f"BAD{RUN}", "name": "x", "entityId": 999999})
    check("Customer with unknown entity rejected", status == 404, f"HTTP {status} {body.get('code')}")
    status, body = admin.try_call("DELETE", f"/entities/{entity['id']}")
    check("Entity with sub-entities cannot be deleted", status == 422, f"HTTP {status} {body.get('code')}")
    vendor_ids = {}
    for code, name, _stem, _total in VENDORS:
        v = admin.call("POST", "/vendors", {"code": f"{code}{RUN}", "name": f"{name} {RUN}",
                                            "email": f"sales@{code.lower()}.example"})
        vendor_ids[code] = v["id"]
    check("Create three vendors", len(vendor_ids) == 3)
    spare = admin.call("POST", "/vendors", {"code": f"SPARE{RUN}", "name": f"Spare vendor {RUN}"})
    spare = admin.call("PUT", f"/vendors/{spare['id']}", {"code": spare["code"], "name": spare["name"] + " (renamed)", "active": True})
    check("Update vendor", spare["name"].endswith("(renamed)"))
    admin.call("DELETE", f"/vendors/{spare['id']}")
    status, body = admin.try_call("GET", f"/vendors/{spare['id']}")
    check("Delete vendor (then 404 VENDOR_NOT_FOUND)", status == 404 and body.get("code") == "VENDOR_NOT_FOUND", f"HTTP {status}")

    # ------------------------------------------------------------------ 2 users and roles
    start("2. Users and roles: company logins and one vendor login per vendor")
    users = {}
    for username, roles, vendor in [(f"buyer{RUN}", ["PROCUREMENT"], None), (f"buyer2{RUN}", ["PROCUREMENT"], None),
                                    (f"manager{RUN}", ["APPROVER"], None)] + \
                                   [(f"{c.lower()}{RUN}", ["VENDOR"], vendor_ids[c]) for c, *_ in VENDORS]:
        users[username] = admin.call("POST", "/users", {"username": username, "email": f"{username}@accept.example",
                                                        "fullName": username, "password": PASSWORD, "roles": roles,
                                                        "vendorId": vendor})
    check("Buyer, second buyer, manager and 3 vendor logins created", len(users) == 6)
    check("Vendor login is linked to its vendor", users[f"vertex{RUN}"]["vendorName"].startswith("Vertex Auto"))
    status, body = admin.try_call("POST", "/users", {"username": f"novendor{RUN}", "email": f"nv{RUN}@x.example",
                                                     "fullName": "x", "password": PASSWORD, "roles": ["VENDOR"]})
    check("Vendor login without a vendor rejected", status == 422 and body.get("code") == "VENDOR_REQUIRED", f"HTTP {status}")
    status, body = admin.try_call("POST", "/users", {"username": f"mixed{RUN}", "email": f"mx{RUN}@x.example",
                                                     "fullName": "x", "password": PASSWORD, "roles": ["VENDOR", "PROCUREMENT"],
                                                     "vendorId": vendor_ids["SHAKTI"]})
    check("Vendor login cannot also have a company role", status == 422, f"HTTP {status} {body.get('code')}")
    buyer, other_buyer = login(f"buyer{RUN}"), login(f"buyer2{RUN}")
    manager = login(f"manager{RUN}")
    vendors = {c: login(f"{c.lower()}{RUN}") for c, *_ in VENDORS}
    status, _ = buyer.try_call("POST", "/vendors", {"code": f"NOPE{RUN}", "name": "x"})
    check("Non-admin cannot change master data", status == 403, f"HTTP {status}")

    # ------------------------------------------------------------------ 3 RFQ and vendor submissions
    start("3. RFQ published; vendors upload quotations that are stored, retrieved and linked to the right vendor")
    pr = buyer.call("POST", "/purchase-requests", {
        "title": f"Production materials for Q1 2027 ({RUN})", "entityId": entity["id"], "subEntityId": sub["id"],
        "customerId": customer["id"], "currency": "INR", "requiredBy": "2026-12-15",
        "description": "RFQ SUTRA-RFQ-2026-001 from the test pack"})
    for code, desc, qty, unit in ITEMS:
        buyer.call("POST", f"/purchase-requests/{pr['id']}/items",
                   {"itemCode": code, "description": desc, "quantity": qty, "unitOfMeasure": unit})
    buyer.call("PATCH", f"/purchase-requests/{pr['id']}/status", {"status": "OPEN"})
    check("Buyer creates the RFQ with 5 items and opens it", True, pr["requestNumber"])
    seen = vendors["SHAKTI"].call("GET", "/vendor-portal/rfqs?size=100")["content"]
    check("Vendor sees the open RFQ in the portal", any(r["id"] == pr["id"] for r in seen))
    vb = vendors["SHAKTI"].call("GET", "/vendor-portal/badges")
    check("Vendor's menu badge counts open RFQs it has not quoted on", vb["openToQuote"] >= 1, vb)
    yesterday = datetime.fromordinal(datetime.now().date().toordinal() - 1).date().isoformat()
    status, body = buyer.try_call("POST", "/purchase-requests/with-items", {
        "request": {"title": f"Backdated RFQ {RUN}", "entityId": entity["id"], "currency": "INR", "quotationsDue": yesterday},
        "items": [{"description": "Brake disc", "quantity": 10, "unitOfMeasure": "Nos"}], "open": True})
    check("A quotations-due date in the past is refused", status == 422 and body.get("code") == "DUE_DATE_IN_PAST",
          f"HTTP {status}")
    late = buyer.call("POST", "/purchase-requests/with-items", {
        "request": {"title": f"Past-due RFQ {RUN}", "entityId": entity["id"], "currency": "INR",
                    "quotationsDue": datetime.now().date().isoformat()},
        "items": [{"description": "Brake disc", "quantity": 10, "unitOfMeasure": "Nos"}], "open": True})
    check("Buyer creates and opens an RFQ with its items in one step", late["status"] == "OPEN" and len(late["items"]) == 1,
          late["requestNumber"])
    # Let a day pass: move the due date back directly in the (test) database
    subprocess.run([PSQL, "-U", "sutra", "-h", "localhost", "-d", "sutra_test", "-q", "-c",
                    f"UPDATE rfq.purchase_request SET quotations_due = DATE '{yesterday}' WHERE id = {late['id']}"],
                   env={**os.environ, "PGPASSWORD": "sutra_pass"}, check=True)
    status, body = vendors["SHAKTI"].try_upload(f"/vendor-portal/rfqs/{late['id']}/quotations",
                                                [DATA / "Vendor_1_Shakti_Metals_Quotation.pdf"])
    check("After the quotation due date the vendor portal refuses new quotations",
          status == 422 and body.get("code") == "RFQ_DUE_DATE_PASSED", f"HTTP {status}: {body.get('message')}")
    status, _ = vendors["SHAKTI"].try_call("GET", "/purchase-requests")
    check("Vendor cannot use the company API", status == 403, f"HTTP {status}")
    status, _ = vendors["SHAKTI"].try_call("GET", "/vendors")
    check("Vendor cannot list other vendors", status == 403, f"HTTP {status}")
    quotation_ids = {}
    for code, _name, stem, _total in VENDORS:
        files = [DATA / f"{stem}_Quotation.pdf", DATA / f"{stem}_Material_List.xlsx"]
        q = vendors[code].upload(f"/vendor-portal/rfqs/{pr['id']}/quotations", files)
        quotation_ids[code] = q["id"]
        check(f"{code}: vendor sends PDF + Excel as one quotation", len(q["files"]) == 2 and q["stage"] == "RECEIVED",
              ", ".join(f"{f['fileName']} ({f['fileFormat']}, {f['sizeBytes']:,} B)" for f in q["files"]))
    status, body = vendors["SHAKTI"].try_upload(f"/vendor-portal/rfqs/{pr['id']}/quotations",
                                                [DATA / "Vendor_1_Shakti_Metals_Quotation.pdf"])
    check("Second quotation from the same vendor rejected", status == 409, f"HTTP {status}")
    vq = buyer.call("GET", f"/quotations/{quotation_ids['VERTEX']}")
    status, _ = vendors["SHAKTI"].try_call("GET", f"/vendor-portal/quotations/{vq['id']}/files/{vq['files'][0]['id']}")
    check("Vendor cannot download another vendor's file", status == 404, f"HTTP {status}")
    stored = vendors["VERTEX"].raw(f"/vendor-portal/quotations/{vq['id']}/files/{vq['files'][0]['id']}")[1]
    original = (DATA / vq["files"][0]["fileName"]).read_bytes()
    check("Original file retrieved byte-for-byte from PostgreSQL", stored == original, f"{len(stored)} bytes")
    listed = buyer.call("GET", f"/purchase-requests/{pr['id']}/quotations")
    check("Buyer sees 3 quotations, each with the right vendor, sent through the portal",
          sorted(x["vendorId"] for x in listed) == sorted(vendor_ids.values()) and all(x["submittedByVendor"] for x in listed))

    # ------------------------------------------------------------------ 4 invalid files
    start("4. Invalid files and failed extraction produce clear errors without losing the upload")
    neg = buyer.call("POST", "/purchase-requests", {"title": f"Negative tests {RUN}", "entityId": entity["id"], "currency": "INR"})
    buyer.call("POST", f"/purchase-requests/{neg['id']}/items", {"description": "Brake disc", "quantity": 1, "unitOfMeasure": "Nos"})
    buyer.call("PATCH", f"/purchase-requests/{neg['id']}/status", {"status": "OPEN"})
    tmp = Path(tempfile.mkdtemp(prefix="sutra-acc-"))
    (tmp / "notes.txt").write_text("not a quotation")
    (tmp / "fake.pdf").write_text("this is text renamed to pdf")
    (tmp / "empty.xlsx").write_bytes(b"")
    upload = f"/purchase-requests/{neg['id']}/quotations?vendorId={vendor_ids['PIONEER']}"
    for fname, label in [("notes.txt", "Unsupported type (.txt)"), ("fake.pdf", "Renamed/corrupt PDF"), ("empty.xlsx", "Empty file")]:
        status, body = buyer.try_upload(upload, [tmp / fname])
        check(f"{label} rejected", status in (400, 415) and body.get("message"), f"HTTP {status}: {body.get('message')}")
    for qid in quotation_ids.values():  # extraction runs one at a time: let the real quotations go first
        wait_processed(buyer, qid)
    from docx import Document  # ai venv
    blank = tmp / "blank_quotation.docx"
    Document().save(blank)
    fq = wait_processed(buyer, buyer.upload(upload, [blank])["id"])
    check("Unreadable document ends in FAILED with a clear message", fq["status"] == "FAILED",
          f"{fq['status']}: {(fq.get('failureReason') or '')[:160]}")
    stored = buyer.raw(f"/quotations/{fq['id']}/files/{fq['files'][0]['id']}")[1]
    check("Original upload kept after the failure", stored == blank.read_bytes(), f"{len(stored)} bytes")
    status, _ = buyer.try_call("POST", f"/quotations/{fq['id']}/reprocess?engine=RULES")
    check("Retry offered with another engine (rule-based)", status == 202, f"HTTP {status}")
    fq = wait_processed(buyer, fq["id"])
    item0 = buyer.call("GET", f"/purchase-requests/{neg['id']}")["items"][0]
    fq = buyer.call("POST", f"/quotations/{fq['id']}/items", {"requestItemId": item0["id"], "description": "Brake disc (entered by hand)",
                                                             "quantity": 1, "unitOfMeasure": "Nos", "unitPrice": 2500, "taxPercent": 18})
    check("Manual entry after failed extraction; backend computes the total",
          Decimal(str(fq["grandTotal"])) == Decimal("2950.00"), f"grand total {fq['grandTotal']}")

    # ------------------------------------------------------------------ 5 review and compare
    start("5. Extracted data can be reviewed or corrected, and equivalent items compared")
    engines = {e["engine"]: e for e in buyer.call("GET", "/extraction-engines")["engines"]}
    check("Engines are Groq (free models) and the rule-based reader",
          set(engines) == {"GROQ", "RULES"} and engines["RULES"]["available"],
          ", ".join(f"{k}: {'on' if v['available'] else 'off'} {v['models']}" for k, v in engines.items()))
    extracted = {}
    for code, qid in quotation_ids.items():
        q = wait_processed(buyer, qid)
        if q["status"] == "FAILED":  # e.g. every free Groq model rate-limited: read it without AI instead
            buyer.call("POST", f"/quotations/{qid}/reprocess?engine=RULES")
            q = wait_processed(buyer, qid)
        extracted[code] = q
        matched = sorted(i["itemCode"] or "" for i in q["items"] if i["requestItemLineNumber"])
        check(f"{code}: read by {q['extractionSource']} ({q['status']}), all 5 materials matched to the RFQ",
              q["status"] in ("NEEDS_REVIEW", "READY_FOR_COMPARISON") and len(matched) == 5, ", ".join(matched))
    status, body = buyer.try_upload(f"/purchase-requests/{pr['id']}/quotations?vendorId={vendor_ids['SHAKTI']}",
                                    [DATA / "Materials_Catalogue.xlsx"])
    check("A second quotation for the same vendor is refused, also when the buyer uploads it",
          status == 409 and "already has a quotation" in body.get("message", ""), f"HTTP {status}")
    badges = buyer.call("GET", "/dashboard/badges")
    check("The buyer's menu badge counts the RFQ as ready to compare", badges["rfqsToCompare"] >= 1, badges)
    q = extracted["SHAKTI"]
    item = next(i for i in q["items"] if i["itemCode"] == "MT-105")
    body = {k: item.get(k) for k in ("requestItemId", "itemCode", "description", "quantity", "unitOfMeasure", "unitPrice",
                                     "discountPercent", "discountAmount", "taxPercent", "taxAmount", "statedLineTotal")}
    body["notes"] = "Checked against the PDF"
    corrected = buyer.call("PUT", f"/quotations/{q['id']}/items/{item['id']}", body)
    check("Buyer corrects a line; it is marked as edited",
          next(i for i in corrected["items"] if i["id"] == item["id"])["manuallyEdited"])
    status, _ = other_buyer.try_call("PUT", f"/quotations/{q['id']}/items/{item['id']}", body)
    check("Another buyer cannot change this RFQ's quotations", status == 403, f"HTTP {status}")
    statuses = {c: buyer.call("GET", f"/quotations/{qid}")["status"] for c, qid in quotation_ids.items()}
    check("Vendor quotations are ready to compare with no review step (notes stay visible)",
          all(s == "READY_FOR_COMPARISON" for s in statuses.values()), statuses)
    report = buyer.call("POST", f"/purchase-requests/{pr['id']}/comparisons")
    result = report["result"]
    check("Side-by-side comparison generated", len(result["quotations"]) == 3, f"version {report['reportVersion']}")
    check("Every material has an offer from every vendor",
          all(sum(c["status"] != "MISSING" for c in r["cells"]) == 3 for r in result["rows"]))
    status, _ = vendors["SHAKTI"].try_upload(f"/vendor-portal/rfqs/{pr['id']}/quotations", [DATA / "Materials_Catalogue.xlsx"])
    check("RFQ stops accepting vendor quotations once compared", status in (409, 422), f"HTTP {status}")

    # ------------------------------------------------------------------ 6 calculations and flags
    start("6. Backend calculations validated, uncertain values flagged, no vendor awarded by the system")
    by_vendor = {c["vendorId"]: c for c in result["quotations"]}
    for code, vid in vendor_ids.items():
        got = Decimal(str(by_vendor[vid]["grandTotal"]))
        check(f"{code}: backend grand total equals the document total", got == EXPECTED[code],
              f"system {got:,} vs README {EXPECTED[code]:,}")
    ranks = {code: by_vendor[vid]["priceRank"] for code, vid in vendor_ids.items()}
    check("Ranking: Vertex 1, Shakti 2, Pioneer 3", ranks == {"VERTEX": 1, "SHAKTI": 2, "PIONEER": 3}, ranks)
    vertex = buyer.call("GET", f"/quotations/{quotation_ids['VERTEX']}")
    flagged = [f for f in vertex["flags"] if "warrant" in (f["message"] + (f.get("fieldName") or "")).lower()]
    check("Vertex's open bearing warranty is flagged for review", flagged, flagged[0]["message"] if flagged else "none")
    check("Comparison lists it as missing information",
          "Warranty incomplete" in by_vendor[vendor_ids["VERTEX"]]["missingInformation"])
    grade = by_vendor[vendor_ids["VERTEX"]]["grade"]
    pay = next(r for r in grade["rules"] if r["code"] == "PAYMENT")
    check("Advisory grades explain themselves (Vertex loses points for the 50% advance)",
          float(pay["points"]) < pay["maxPoints"], f"{grade['grade']} ({grade['score']}); payment: {pay['detail']}")
    pr_now = buyer.call("GET", f"/purchase-requests/{pr['id']}")
    check("Nothing awarded automatically", pr_now["status"] == "UNDER_REVIEW"
          and not buyer.call("GET", f"/purchase-requests/{pr['id']}/approvals"), pr_now["status"])
    check("Disclaimer: the system never selects or approves", "never selects or approves" in result["disclaimer"])

    # ------------------------------------------------------------------ 7 authorisation and audit
    start("7. Only authorised users access restricted records or award; actions are auditable")
    status, _ = Api().try_call("GET", "/purchase-requests")
    check("Unauthenticated access rejected", status == 401, f"HTTP {status}")
    status, _ = BaseInternal().status()
    check("Internal service APIs are not reachable through the gateway", status == 404, f"HTTP {status}")
    status, _ = manager.try_upload(f"/purchase-requests/{pr['id']}/quotations?vendorId={vendor_ids['SHAKTI']}",
                                   [DATA / "Vendor_1_Shakti_Metals_Quotation.pdf"])
    check("Manager cannot upload", status == 403, f"HTTP {status}")
    lowest = by_vendor[vendor_ids["VERTEX"]]["quotationId"]
    other = by_vendor[vendor_ids["SHAKTI"]]["quotationId"]
    award = {"comparisonReportId": report["id"], "decision": "APPROVED", "selectedQuotationId": lowest}
    for who, api in (("Buyer", buyer), ("Vendor", vendors["VERTEX"])):
        status, _ = api.try_call("POST", f"/purchase-requests/{pr['id']}/approvals", award)
        check(f"{who} cannot award", status == 403, f"HTTP {status}")
    status, body = manager.try_call("POST", f"/purchase-requests/{pr['id']}/approvals", {**award, "selectedQuotationId": other})
    check("Awarding a vendor that is not the lowest needs a reason", status == 422 and body.get("code") == "REASON_REQUIRED",
          f"HTTP {status} {body.get('code')}")
    approval = manager.call("POST", f"/purchase-requests/{pr['id']}/approvals",
                            {**award, "reason": "Lowest complete quotation; bearing warranty to be fixed in the PO."})
    check("Manager awards the RFQ (who, when, reason recorded)", approval["decidedBy"] == f"manager{RUN}",
          f"{approval['decision']} {approval['selectedVendorName']} by {approval['decidedBy']} at {approval['decidedAt']}")
    check("RFQ closed as awarded", buyer.call("GET", f"/purchase-requests/{pr['id']}")["status"] == "APPROVED")
    status, pdf = buyer.raw(f"/purchase-requests/{pr['id']}/award-summary")
    check("Award summary downloads as a PDF", status == 200 and pdf[:5] == b"%PDF-", f"HTTP {status}, {len(pdf):,} bytes")
    outcomes = {c: next(q["outcome"] for q in vendors[c].call("GET", "/vendor-portal/quotations") if q["rfqId"] == pr["id"])
                for c in vendors}
    check("Vendors see the outcome (Vertex awarded, the others not)",
          outcomes == {"VERTEX": "AWARDED", "SHAKTI": "NOT_AWARDED", "PIONEER": "NOT_AWARDED"}, outcomes)
    status, _ = buyer.try_call("GET", "/audit-logs")
    check("Buyer cannot read the full audit log", status == 403, f"HTTP {status}")
    status, _ = manager.try_call("GET", "/audit-logs?size=5")
    check("Manager can read the full audit log", status == 200, f"HTTP {status}")
    time.sleep(3)  # audit events reach the audit service from each service's outbox within a second or two
    history = manager.call("GET", f"/purchase-requests/{pr['id']}/history?size=200")["content"]
    actions = {h["action"] for h in history}
    needed = {"CREATED", "ITEM_ADDED", "UPLOADED", "EXTRACTED", "ITEM_CORRECTED", "GENERATED", "APPROVED"}
    check("RFQ history shows every step", needed <= actions, f"{len(history)} entries; missing {needed - actions or 'none'}")
    check("Extraction recorded as the system, uploads as each vendor, the award as the manager",
          any(h["action"] == "EXTRACTED" and h["performedBy"] == "system" for h in history)
          and any(h["action"] == "UPLOADED" and h["performedBy"] == f"vertex{RUN}" for h in history)
          and any(h["action"] == "APPROVED" and h["performedBy"] == f"manager{RUN}" for h in history))
    lock = f"lock{RUN}"
    admin.call("POST", "/users", {"username": lock, "email": f"{lock}@x.example", "fullName": "Lock", "password": PASSWORD,
                                  "roles": ["PROCUREMENT"]})
    for _ in range(5):
        Api().try_call("POST", "/auth/login", {"username": lock, "password": "wrong-password"})
    status, body = Api().try_call("POST", "/auth/login", {"username": lock, "password": PASSWORD})
    check("Account locked after 5 failed sign-ins", status == 429, f"HTTP {status}: {body.get('message')}")
    target = users[f"buyer2{RUN}"]
    admin.call("PUT", f"/users/{target['id']}", {"username": target["username"], "email": target["email"],
                                                  "fullName": target["fullName"], "roles": ["PROCUREMENT"], "active": False})
    time.sleep(16)  # the gateway trusts a user's state for up to 15 seconds
    status, body = other_buyer.try_call("GET", "/purchase-requests")
    check("A deactivated user's open session stops working", status == 401 and body.get("code") == "SESSION_EXPIRED",
          f"HTTP {status} {body.get('code')}")
    services = admin.call("GET", "/system/services")
    check("All services report healthy", all(s["up"] for s in services), ", ".join(f"{s['service']}" for s in services))

    write_report(time.time() - t0, pr, result, approval, vendor_ids)


class BaseInternal:
    """A request for an internal service API through the gateway (it must not exist from outside)."""

    def status(self):
        req = urllib.request.Request(BASE + "/internal/stats")
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, None
        except urllib.error.HTTPError as e:
            return e.code, None


def write_report(seconds, pr, result, approval, vendor_ids):
    passed = sum(1 for r in results if r[2])
    lines = [
        "# SUTRA acceptance results", "",
        f"Run {datetime.now():%Y-%m-%d %H:%M}, against the acceptance copy of the microservice stack ({BASE}, database "
        f"sutra_test) with the automotive test pack in `ai/test-data/`.",
        f"**{passed} of {len(results)} checks passed** in {seconds:.0f} s. RFQ {pr['requestNumber']}.", "",
        "Regenerate with `.\\start-all.ps1 -Acceptance`, then `ai\\.venv\\Scripts\\python scripts\\acceptance_test.py`.", "",
        "## Comparison of the test-pack quotations", "",
        "| Rank | Vendor | System grand total (INR) | Expected (ai/test-data/README.txt) | Grade | Missing information |",
        "|---|---|---|---|---|---|",
    ]
    names = {vid: code for code, vid in vendor_ids.items()}
    for col in sorted(result["quotations"], key=lambda c: c["priceRank"] or 99):
        code = names[col["vendorId"]]
        lines.append(f"| {col['priceRank']} | {col['vendorName']} | {Decimal(str(col['grandTotal'])):,} | "
                     f"{EXPECTED[code]:,} | {col['grade']['grade']} ({col['grade']['score']}) | "
                     f"{', '.join(col['missingInformation']) or 'none'} |")
    lines += ["", f"Award: **{approval['decision']}** {approval['selectedVendorName']} by {approval['decidedBy']} "
                  f"(reason: {approval['reason']}).", ""]
    current = None
    for sec, name, ok, detail in results:
        if sec != current:
            lines += ["", f"## {sec}", "", "| Result | Check | Detail |", "|---|---|---|"]
            current = sec
        lines.append(f"| {'PASS' if ok else '**FAIL**'} | {name} | {detail.replace('|', '/')} |")
    (ROOT / "docs" / "ACCEPTANCE_RESULTS.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n{passed} of {len(results)} checks passed. Report: docs/ACCEPTANCE_RESULTS.md")


if __name__ == "__main__":
    main()
