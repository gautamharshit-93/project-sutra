"""Generates the automotive test pack in ai/test-data: one RFQ, a materials catalogue and three vendor quotations.

Every company, person, number and price in the pack is fictional demo data. Run with the ai venv:
    ai\\.venv\\Scripts\\python ai\\scripts\\generate_data_pack.py
Each vendor sends a PDF quotation and an Excel material list with the same prices; the grand totals are printed
and written to ai/test-data/README.txt so the system's own calculation can be checked against them.
"""
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

from docx import Document
from docx.shared import Pt
from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

DATA = Path(__file__).resolve().parents[1] / "test-data"
RED = "D0102B"
BLACK = "111111"
BUYER = "Axiom Auto Components Pvt Ltd (Demo)"
BUYER_ADDRESS = "Plot 14, Auto Cluster, Chakan MIDC, Pune 410501"
RFQ_REF = "SUTRA-RFQ-2026-001"
QUOTE_DATE = date(2026, 11, 3)
VALID_UNTIL = date(2026, 12, 3)
GST = Decimal("18")

# What the buyer asks for: code, material, specification, quantity, unit
ITEMS = [
    ("MT-101", "Cold-rolled steel sheet (CRCA), 1.2 mm", "IS 513 Grade D, oiled, 1250 x 2500 mm sheets", 12, "Tonne"),
    ("MT-102", "Aluminium alloy ingot ADC12", "Die-casting grade, JIS H 5302, 7 kg ingots", 8, "Tonne"),
    ("MT-103", "EPDM rubber compound, 70 Shore A", "For door seals and gaskets, -40 to 120 C, ASTM D2000", 1500, "Kg"),
    ("MT-104", "Deep-groove ball bearing 6204-2RS", "20 x 47 x 14 mm, rubber sealed both sides", 2000, "Nos"),
    ("MT-105", "Hex flange bolt M8 x 25, grade 8.8", "Zinc-plated, ISO 4162, packed 500 per box", 10000, "Nos"),
]

# Vendor quotations: unit price per item code, optional line discount %, terms
VENDORS = [
    {
        "file": "Vendor_1_Shakti_Metals", "name": "Shakti Metals & Components (Demo)", "code": "SHAKTI",
        "address": "B-22, Bhosari Industrial Estate, Pune 411026", "gstin": "27AAACS0000A1Z1",
        "contact": "Rohan Kulkarni", "email": "sales@shakti-metals.example", "quote_no": "SMC/Q/26-27/041",
        "prices": {"MT-101": 62500, "MT-102": 231000, "MT-103": 265, "MT-104": 54, "MT-105": 3.40},
        "discounts": {},
        "delivery": "Within 15 days of purchase order, delivered to Chakan plant",
        "payment": "30 days from invoice",
        "warranty": "Mill test certificate with every lot; replacement of non-conforming material",
        "freight": "Included",
    },
    {
        "file": "Vendor_2_Pioneer_Industrial", "name": "Pioneer Industrial Supplies (Demo)", "code": "PIONEER",
        "address": "Gat 118, Talawade Road, Pune 411062", "gstin": "27AAACP0000B1Z2",
        "contact": "Meera Shah", "email": "quotes@pioneer-industrial.example", "quote_no": "PIS-2026-1187",
        "prices": {"MT-101": 61200, "MT-102": 236000, "MT-103": 258, "MT-104": 57, "MT-105": 3.25},
        "discounts": {},
        "delivery": "Steel and aluminium in 10 days; rubber, bearings and bolts in 21 days",
        "payment": "45 days from invoice",
        "warranty": "12 months on bearings; test certificates for steel, aluminium and rubber",
        "freight": "Included",
    },
    {
        "file": "Vendor_3_Vertex_Auto", "name": "Vertex Auto Materials (Demo)", "code": "VERTEX",
        "address": "S. No. 54, Nanded Phata, Pune 411041", "gstin": "27AAACV0000C1Z3",
        "contact": "Arjun Desai", "email": "rfq@vertex-auto.example", "quote_no": "VAM/RFQ/2611",
        "prices": {"MT-101": 63000, "MT-102": 229500, "MT-103": 262, "MT-104": 52, "MT-105": 3.30},
        "discounts": {"MT-101": 2, "MT-102": 2},
        "delivery": "Within 12 days of purchase order",
        "payment": "50% advance, balance within 30 days",
        # Deliberately incomplete, so the system flags it for review
        "warranty": "Bearing warranty to be confirmed",
        "freight": "Extra at actuals",
    },
]

