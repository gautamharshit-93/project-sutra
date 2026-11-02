import pytest

from app import parsers


def test_parse_xlsx_returns_rows(xlsx_bytes):
    doc = parsers.parse("q.xlsx", xlsx_bytes)
    assert doc.format == "xlsx"
    assert any("Dell Latitude 5440" in str(c) for row in doc.tables[0] for c in row)
    assert "Quotation No: AC/Q/2026/118" in doc.as_text()


def test_parse_docx_returns_paragraphs_and_table(docx_bytes):
    doc = parsers.parse("q.docx", docx_bytes)
    assert "Quote No: BSS-7781" in doc.lines
    assert doc.tables[0][0] == ["Item", "Description", "Quantity", "Unit", "Rate", "Total"]


def test_parse_pdf_extracts_ruled_table(pdf_bytes):
    doc = parsers.parse("q.pdf", pdf_bytes)
    assert doc.pages == 1
    assert doc.tables and doc.tables[0][0][0] == "Description"
    assert not doc.warnings, "a digital PDF must not be reported as scanned"


def test_xlsx_shows_percent_formatted_cells_and_dates_as_displayed():
    import io
    from datetime import datetime

    import openpyxl

    wb = openpyxl.Workbook()
    ws = wb.active
    ws.append(["Item Code", "Discount", "GST", "Date"])
    ws.append(["IT-001", 0.02, 0.18, datetime(2026, 11, 1)])
    ws["B2"].number_format = "0%"
    ws["C2"].number_format = "0.00%"
    out = io.BytesIO()
    wb.save(out)

    text = parsers.parse("q.xlsx", out.getvalue()).as_text()

    assert "IT-001 | 2% | 18% | 2026-11-01" in text


def test_unsupported_extension_is_rejected():
    with pytest.raises(parsers.UnreadableDocumentError, match="Unsupported file type"):
        parsers.parse("q.doc", b"anything")


@pytest.mark.parametrize("name", ["q.pdf", "q.xlsx", "q.xls", "q.docx"])
def test_corrupt_files_raise_readable_error(name):
    with pytest.raises(parsers.UnreadableDocumentError):
        parsers.parse(name, b"this is not a real document")


def test_empty_file_is_rejected():
    with pytest.raises(parsers.UnreadableDocumentError, match="empty"):
        parsers.parse("q.pdf", b"")
