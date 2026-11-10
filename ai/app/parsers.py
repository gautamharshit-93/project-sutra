"""Converts PDF, Excel and Word files into text lines plus tables (lists of cell rows)."""
import datetime
import io
import zipfile
from dataclasses import dataclass, field

SUPPORTED = {"pdf", "xlsx", "xls", "docx"}


class UnreadableDocumentError(ValueError):
    """The file could not be opened or has an unsupported type."""


@dataclass
class Document:
    format: str
    pages: int = 1
    lines: list[str] = field(default_factory=list)
    tables: list[list[list]] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def as_text(self) -> str:
        """Plain text for an LLM or a human: free text first, then every table as pipe-separated rows."""
        parts = ["\n".join(self.lines)] if self.lines else []
        for number, table in enumerate(self.tables, start=1):
            rows = [" | ".join(_cell_text(c) for c in row) for row in table if any(_cell_text(c) for c in row)]
            if rows:
                parts.append(f"[Table {number}]\n" + "\n".join(rows))
        return "\n\n".join(parts).strip()


def _cell_text(cell) -> str:
    if cell is None:
        return ""
    text = str(cell).strip()
    return text.replace("\n", " ")


def extension_of(file_name: str) -> str:
    return file_name.rsplit(".", 1)[-1].lower() if "." in file_name else ""


def parse(file_name: str, content: bytes) -> Document:
    ext = extension_of(file_name)
    if ext not in SUPPORTED:
        raise UnreadableDocumentError(f"Unsupported file type '.{ext}'. Use PDF, XLSX, XLS or DOCX.")
    if not content:
        raise UnreadableDocumentError("The file is empty.")
    try:
        if ext == "pdf":
            return _parse_pdf(content)
        if ext == "xlsx":
            return _parse_xlsx(content)
        if ext == "xls":
            return _parse_xls(content)
        return _parse_docx(content)
    except UnreadableDocumentError:
        raise
    except Exception as exc:  # parser libraries raise many different types for corrupt files
        raise UnreadableDocumentError(f"The {ext.upper()} file could not be read: {type(exc).__name__}") from exc


def _parse_pdf(content: bytes) -> Document:
    import pdfplumber

    doc = Document(format="pdf")
    with pdfplumber.open(io.BytesIO(content)) as pdf:
        doc.pages = len(pdf.pages)
        for page in pdf.pages:
            text = page.extract_text() or ""
            doc.lines.extend(line for line in text.splitlines() if line.strip())
            for table in page.extract_tables() or []:
                if table and len(table) > 1:
                    doc.tables.append(table)
    if len("".join(doc.lines)) < 20 * max(doc.pages, 1):
        doc.warnings.append("Very little text was found; this PDF is probably scanned. "
                            "The backend reads it with OCR (PDFBox + Tesseract).")
    return doc


def _parse_xlsx(content: bytes) -> Document:
    import openpyxl

    if not zipfile.is_zipfile(io.BytesIO(content)):
        raise UnreadableDocumentError("The XLSX file is not a valid Office Open XML package.")
    workbook = openpyxl.load_workbook(io.BytesIO(content), data_only=True, read_only=True)
    doc = Document(format="xlsx", pages=len(workbook.sheetnames))
    formula_without_value = False
    for sheet in workbook.worksheets:
        rows = []
        for row in sheet.iter_rows():
            values = [_xlsx_display_value(cell) for cell in row]
            while values and values[-1] is None:
                values.pop()
            if any(v is not None and str(v).strip() for v in values):
                rows.append(values)
        if rows:
            doc.tables.append(rows)
    workbook.close()
    # Formula cells saved by tools that don't cache results come back as None.
    formula_check = openpyxl.load_workbook(io.BytesIO(content), data_only=False, read_only=True)
    cached = openpyxl.load_workbook(io.BytesIO(content), data_only=True, read_only=True)
    for f_sheet, v_sheet in zip(formula_check.worksheets, cached.worksheets):
        for f_row, v_row in zip(f_sheet.iter_rows(values_only=True), v_sheet.iter_rows(values_only=True)):
            if any(isinstance(f, str) and f.startswith("=") and v is None for f, v in zip(f_row, v_row)):
                formula_without_value = True
                break
    formula_check.close()
    cached.close()
    if formula_without_value:
        doc.warnings.append("Some formula cells have no saved value; open and re-save the file in Excel.")
    return doc


def _xlsx_display_value(cell):
    """The value as the spreadsheet shows it: 0.18 formatted as a percentage becomes "18%", and a date-only
    datetime becomes "2026-11-01". Without this, a reader would see 0.18 and could take it for 0.18%."""
    value = getattr(cell, "value", None)
    if value is None or isinstance(value, bool):
        return value
    number_format = getattr(cell, "number_format", "") or ""
    if isinstance(value, (int, float)) and "%" in number_format:
        return f"{value * 100:g}%"
    if isinstance(value, datetime.datetime):
        return value.date().isoformat() if value.time() == datetime.time(0, 0) else value.isoformat(sep=" ")
    if isinstance(value, datetime.date):
        return value.isoformat()
    return value


def _parse_xls(content: bytes) -> Document:
    import xlrd

    book = xlrd.open_workbook(file_contents=content)
    doc = Document(format="xls", pages=book.nsheets)
    for sheet in book.sheets():
        rows = []
        for r in range(sheet.nrows):
            values = []
            for c in range(sheet.ncols):
                cell = sheet.cell(r, c)
                if cell.ctype == xlrd.XL_CELL_DATE:
                    values.append(xlrd.xldate.xldate_as_datetime(cell.value, book.datemode))
                elif cell.ctype in (xlrd.XL_CELL_EMPTY, xlrd.XL_CELL_BLANK):
                    values.append(None)
                else:
                    values.append(cell.value)
            while values and values[-1] is None:
                values.pop()
            if any(v is not None and str(v).strip() for v in values):
                rows.append(values)
        if rows:
            doc.tables.append(rows)
    return doc


def _parse_docx(content: bytes) -> Document:
    import docx

    document = docx.Document(io.BytesIO(content))
    doc = Document(format="docx")
    doc.lines = [p.text.strip() for p in document.paragraphs if p.text.strip()]
    for table in document.tables:
        rows = []
        for row in table.rows:
            cells = []
            previous = None
            for cell in row.cells:
                # merged cells repeat the same object; keep one copy
                if cell._tc is previous:
                    continue
                previous = cell._tc
                cells.append(cell.text.strip())
            rows.append(cells)
        if rows:
            doc.tables.append(rows)
    return doc