# Every material type the buyer procures (the catalogue sheet)
CATALOGUE = [
    ("Steel", "MT-101", "Cold-rolled steel sheet (CRCA), 1.2 mm", "IS 513 Grade D", "Tonne", "Body brackets, panels"),
    ("Steel", "MT-111", "Hot-rolled steel coil, 3 mm", "IS 2062 E250", "Tonne", "Chassis members"),
    ("Steel", "MT-112", "Seamless steel tube 25 x 2 mm", "ASTM A519 1020", "Metre", "Seat frames"),
    ("Aluminium", "MT-102", "Aluminium alloy ingot ADC12", "JIS H 5302", "Tonne", "Gearbox and pump housings"),
    ("Aluminium", "MT-121", "Aluminium extrusion 6063-T6", "IS 733", "Kg", "Roof rails, trims"),
    ("Rubber & polymers", "MT-103", "EPDM rubber compound, 70 Shore A", "ASTM D2000", "Kg", "Door seals, gaskets"),
    ("Rubber & polymers", "MT-131", "Nitrile (NBR) O-ring stock", "ASTM D2000 BG", "Kg", "Fuel and oil seals"),
    ("Rubber & polymers", "MT-132", "Polypropylene granules, talc-filled", "PP-TD20", "Kg", "Interior panels"),
    ("Bearings", "MT-104", "Deep-groove ball bearing 6204-2RS", "ISO 15", "Nos", "Pumps, alternators"),
    ("Bearings", "MT-141", "Tapered roller bearing 30205", "ISO 355", "Nos", "Wheel hubs"),
    ("Fasteners", "MT-105", "Hex flange bolt M8 x 25, grade 8.8", "ISO 4162", "Nos", "General assembly"),
    ("Fasteners", "MT-151", "Nyloc nut M8, grade 8", "ISO 7040", "Nos", "General assembly"),
    ("Fasteners", "MT-152", "Spring washer M8", "DIN 127", "Nos", "General assembly"),
    ("Friction materials", "MT-161", "Brake friction compound, low-metallic", "ECE R90", "Kg", "Brake pads"),
    ("Consumables", "MT-171", "Zinc phosphate pre-treatment chemical", "IS 6005", "Litre", "Paint shop"),
]


