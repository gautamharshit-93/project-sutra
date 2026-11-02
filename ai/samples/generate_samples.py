"""Builds three sample vendor quotations (Excel, Word, PDF) for one purchase request.

Purchase request used in the demo:
    1. Laptop - Dell Latitude 5440, i5, 16GB RAM, 512GB SSD   10 Nos
    2. 24-inch LED Monitor, Full HD                             10 Nos
    3. Wireless Keyboard and Mouse Combo                        10 Nos

Run:  .venv\\Scripts\\python samples\\generate_samples.py   (writes into samples/)
"""
import io
from datetime import date, timedelta
from pathlib import Path

TODAY = date.today()


def build_xlsx(valid_days: int = 30) -> bytes:
    """Vendor A - Excel quotation with a GST % column and totals rows."""
    import openpyxl

    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Quotation"
    ws.append(["Alpha Computers Pvt Ltd"])
    ws.append(["Quotation No: AC/Q/2026/118"])
    ws.append(["Quotation Date:", TODAY.strftime("%d/%m/%Y")])
    ws.append([f"Valid for {valid_days} days"])
    ws.append([])
    ws.append(["S.No", "Description", "Qty", "UOM", "Unit Price (INR)", "GST %", "Amount"])
    rows = [
        (1, "Dell Latitude 5440 Laptop i5 16GB 512GB SSD", 10, "Nos", 68500, 18, 808300),
        (2, "Dell 24 inch Full HD LED Monitor P2422H", 10, "Nos", 11200, 18, 132160),
        (3, "Logitech MK270 Wireless Keyboard Mouse Combo", 10, "Nos", 1650, 18, 19470),
    ]
    for row in rows:
        ws.append(list(row))
    ws.append([])
    ws.append(["", "Sub Total", "", "", "", "", 813500])
    ws.append(["", "GST", "", "", "", "", 146430])
    ws.append(["", "Grand Total", "", "", "", "", 959930])
    ws.append([])
    ws.append(["Payment Terms: 30 days from invoice"])
    ws.append(["Delivery: 2 weeks from PO"])
    ws.append(["Warranty: 3 years onsite"])
    out = io.BytesIO()
    wb.save(out)
    return out.getvalue()


def build_docx() -> bytes:
    """Vendor B - Word quotation; omits the keyboard combo and quotes a different monitor."""
    import docx

    document = docx.Document()
    document.add_heading("Beta Systems & Solutions", level=1)
    document.add_paragraph("Quote No: BSS-7781")
    document.add_paragraph(f"Date: {TODAY.strftime('%d-%m-%Y')}")
    document.add_paragraph(f"Valid until: {(TODAY + timedelta(days=21)).strftime('%d-%m-%Y')}")
    table = document.add_table(rows=1, cols=6)
    for cell, text in zip(table.rows[0].cells, ["Item", "Description", "Quantity", "Unit", "Rate", "Total"]):
        cell.text = text
    for row in [
        ("1", "Laptop Dell Latitude 5440 (Core i5, 16 GB RAM, 512 GB SSD)", "10", "Nos", "66,900.00", "6,69,000.00"),
        ("2", "LG 24MP400 24\" Full HD Monitor", "10", "Nos", "10,450.00", "1,04,500.00"),
    ]:
        cells = table.add_row().cells
        for cell, text in zip(cells, row):
            cell.text = text
    document.add_paragraph("Sub Total: 7,73,500.00")
    document.add_paragraph("GST 18%: 1,39,230.00")
    document.add_paragraph("Grand Total: 9,12,730.00")
    document.add_paragraph("Payment Terms: 50% advance, balance on delivery")
    document.add_paragraph("Delivery: 3-4 weeks")
    out = io.BytesIO()
    document.save(out)
    return out.getvalue()


def build_pdf() -> bytes:
    """Vendor C - PDF quotation with a ruled table and a 5% discount column."""
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import getSampleStyleSheet
    from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

    out = io.BytesIO()
    pdf = SimpleDocTemplate(out, pagesize=A4)
    styles = getSampleStyleSheet()
    story = [
        Paragraph("Gamma IT Traders", styles["Title"]),
        Paragraph("Quotation No: GIT-2026-0042", styles["Normal"]),
        Paragraph(f"Quotation Date: {TODAY.isoformat()}", styles["Normal"]),
        Paragraph(f"Valid till: {(TODAY + timedelta(days=45)).isoformat()}", styles["Normal"]),
        Spacer(1, 12),
    ]
    data = [
        ["Description", "Qty", "Unit", "Rate", "Disc %", "Amount"],
        ["Dell Latitude 5440 Laptop, i5/16GB/512GB", "10", "Nos", "70000.00", "5", "665000.00"],
        ["Samsung 24 inch FHD Monitor LS24C310", "10", "Nos", "9800.00", "5", "93100.00"],
        ["Wireless Keyboard & Mouse Combo (Dell KM3322W)", "10", "Nos", "1500.00", "5", "14250.00"],
        ["Sub Total", "", "", "", "", "772350.00"],
        ["GST 18%", "", "", "", "", "139023.00"],
        ["Grand Total", "", "", "", "", "911373.00"],
    ]
    table = Table(data)
    table.setStyle(TableStyle([("GRID", (0, 0), (-1, -1), 0.5, colors.black)]))
    story += [table, Spacer(1, 12),
              Paragraph("Payment Terms: 45 days credit", styles["Normal"]),
              Paragraph("Delivery: Within 10 working days", styles["Normal"]),
              Paragraph("Warranty: 3 years", styles["Normal"])]
    pdf.build(story)
    return out.getvalue()


if __name__ == "__main__":
    folder = Path(__file__).parent
    (folder / "vendor_a_alpha_computers.xlsx").write_bytes(build_xlsx())
    (folder / "vendor_b_beta_systems.docx").write_bytes(build_docx())
    (folder / "vendor_c_gamma_it.pdf").write_bytes(build_pdf())
    print("Sample quotations written to", folder)
