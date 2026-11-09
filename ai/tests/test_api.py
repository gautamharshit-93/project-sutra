from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"


def test_health():
    assert client.get("/health").json() == {"status": "UP"}


def test_parse_pdf_returns_text(pdf_bytes):
    response = client.post("/parse", files={"file": ("c.pdf", pdf_bytes, "application/pdf")})
    assert response.status_code == 200
    body = response.json()
    assert body["format"] == "pdf"
    assert "Dell Latitude 5440" in body["text"]
    assert body["charCount"] == len(body["text"])
    assert body["likelyScanned"] is False


def test_parse_matches_the_java_contract(xlsx_bytes):
    response = client.post("/parse", files={"file": ("a.xlsx", xlsx_bytes, XLSX)})
    assert response.status_code == 200
    body = response.json()
    # every field of the Java ParsedDocument record must be present
    assert set(body) == {"format", "text", "pages", "charCount", "likelyScanned", "warnings"}
    # spreadsheet rows become pipe-separated lines that Groq reads as a table
    assert "Dell Latitude 5440 Laptop i5 16GB 512GB SSD | 10 | Nos | 68500 | 18 | 808300" in body["text"]


def test_parse_word_includes_paragraphs_and_tables(docx_bytes):
    body = client.post("/parse", files={"file": ("b.docx", docx_bytes, DOCX)}).json()
    assert "Quote No: BSS-7781" in body["text"]
    assert "[Table 1]" in body["text"]


def test_corrupt_file_returns_422():
    response = client.post("/parse", files={"file": ("bad.pdf", b"not a pdf", "application/pdf")})
    assert response.status_code == 422
    assert "could not be read" in response.json()["detail"]


def test_unsupported_type_returns_422():
    response = client.post("/parse", files={"file": ("q.txt", b"hello", "text/plain")})
    assert response.status_code == 422


def test_match_endpoint():
    payload = {
        "requestItems": [{"id": 7, "description": "LED Monitor 24 inch"}, {"id": 8, "description": "Laser printer"}],
        "quotedItems": [{"index": 0, "description": "24\" LED monitor full HD"}],
    }
    scores = client.post("/match", json=payload).json()["scores"]
    best = max(scores, key=lambda s: s["score"])
    assert best["requestItemId"] == 7 and best["quotedIndex"] == 0
