"""Rule-based quotation extraction: no AI, no API key, no rate limit.

The item table is found by its column headers (code, description, quantity, unit, unit price, discount, GST/tax,
line total). Totals, dates, currency and commercial terms are found by their labels ("Grand Total", "Valid Until",
"Payment:" ...), in table cells or in text lines. Values are reported as printed; the backend recalculates every
total and flags anything that does not add up.

Works on Excel, Word and text-based PDF quotations with a normal tabular layout. Scanned PDFs and free-form
letters need an AI engine. When several files of one quotation are read, the file with the most priced lines is
used and the others are cross-checked against it.
"""
import datetime
import re
from decimal import Decimal, InvalidOperation

from . import parsers

FORMAT_PRIORITY = {"xlsx": 0, "xls": 1, "docx": 2, "pdf": 3}

_TAG = re.compile(r"<[^>]+>")
_BR = re.compile(r"<br\s*/?>", re.IGNORECASE)
_NUMBER = re.compile(r"-?\d[\d,]*(?:\.\d+)?")
_SPACES = re.compile(r"[ \t]+")

# Labels of total rows. Order matters: the first match wins.
_TOTAL_LABELS = [
    ("grandTotal", re.compile(r"^(grand\s+total|total\s+(amount\s+)?payable|net\s+payable|amount\s+payable"
                              r"|total\s+amount(\s+incl.*)?|invoice\s+total|total\s*\(incl.*)", re.I)),
    (None, re.compile(r"^taxable", re.I)),  # after-discount value: recalculated by the backend, not needed
    ("subtotal", re.compile(r"^sub\s*-?\s*total", re.I)),
    ("discountTotal", re.compile(r"^(total\s+)?discount", re.I)),
    ("taxTotal", re.compile(r"^(total\s+)?(gst|igst|cgst|sgst|vat|tax)(\s+total|\s+amount|\s*@|\s*\(|\s*$)", re.I)),
]
_ROW_STOP = re.compile(r"^(sub\s*-?\s*total|grand\s+total|total\b|taxable|discount|gst\b|tax\b|vat\b|amount\s+payable"
                       r"|net\s+payable)", re.I)

# Header fields: label pattern -> field. The value is the rest of the cell/line, or the next cell/line.
_FIELD_LABELS = [
    ("quotationNumber", re.compile(r"^(quotation|quote|qtn|offer)\s*(no\.?|number|#|ref(erence)?\.?)\s*[:.\-]?\s*", re.I)),
    ("validUntil", re.compile(r"^(quotation\s+)?(valid(ity)?\s*(until|till|up\s*to|upto)?|validity\s+date)\s*[:.\-]?\s*", re.I)),
    ("quotationDate", re.compile(r"^(quotation\s+|quote\s+)?date(d)?\s*[:.\-]?\s*", re.I)),
    ("currency", re.compile(r"^currency\s*[:.\-]?\s*", re.I)),
    # A label alone or followed by ":" - so a heading such as "VENDOR QUOTATION" is not read as a name
    ("vendorName", re.compile(r"^(supplier|vendor)(\s+name)?\s*(:\s*|$)", re.I)),
    ("deliveryTerms", re.compile(r"^delivery(\s+terms|\s+period|\s+time|\s+schedule)?\s*[:.\-]?\s*", re.I)),
    ("paymentTerms", re.compile(r"^payment(\s+terms)?\s*[:.\-]?\s*", re.I)),
    ("warrantyTerms", re.compile(r"^warranty(\s+terms)?\s*[:.\-]?\s*", re.I)),
    ("freight", re.compile(r"^(freight|shipping|transport)\s*[:.\-]?\s*", re.I)),
    ("notes", re.compile(r"^(notes?|remarks?)\s*[:.\-]?\s*", re.I)),
]
_TEXT_FIELDS = {"deliveryTerms", "paymentTerms", "warrantyTerms", "freight", "notes"}

