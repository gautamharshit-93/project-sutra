from datetime import timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import rules
from app.main import app
from samples import generate_samples

client = TestClient(app)
DATA = Path(__file__).resolve().parents[1] / "test-data" / "v1-it-test-pack"
XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

# Grand totals printed in test-data/v1-it-test-pack/README.txt
PACK = {
    "Vendor_1_Northstar_Quotation": ("NSOS/QTN/26-27/118", 1778260.0, "Northstar Office Systems (Demo Vendor)"),
    "Vendor_2_Vertex_Quotation": ("VIS/NOI/2026/447", 1785340.0, "Vertex Infotech Supplies (Demo Vendor)"),
    "Vendor_3_Apex_Quotation": ("ADT/QUO/2026-27/092", 1753527.2, "Apex Digital Traders (Demo Vendor)"),
}


@pytest.mark.parametrize("ext", ["xlsx", "docx", "pdf"])
@pytest.mark.parametrize("stem", sorted(PACK))
def test_reads_the_test_pack_in_every_format(stem, ext):
    path = DATA / f"{stem}.{ext}"
    if not path.exists():
        pytest.skip(f"{path.name} not present")
    number, grand_total, vendor = PACK[stem]
    result = rules.extract([(path.name, path.read_bytes())])
    assert result["quotationNumber"] == number
    assert result["grandTotal"] == grand_total
    assert result["vendorName"] == vendor
    assert result["quotationDate"] == "2026-11-01"
    assert result["validUntil"] == "2026-12-01"
    assert result["currency"] == "INR"
    assert [i["itemCode"] for i in result["items"]] == ["IT-001", "IT-002", "IT-003", "IT-004"]
    assert all(i["quantity"] and i["unitPrice"] and i["lineTotal"] for i in result["items"])
    assert result["deliveryTerms"] and result["paymentTerms"] and result["warrantyTerms"]
    assert result["overallConfidence"] == 0.9


def test_spreadsheet_fractions_become_percentages():
    path = DATA / "Vendor_3_Apex_Quotation.xlsx"
    if not path.exists():
        pytest.skip("test pack not present")
    laptop = rules.extract([(path.name, path.read_bytes())])["items"][0]
    assert laptop["discountPercent"] == 3.0   # stored as 0.03
    assert laptop["taxPercent"] == 18.0       # stored as 0.18
    assert laptop["discountAmount"] == 37800.0


def test_ambiguous_gst_column_with_amounts_is_a_tax_amount():
    assert rules.header_key("GST") == "tax?"
    columns = {"tax?": 0}
    rules._resolve_ambiguous(columns, [["₹219,600.00"], ["₹39,600.00"]])
    assert columns == {"taxAmount": 0}
    columns = {"discount?": 0}
    rules._resolve_ambiguous(columns, [["2%"], ["0%"]])
    assert columns == {"discountPercent": 0}


def test_header_rules():
    assert rules.header_key("Unit Price (INR)") == "unitPrice"
    assert rules.header_key("Item Code") == "itemCode"
    assert rules.header_key("Taxable Value") is None
    assert rules.header_key("Discount Value") == "discountAmount"
    assert rules.header_key("GST %") == "taxPercent"
    assert rules.header_key("Line Total") == "lineTotal"


def test_several_files_of_one_quotation_are_cross_checked():
    files = [(p.name, p.read_bytes()) for p in sorted(DATA.glob("Vendor_1_Northstar_Quotation.*"))]
    if len(files) < 2:
        pytest.skip("test pack not present")
    result = rules.extract(files)
    assert result["grandTotal"] == 1778260.0
    assert len(result["items"]) == 4          # each line once, not once per file
    assert result["warnings"] == []           # the three files agree


def test_disagreeing_files_are_reported(xlsx_bytes, pdf_bytes):
    result = rules.extract([("a.xlsx", xlsx_bytes), ("c.pdf", pdf_bytes)])
    assert any("disagree on the grand total" in w for w in result["warnings"])


def test_validity_in_days_is_calculated_and_reported(xlsx_bytes):
    result = rules.extract([("a.xlsx", xlsx_bytes)])
    # the generated sample is dated today and valid for 30 days
    assert result["quotationDate"] == generate_samples.TODAY.isoformat()
    assert result["validUntil"] == (generate_samples.TODAY + timedelta(days=30)).isoformat()
    assert any("Valid for 30 days" in w for w in result["warnings"])


def test_unreadable_files_only_is_an_error():
    with pytest.raises(rules.NoReadableFileError):
        rules.extract([("broken.pdf", b"not a pdf")])


def test_extract_endpoint_matches_the_java_contract(xlsx_bytes):
    response = client.post("/extract", files=[("files", ("a.xlsx", xlsx_bytes, XLSX))])
    assert response.status_code == 200
    body = response.json()
    # every field of the Java ExtractedQuotation record must be present
    assert set(body) == {"vendorName", "quotationNumber", "quotationDate", "validUntil", "currency", "items",
                         "subtotal", "discountTotal", "taxTotal", "grandTotal", "deliveryTerms", "paymentTerms",
                         "warrantyTerms", "notes", "overallConfidence", "warnings"}
    assert set(body["items"][0]) == {"itemCode", "description", "quantity", "unitOfMeasure", "unitPrice",
                                     "discountPercent", "discountAmount", "taxPercent", "taxAmount", "lineTotal",
                                     "confidence", "notes"}


def test_extract_endpoint_rejects_unreadable_files():
    response = client.post("/extract", files=[("files", ("x.pdf", b"nope", "application/pdf"))])
    assert response.status_code == 422