def money(x):
    return Decimal(str(x)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def lines_for(vendor):
    """The quoted lines with every amount, as the vendor prints them."""
    rows = []
    for code, desc, _spec, qty, unit in ITEMS:
        price = Decimal(str(vendor["prices"][code]))
        disc = Decimal(str(vendor["discounts"].get(code, 0)))
        gross = money(price * qty)
        discount = money(gross * disc / 100)
        taxable = gross - discount
        gst = money(taxable * GST / 100)
        rows.append({"code": code, "desc": desc, "qty": qty, "unit": unit, "price": price, "disc": disc,
                     "gross": gross, "discount": discount, "taxable": taxable, "gst": gst, "total": taxable + gst})
    totals = {k: sum((r[k] for r in rows), Decimal("0")) for k in ("gross", "discount", "taxable", "gst", "total")}
    return rows, totals


def fmt(d):
    return f"{d:,.2f}"


def qty_text(q):
    return f"{q:,}"


# ---------------------------------------------------------------------- PDF quotation
def write_pdf(vendor, rows, totals):
    path = DATA / f"{vendor['file']}_Quotation.pdf"
    doc = SimpleDocTemplate(str(path), pagesize=A4, leftMargin=14 * mm, rightMargin=14 * mm, topMargin=12 * mm,
                            bottomMargin=12 * mm, title=f"Quotation {vendor['quote_no']}", author=vendor["name"])
    styles = getSampleStyleSheet()
    small = styles["BodyText"].clone("small", fontSize=8.5, leading=11)
    head = styles["Title"].clone("head", fontSize=16, textColor=colors.HexColor("#" + BLACK), alignment=0, spaceAfter=2)
    story = [
        Paragraph(vendor["name"], head),
        Paragraph(f"{vendor['address']} &nbsp;|&nbsp; GSTIN {vendor['gstin']} &nbsp;|&nbsp; {vendor['email']}", small),
        Spacer(1, 6),
        Table([[Paragraph("<b>QUOTATION</b>", styles["Heading2"])]], colWidths=[182 * mm],
              style=[("LINEBELOW", (0, 0), (-1, -1), 2, colors.HexColor("#" + RED))]),
        Spacer(1, 4),
    ]
    # One label per line, so each value can be read on its own (also by the rule-based reader)
    info = [
        ["Quotation No:", vendor["quote_no"]],
        ["Quotation Date:", QUOTE_DATE.strftime("%d %b %Y")],
        ["Valid Until:", VALID_UNTIL.strftime("%d %b %Y")],
        ["Currency:", "INR"],
        ["RFQ Ref:", RFQ_REF],
        ["Customer:", f"{BUYER}, {BUYER_ADDRESS}"],
        ["Contact:", vendor["contact"]],
    ]
    story.append(Table(info, colWidths=[30 * mm, 152 * mm],
                       style=[("FONTSIZE", (0, 0), (-1, -1), 8.5), ("FONTNAME", (0, 0), (0, -1), "Helvetica-Bold"),
                              ("BOTTOMPADDING", (0, 0), (-1, -1), 1), ("TOPPADDING", (0, 0), (-1, -1), 1)]))
    story.append(Spacer(1, 8))
    header = ["Item Code", "Description", "Qty", "Unit", "Unit Price", "Disc %", "GST %", "Line Total"]
    body = [header]
    for r in rows:
        body.append([r["code"], Paragraph(r["desc"], small), qty_text(r["qty"]), r["unit"], fmt(r["price"]),
                     f"{r['disc']:g}" if r["disc"] else "0", f"{GST:g}", fmt(r["total"])])
    body += [["", "Sub Total", "", "", "", "", "", fmt(totals["gross"])],
             ["", "Discount", "", "", "", "", "", fmt(totals["discount"])],
             ["", "GST @ 18%", "", "", "", "", "", fmt(totals["gst"])],
             ["", "Grand Total", "", "", "", "", "", fmt(totals["total"])]]
    n = len(rows)
    story.append(Table(body, colWidths=[18 * mm, 64 * mm, 16 * mm, 13 * mm, 20 * mm, 13 * mm, 12 * mm, 26 * mm],
                       repeatRows=1, style=[
                           ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#" + BLACK)),
                           ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                           ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
                           ("FONTSIZE", (0, 0), (-1, -1), 8.5),
                           ("ALIGN", (2, 1), (-1, -1), "RIGHT"),
                           ("GRID", (0, 0), (-1, n), .4, colors.HexColor("#BBBBBB")),
                           ("LINEABOVE", (1, n + 1), (-1, n + 1), .8, colors.black),
                           ("FONTNAME", (1, n + 4), (-1, n + 4), "Helvetica-Bold"),
                           ("TEXTCOLOR", (1, n + 4), (-1, n + 4), colors.HexColor("#" + RED)),
                           ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                       ]))
    story.append(Spacer(1, 10))
    for label, value in [("Delivery", vendor["delivery"]), ("Payment", vendor["payment"]),
                         ("Warranty", vendor["warranty"]), ("Freight", vendor["freight"])]:
        story.append(Paragraph(f"<b>{label}:</b> {value}", small))
    story.append(Spacer(1, 10))
    story.append(Paragraph("Prices are per unit shown, excluding GST. Demo document: fictional vendor and prices.", small))
    story.append(Spacer(1, 18))
    story.append(Paragraph(f"For {vendor['name']}<br/>Authorised signatory: {vendor['contact']}", small))
    doc.build(story)
    return path


# ---------------------------------------------------------------------- Excel material list
def write_xlsx(vendor, rows, totals):
    path = DATA / f"{vendor['file']}_Material_List.xlsx"
    wb = Workbook()
    ws = wb.active
    ws.title = "Quotation"
    bold = Font(bold=True)
    head_fill = PatternFill("solid", fgColor=BLACK)
    ws.append([vendor["name"]])
    ws["A1"].font = Font(bold=True, size=14)
    ws.append(["Quotation No:", vendor["quote_no"]])
    ws.append(["Quotation Date:", QUOTE_DATE.strftime("%d %b %Y")])
    ws.append(["Valid Until:", VALID_UNTIL.strftime("%d %b %Y")])
    ws.append(["Currency:", "INR"])
    ws.append(["RFQ Ref:", RFQ_REF])
    ws.append([])
    header = ["Item Code", "Description", "Qty", "Unit", "Unit Price", "Disc %", "GST %", "Line Total"]
    ws.append(header)
    for c in ws[ws.max_row]:
        c.font = Font(bold=True, color="FFFFFF")
        c.fill = head_fill
    for r in rows:
        ws.append([r["code"], r["desc"], r["qty"], r["unit"], float(r["price"]), float(r["disc"]), float(GST),
                   float(r["total"])])
    ws.append([])
    for label, key in [("Sub Total", "gross"), ("Discount", "discount"), ("GST @ 18%", "gst"), ("Grand Total", "total")]:
        ws.append(["", label, "", "", "", "", "", float(totals[key])])
        ws.cell(ws.max_row, 2).font = bold
    ws.cell(ws.max_row, 8).font = Font(bold=True, color=RED)
    ws.append([])
    for label, value in [("Delivery:", vendor["delivery"]), ("Payment:", vendor["payment"]),
                         ("Warranty:", vendor["warranty"]), ("Freight:", vendor["freight"])]:
        ws.append([label, value])
        ws.cell(ws.max_row, 1).font = bold
    for col, width in zip("ABCDEFGH", (14, 44, 10, 8, 13, 9, 8, 16)):
        ws.column_dimensions[col].width = width
    for row in ws.iter_rows(min_row=9, max_row=ws.max_row):
        for cell in row[4:8]:
            if isinstance(cell.value, float):
                cell.number_format = "#,##0.00"

    # Second sheet: what each material is, as the vendor supplies it (no prices)
    spec = wb.create_sheet("Material specifications")
    spec.append(["Item Code", "Material", "Specification offered", "Origin", "Packing"])
    for c in spec[1]:
        c.font = Font(bold=True, color="FFFFFF")
        c.fill = head_fill
    packing = {"Tonne": "Bundles / pallets", "Kg": "25 kg bags", "Nos": "Boxes"}
    for code, desc, spec_text, _qty, unit in ITEMS:
        spec.append([code, desc, spec_text, "India", packing[unit]])
    for col, width in zip("ABCDE", (12, 40, 52, 10, 18)):
        spec.column_dimensions[col].width = width
    for row in spec.iter_rows():
        for cell in row:
            cell.alignment = Alignment(wrap_text=True, vertical="top")
    wb.save(path)
    return path


# ---------------------------------------------------------------------- buyer documents
def write_rfq_docx():
    path = DATA / f"RFQ_{RFQ_REF}_Production_Materials.docx"
    doc = Document()
    style = doc.styles["Normal"]
    style.font.name = "Calibri"
    style.font.size = Pt(10)
    doc.add_heading("Request for Quotation", level=0)
    doc.add_paragraph(f"{BUYER}\n{BUYER_ADDRESS}")
    doc.add_paragraph(f"RFQ reference: {RFQ_REF}\nIssued: 01 Nov 2026\nQuotations due by: 15 Nov 2026\n"
                      "Currency: INR (prices excluding GST; show GST separately)")
    doc.add_heading("What we need", level=1)
    doc.add_paragraph("Production materials for the Q1 2027 build plan. Enter these lines as the items of the RFQ in "
                      "SUTRA (Title: Production materials for Q1 2027; Required by: 15 Dec 2026).")
    table = doc.add_table(rows=1, cols=5)
    table.style = "Light Grid Accent 1"
    for cell, text in zip(table.rows[0].cells, ["Item code", "Material", "Specification", "Quantity", "Unit"]):
        cell.text = text
    for code, desc, spec, qty, unit in ITEMS:
        cells = table.add_row().cells
        for cell, text in zip(cells, [code, desc, spec, f"{qty:,}", unit]):
            cell.text = text
    doc.add_heading("How to quote", level=1)
    for line in ["Quote every item with its item code, quantity, unit price, GST % and line total.",
                 "State delivery time, payment terms, warranty and freight.",
                 "Send the quotation through the SUTRA vendor portal as PDF and/or Excel.",
                 "Quotations must be valid for at least 30 days."]:
        doc.add_paragraph(line, style="List Bullet")
    doc.add_paragraph("Demo document: fictional company, used to test Project SUTRA.")
    doc.save(path)
    return path


def write_catalogue_xlsx():
    path = DATA / "Materials_Catalogue.xlsx"
    wb = Workbook()
    ws = wb.active
    ws.title = "Material types"
    ws.append([f"{BUYER} - materials we procure"])
    ws["A1"].font = Font(bold=True, size=13)
    ws.append([])
    ws.append(["Category", "Code", "Material", "Standard / grade", "Unit", "Used for"])
    for c in ws[3]:
        c.font = Font(bold=True, color="FFFFFF")
        c.fill = PatternFill("solid", fgColor=RED)
    for row in CATALOGUE:
        ws.append(list(row))
    for col, width in zip("ABCDEF", (18, 9, 42, 18, 8, 26)):
        ws.column_dimensions[col].width = width
    wb.save(path)
    return path


def main():
    DATA.mkdir(exist_ok=True)
    write_rfq_docx()
    write_catalogue_xlsx()
    summary = []
    for v in VENDORS:
        rows, totals = lines_for(v)
        write_pdf(v, rows, totals)
        write_xlsx(v, rows, totals)
        summary.append((v, totals))
        print(f"{v['name']:<40} sub {fmt(totals['gross']):>14}  disc {fmt(totals['discount']):>10}  "
              f"GST {fmt(totals['gst']):>13}  grand {fmt(totals['total']):>14}")

    ranked = sorted(summary, key=lambda s: s[1]["total"])
    readme = [
        "PROJECT SUTRA - AUTOMOTIVE TEST PACK",
        "All companies, people, references and prices here are fictional demo data, not real offers.",
        "",
        f"Buyer: {BUYER}. RFQ reference {RFQ_REF}: production materials for Q1 2027.",
        "",
        "FILES",
        f"  RFQ_{RFQ_REF}_Production_Materials.docx  The buyer's request: the 5 items to enter in SUTRA.",
        "  Materials_Catalogue.xlsx                     Every material type the buyer procures (15 types, 7 categories).",
        "  Vendor_N_<name>_Quotation.pdf                Each vendor's quotation (one page).",
        "  Vendor_N_<name>_Material_List.xlsx           The same quotation as a priced material list, plus a",
        "                                               'Material specifications' sheet.",
        "  A vendor sends its PDF and Excel together: SUTRA reads them as one quotation.",
        "",
        "ITEMS REQUESTED",
    ]
    for code, desc, spec, qty, unit in ITEMS:
        readme.append(f"  {code}  {desc} - {qty:,} {unit} ({spec})")
    readme += ["", "EXPECTED TOTALS (INR, GST 18%)"]
    for v, t in summary:
        readme.append(f"  {v['name']}: Sub Total {fmt(t['gross'])}; Discount {fmt(t['discount'])}; "
                      f"GST {fmt(t['gst'])}; Grand Total {fmt(t['total'])}")
    readme += ["", "EXPECTED RANKING (lowest grand total first)"]
    for i, (v, t) in enumerate(ranked, 1):
        readme.append(f"  {i}. {v['name']} - {fmt(t['total'])}")
    readme += [
        "",
        "THINGS SUTRA SHOULD FLAG",
        "  Vertex Auto Materials: 'Bearing warranty to be confirmed' (warranty left open) and freight extra.",
        "  Vertex also asks for a 50% advance, which lowers its advisory payment grade.",
        "",
        "HOW TO USE",
        "  1. Admin creates the buyer company (Entities), the three vendors (Vendors) and logins: a buyer, a manager,",
        "     and one vendor login per vendor (Users & roles, role Vendor, linked to the vendor).",
        "  2. The buyer creates the RFQ with the 5 items above and opens it to vendors.",
        "  3. Each vendor signs in, opens the RFQ and sends its PDF + Excel files.",
        "  4. The buyer reviews each quotation, marks it ready and generates the comparison.",
        "  5. The manager awards the RFQ (a reason is needed when not choosing the lowest complete quotation).",
    ]
    (DATA / "README.txt").write_text("\n".join(readme) + "\n", encoding="utf-8")
    print("written to", DATA)


if __name__ == "__main__":
    main()