_MONTHS = {m: i for i, m in enumerate(
    ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"], start=1)}
_DATE_PATTERNS = [
    (re.compile(r"\b(\d{4})-(\d{1,2})-(\d{1,2})\b"), "ymd"),
    (re.compile(r"\b(\d{1,2})[\s\-]+([A-Za-z]{3,9})[\s\-,]+(\d{4})\b"), "dMy"),
    (re.compile(r"\b([A-Za-z]{3,9})\s+(\d{1,2}),?\s+(\d{4})\b"), "Mdy"),
    (re.compile(r"\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})\b"), "dmy"),
]
_CURRENCY_SYMBOLS = {"₹": "INR", "$": "USD", "€": "EUR", "£": "GBP"}
_CURRENCY_CODES = {"INR", "USD", "EUR", "GBP", "AED", "SGD", "JPY", "CNY", "AUD", "CAD", "CHF"}


class NoReadableFileError(ValueError):
    """None of the files could be read."""


def extract(files: list[tuple[str, bytes]]) -> dict:
    """Reads all files of one quotation and returns the fields in the backend's ExtractedQuotation shape."""
    readings, warnings = [], []
    for name, content in files:
        try:
            doc = parsers.parse(name, content)
        except parsers.UnreadableDocumentError as exc:
            warnings.append(f"{name}: {exc}")
            continue
        reading = read_document(doc)
        reading["fileName"] = name
        readings.append(reading)
    if not readings:
        raise NoReadableFileError("None of the files could be read. " + " ".join(warnings))

    readings.sort(key=lambda r: (-len(r["items"]), FORMAT_PRIORITY.get(r["format"], 9)))
    primary = readings[0]
    result = {key: primary.get(key) for key in (
        "vendorName", "quotationNumber", "quotationDate", "validUntil", "currency", "subtotal", "discountTotal",
        "taxTotal", "grandTotal", "deliveryTerms", "paymentTerms", "warrantyTerms", "notes")}
    for other in readings[1:]:
        for key, value in result.items():
            if value in (None, "") and other.get(key) not in (None, ""):
                result[key] = other[key]
        _compare(primary, other, warnings)

    result["items"] = primary["items"]
    warnings = primary["warnings"] + warnings
    if not primary["items"]:
        warnings.append("Rule-based reading found no item table (headers such as Description, Qty, Unit Price, "
                        "Total). Use an AI engine or enter the lines manually.")
    if result["grandTotal"] is None:
        warnings.append("Rule-based reading found no grand total to check the lines against.")
    complete = all(i["quantity"] is not None and i["unitPrice"] is not None for i in primary["items"])
    result["overallConfidence"] = 0.9 if primary["items"] and result["grandTotal"] is not None and complete else 0.6
    result["warnings"] = warnings
    return _jsonable(result)


def read_document(doc: parsers.Document) -> dict:
    """One file: item table, totals and header fields."""
    tables = [[[_clean(c) for c in row] for row in table] for table in doc.tables]
    lines = [_clean(line) for line in doc.lines]
    reading = {"format": doc.format, "items": [], "warnings": list(doc.warnings)}
    for key, _ in _FIELD_LABELS:
        reading[key] = None
    for key, _ in _TOTAL_LABELS:
        if key:
            reading[key] = None

    for table in tables:
        items = _items_from_table(table)
        if len(items) > len(reading["items"]):
            reading["items"] = items
    _totals(tables, lines, reading)
    _fields(tables, lines, reading)
    _fallbacks(tables, lines, reading)

    if reading.get("freight"):
        freight = "Freight: " + reading.pop("freight")
        reading["notes"] = f"{reading['notes']} {freight}" if reading.get("notes") else freight
    reading.pop("freight", None)
    return reading


# ---------------------------------------------------------------- item table

def header_key(text: str) -> str | None:
    """Maps a column header to a field, e.g. 'Unit Price (INR)' -> unitPrice, 'GST %' -> taxPercent."""
    t = re.sub(r"[^a-z%]+", " ", text.lower()).strip()
    if not t:
        return None
    if re.search(r"\b(code|sku|part|model\s+no)\b", t):
        return "itemCode"
    if "description" in t or t in ("item", "items", "particulars", "product", "goods", "services", "item name"):
        return "description"
    if re.search(r"\b(qty|quantity)\b", t):
        return "quantity"
    if re.search(r"\b(uom|unit of measure)\b", t) or t in ("unit", "units"):
        return "unitOfMeasure"
    if re.search(r"\b(unit\s+(price|rate|cost)|rate|price)\b", t) and "total" not in t:
        return "unitPrice"
    if "taxable" in t or "gross" in t or "net value" in t:
        return None
    if re.search(r"\b(disc|discount)\b", t):
        if "%" in t or "percent" in t or "rate" in t:
            return "discountPercent"
        if re.search(r"\b(value|amount|amt)\b", t):
            return "discountAmount"
        return "discount?"
    if re.search(r"\b(gst|igst|cgst|sgst|tax|vat)\b", t):
        if "%" in t or "percent" in t or "rate" in t:
            return "taxPercent"
        if re.search(r"\b(value|amount|amt)\b", t):
            return "taxAmount"
        return "tax?"
    if "total" in t or "amount" in t:
        return "lineTotal"
    return None


def _items_from_table(table: list[list[str]]) -> list[dict]:
    for h, header in enumerate(table):
        columns = {}
        for index, cell in enumerate(header):
            key = header_key(cell)
            if key and key not in columns:
                columns[key] = index
        if "description" not in columns or not ({"quantity", "unitPrice", "lineTotal"} & columns.keys()):
            continue
        body = []
        for row in table[h + 1:]:
            cells = [c for c in row if c]
            if not cells:
                continue
            if _ROW_STOP.match(cells[0]):
                break
            body.append(row)
        _resolve_ambiguous(columns, body)
        items = [item for item in (_item(row, columns) for row in body) if item]
        if items:
            return items
    return []


def _resolve_ambiguous(columns: dict, body: list[list[str]]) -> None:
    """'Disc.' or 'GST' columns hold either percentages or amounts: decide from the values."""
    for key, percent, amount in (("discount?", "discountPercent", "discountAmount"),
                                 ("tax?", "taxPercent", "taxAmount")):
        if key not in columns:
            continue
        index = columns.pop(key)
        values = [row[index] for row in body if index < len(row) and row[index]]
        numbers = [n for n in (_number(v) for v in values) if n is not None]
        is_percent = any("%" in v for v in values) or (numbers and all(abs(n) <= 1 for n in numbers))
        target = percent if is_percent else amount
        columns.setdefault(target, index)


def _item(row: list[str], columns: dict) -> dict | None:
    def cell(key):
        index = columns.get(key)
        return row[index] if index is not None and index < len(row) else ""

    description = cell("description")
    if not description:
        return None
    quantity, unit_price, line_total = _number(cell("quantity")), _number(cell("unitPrice")), _number(cell("lineTotal"))
    if quantity is None and unit_price is None and line_total is None:
        return None
    complete = quantity is not None and unit_price is not None and line_total is not None
    return {
        "itemCode": cell("itemCode") or None,
        "description": description,
        "quantity": quantity,
        "unitOfMeasure": cell("unitOfMeasure") or None,
        "unitPrice": unit_price,
        "discountPercent": _percent(cell("discountPercent")),
        "discountAmount": _abs(_number(cell("discountAmount"))),
        "taxPercent": _percent(cell("taxPercent")),
        "taxAmount": _number(cell("taxAmount")),
        "lineTotal": line_total,
        "confidence": 0.9 if complete else 0.6,
        "notes": None,
    }


# ---------------------------------------------------------------- totals and fields

def _totals(tables, lines, reading) -> None:
    candidates = [[c for c in row if c] for table in tables for row in table]
    candidates += [[line] for line in lines]
    for cells in candidates:
        if not cells:
            continue
        label = cells[0]
        for key, pattern in _TOTAL_LABELS:
            if not pattern.match(label):
                continue
            if key and reading.get(key) is None:
                # The amount is the last number in the row: "GST @ 18% | 271,260.00" -> 271260.00
                value = _last_number(" ".join(cells[1:]) if len(cells) > 1 else _after_label(label, pattern))
                if value is not None:
                    reading[key] = _abs(value) if key == "discountTotal" else value
            break


def _fields(tables, lines, reading) -> None:
    sequences = [[c for c in row if c] for table in tables for row in table] + [lines]
    for cells in sequences:
        for i, cell in enumerate(cells):
            first_line = cell.split("\n", 1)[0]
            for key, pattern in _FIELD_LABELS:
                m = pattern.match(first_line)
                if not m or reading.get(key):
                    continue
                rest = cell[m.end():].strip(" :\n")
                if not rest and i + 1 < len(cells) and not _is_label(cells[i + 1]):
                    rest = cells[i + 1]
                value = _field_value(key, rest)
                if value:
                    reading[key] = value
                break


def _fallbacks(tables, lines, reading) -> None:
    """Fields many quotations print without a label."""
    texts = lines + [c for table in tables for row in table for c in row if c]
    if not reading.get("vendorName"):
        # The letterhead: the first line that is a name, not a heading, label, column header or amount
        for text in texts:
            first = text.split("\n", 1)[0]
            if (re.search(r"[A-Za-z]{3}", first) and ":" not in first and not _NUMBER.search(first)
                    and header_key(first) is None
                    and not re.search(r"quotation|quote|invoice|proforma|demo|rfq", first, re.I)):
                reading["vendorName"] = first.strip()
                break
    if not reading.get("currency"):
        # e.g. a column header "Unit Price (INR)" or amounts printed as "₹1,200"
        for text in texts:
            currency = _currency(text) if re.search(r"\b[A-Z]{3}\b|[₹$€£]", text) else None
            if currency:
                reading["currency"] = currency
                break
    if not reading.get("validUntil") and reading.get("quotationDate"):
        # "Valid for 30 days" -> quotation date + 30 days, reported so the reviewer can confirm it
        for text in texts:
            m = re.search(r"valid(?:ity)?\s*(?:for|:)?\s*(\d{1,3})\s*days", text, re.I)
            if m:
                start = datetime.date.fromisoformat(reading["quotationDate"])
                reading["validUntil"] = (start + datetime.timedelta(days=int(m.group(1)))).isoformat()
                reading["warnings"].append(f"Validity given as \"{m.group(0)}\"; valid-until date "
                                           f"{reading['validUntil']} was calculated from the quotation date.")
                break


def _is_label(text: str) -> bool:
    return any(p.match(text.split("\n", 1)[0]) and not text[p.match(text).end():].strip() for _, p in _FIELD_LABELS)


def _field_value(key: str, raw: str):
    if not raw:
        return None
    if key in ("quotationDate", "validUntil"):
        return _date(raw)
    if key == "currency":
        return _currency(raw)
    if key in _TEXT_FIELDS:
        return _SPACES.sub(" ", raw.replace("\n", " ")).strip()
    return raw.split("\n", 1)[0].strip() or None


# ---------------------------------------------------------------- cross-file check

def _compare(primary: dict, other: dict, warnings: list[str]) -> None:
    name, other_name = primary["fileName"], other["fileName"]
    for key, label in (("grandTotal", "grand total"), ("taxTotal", "tax total"), ("subtotal", "subtotal")):
        a, b = primary.get(key), other.get(key)
        if a is not None and b is not None and a != b:
            warnings.append(f"The files disagree on the {label}: {name} shows {a}, {other_name} shows {b}.")
    if other["items"] and len(other["items"]) != len(primary["items"]):
        warnings.append(f"{name} has {len(primary['items'])} priced lines but {other_name} has {len(other['items'])}.")
    by_code = {i["itemCode"]: i for i in other["items"] if i["itemCode"]}
    for item in primary["items"]:
        twin = by_code.get(item["itemCode"]) if item["itemCode"] else None
        if twin and twin["lineTotal"] is not None and item["lineTotal"] is not None \
                and twin["lineTotal"] != item["lineTotal"]:
            warnings.append(f"Line {item['itemCode']}: {name} shows {item['lineTotal']}, "
                            f"{other_name} shows {twin['lineTotal']}.")


# ---------------------------------------------------------------- value parsing

def _clean(cell) -> str:
    if cell is None:
        return ""
    if isinstance(cell, (datetime.date, datetime.datetime)):
        return cell.strftime("%Y-%m-%d")
    text = _BR.sub("\n", str(cell))
    text = _TAG.sub(" ", text)
    lines = [_SPACES.sub(" ", line).strip() for line in text.split("\n")]
    return "\n".join(line for line in lines if line)


def _number(text) -> Decimal | None:
    if text is None:
        return None
    match = _NUMBER.search(str(text).replace(" ", ""))
    if not match:
        return None
    try:
        return Decimal(match.group(0).replace(",", ""))
    except InvalidOperation:
        return None


def _last_number(text: str) -> Decimal | None:
    numbers = [m for m in _NUMBER.finditer(text or "")
               if not text[m.end():m.end() + 1] == "%"]  # skip rates such as "18%"
    if not numbers:
        return None
    try:
        return Decimal(numbers[-1].group(0).replace(",", ""))
    except InvalidOperation:
        return None


def _after_label(text: str, pattern) -> str:
    m = pattern.match(text)
    return text[m.end():] if m else ""


def _percent(text: str) -> Decimal | None:
    """'2%' -> 2; spreadsheets store 3% as 0.03 -> 3."""
    value = _number(text)
    if value is None:
        return None
    if "%" not in text and 0 < value < 1:
        value = value * 100
    return value.normalize() if value == value.to_integral() else value


def _abs(value):
    return abs(value) if value is not None else None


def _date(text: str) -> str | None:
    for pattern, kind in _DATE_PATTERNS:
        m = pattern.search(text)
        if not m:
            continue
        try:
            if kind == "ymd":
                y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
            elif kind == "dmy":
                d, mo, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
            elif kind == "dMy":
                d, mo, y = int(m.group(1)), _MONTHS.get(m.group(2)[:3].lower()), int(m.group(3))
            else:
                mo, d, y = _MONTHS.get(m.group(1)[:3].lower()), int(m.group(2)), int(m.group(3))
            if mo:
                return datetime.date(y, mo, d).isoformat()
        except ValueError:
            continue
    return None


def _currency(text: str) -> str | None:
    for code in re.findall(r"\b[A-Z]{3}\b", text.upper()):
        if code in _CURRENCY_CODES:
            return code
    for symbol, code in _CURRENCY_SYMBOLS.items():
        if symbol in text:
            return code
    return None


def _jsonable(value):
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, dict):
        return {k: _jsonable(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_jsonable(v) for v in value]
    return value
