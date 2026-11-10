package com.sutra.reporting.service.impl;

import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationItemResponse;
import com.sutra.api.quotation.QuotationResponses.QuotationResponse;
import com.sutra.api.comparison.ComparisonResult.QuotationColumn;
import com.sutra.api.decision.DecisionResponses.ComparisonReportResponse;
import com.sutra.api.decision.DecisionResponses.ApprovalResponse;
import com.sutra.reporting.client.PlatformClient;
import com.sutra.reporting.dto.response.AwardSummary;
import com.sutra.reporting.service.AwardSummaryService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.api.enums.ApprovalDecision;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

/**
 * A one-page (or longer) PDF of an RFQ's decision: what was bought, from whom, at what price, who decided, when and
 * why, and how the other compared vendors stood. Amounts are the backend's own calculations.
 */
@Service
public class AwardSummaryServiceImpl implements AwardSummaryService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH);
    private static final float MARGIN = 48;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;

    private final PlatformClient platform;
    private final Clock clock;

    public AwardSummaryServiceImpl(PlatformClient platform, Clock clock) {
        this.platform = platform;
        this.clock = clock;
    }

    @Override
    public AwardSummary summary(Long purchaseRequestId) {
        PurchaseRequestResponse pr = platform.rfq(purchaseRequestId);
        ApprovalResponse decision = platform.latestDecision(purchaseRequestId)
                .orElseThrow(() -> new BusinessRuleException("NO_DECISION", pr.requestNumber() + " has no award decision yet"));
        QuotationResponse selected = decision.selectedQuotationId() == null ? null
                : platform.quotation(decision.selectedQuotationId());
        ComparisonReportResponse report = platform.comparison(decision.comparisonReportId());
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Writer w = new Writer(doc);
            write(w, pr, decision, selected, report);
            w.close();
            doc.save(out);
            return new AwardSummary(pr.requestNumber() + "-award-summary.pdf", out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the award summary", e);
        }
    }

    private void write(Writer w, PurchaseRequestResponse pr, ApprovalResponse a, QuotationResponse q,
                       ComparisonReportResponse report) throws IOException {
        ZoneId zone = clock.getZone();
        boolean awarded = a.decision() == ApprovalDecision.APPROVED;
        w.title("SUTRA  |  Award summary");
        w.small("Generated " + DATE_TIME.format(Instant.now(clock).atZone(zone)) + " (" + zone.getId() + ")");
        w.gap(10);

        w.heading(pr.requestNumber() + "  " + pr.title());
        w.pair("Entity", pr.entityName() + (pr.subEntityName() == null ? "" : " / " + pr.subEntityName()));
        if (pr.customerName() != null) {
            w.pair("Customer", pr.customerName());
        }
        w.pair("Requested by", pr.requestedBy());
        w.pair("Currency", pr.currency());
        w.pair("Required by", date(pr.requiredBy()));
        if (pr.quotationsDue() != null) {
            w.pair("Quotations due", date(pr.quotationsDue()));
        }
        w.gap(8);

        w.heading(awarded ? "Decision: AWARDED" : "Decision: REJECTED");
        if (awarded && q != null) {
            w.pair("Vendor", q.vendorName());
            w.pair("Quotation", nz(q.quotationNumber()) + (q.quotationDate() == null ? "" : " dated " + date(q.quotationDate())));
            w.pair("Valid until", date(q.validUntil()));
        }
        w.pair("Decided by", a.decidedBy());
        w.pair("Decided on", DATE_TIME.format(a.decidedAt().atZone(zone)));
        w.pair("Reason", nz(a.reason()));
        w.gap(8);

        if (awarded && q != null) {
            String cur = q.currency() == null ? pr.currency() : q.currency();
            w.heading("Items awarded");
            float[] cols = {22, 60, 175, 52, 40, 72, 38, 80};
            w.row(cols, true, "#", "Code", "Description", "Qty", "Unit", "Unit price", "Tax %", "Line total");
            List<QuotationItemResponse> items = new ArrayList<>(q.items());
            items.sort(Comparator.comparing(QuotationItemResponse::lineNumber));
            for (QuotationItemResponse i : items) {
                w.row(cols, false, String.valueOf(i.lineNumber()), nz(i.itemCode()), i.description(),
                        num(i.quantity()), nz(i.unitOfMeasure()), money(i.unitPrice(), cur),
                        num(i.taxPercent()), money(i.lineTotal(), cur));
            }
            w.gap(6);
            w.pair("Subtotal", money(q.subtotal(), cur));
            w.pair("Discount", money(q.discountTotal(), cur));
            w.pair("Tax", money(q.taxTotal(), cur));
            w.pairBold("Grand total", money(q.grandTotal(), cur));
            w.gap(4);
            if (q.deliveryTerms() != null) {
                w.pair("Delivery", q.deliveryTerms());
            }
            if (q.paymentTerms() != null) {
                w.pair("Payment", q.paymentTerms());
            }
            if (q.warrantyTerms() != null) {
                w.pair("Warranty", q.warrantyTerms());
            }
            w.gap(8);
        }

        if (report != null && report.result() != null) {
            w.heading("Comparison version " + report.reportVersion());
            float[] cols = {40, 230, 120, 80, 69};
            w.row(cols, true, "Rank", "Vendor", "Grand total", "Items quoted", "Missing");
            List<QuotationColumn> rows = new ArrayList<>(report.result().quotations());
            rows.sort(Comparator.comparing((QuotationColumn c) -> c.priceRank() == null ? Integer.MAX_VALUE : c.priceRank()));
            for (QuotationColumn c : rows) {
                String cur = c.currency() == null ? pr.currency() : c.currency();
                boolean chosen = q != null && c.quotationId().equals(q.id());
                w.row(cols, chosen, c.priceRank() == null ? "-" : String.valueOf(c.priceRank()),
                        c.vendorName() + (chosen ? "  (awarded)" : ""), money(c.grandTotal(), cur),
                        String.valueOf(c.itemsQuoted()), String.valueOf(c.itemsMissing()));
            }
            w.gap(8);
        }
        w.small("Totals are calculated by SUTRA from the quoted lines, not taken from the documents or the AI. "
                + "The decision was recorded by an authorised user; SUTRA never selects or approves a vendor.");
    }

    private static String date(LocalDate d) {
        return d == null ? "-" : DATE.format(d);
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String num(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    /** "INR 17,78,260.00" (Indian grouping), via the assistant's formatter; the PDF font has no rupee sign. */
    static String money(BigDecimal v, String currency) {
        String s = AssistantServiceImpl.money(v, currency);
        return s == null ? "-" : s.replace("₹", "INR ");
    }

    /** Writes lines top to bottom, starting a new page when one is full. */
    private static final class Writer {
        private final PDDocument doc;
        private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        private PDPageContentStream out;
        private float y;

        Writer(PDDocument doc) throws IOException {
            this.doc = doc;
            newPage();
        }

        private void newPage() throws IOException {
            if (out != null) {
                out.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            out = new PDPageContentStream(doc, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }

        private void need(float height) throws IOException {
            if (y - height < MARGIN) {
                newPage();
            }
        }

        void close() throws IOException {
            out.close();
        }

        void gap(float h) {
            y -= h;
        }

        void title(String text) throws IOException {
            need(24);
            out.setNonStrokingColor(1f, 0f, 0f);
            text(bold, 16, MARGIN, text);
            out.setNonStrokingColor(0f, 0f, 0f);
            y -= 20;
        }

        void heading(String text) throws IOException {
            need(20);
            for (String line : wrap(bold, 11, text, WIDTH)) {
                text(bold, 11, MARGIN, line);
                y -= 15;
            }
            out.setStrokingColor(1f, 0f, 0f);
            out.moveTo(MARGIN, y + 11);
            out.lineTo(MARGIN + WIDTH, y + 11);
            out.stroke();
            y -= 3;
        }

        void small(String text) throws IOException {
            for (String line : wrap(regular, 8, text, WIDTH)) {
                need(11);
                text(regular, 8, MARGIN, line);
                y -= 11;
            }
        }

        void pair(String label, String value) throws IOException {
            pair(label, value, regular);
        }

        void pairBold(String label, String value) throws IOException {
            pair(label, value, bold);
        }

        private void pair(String label, String value, PDType1Font font) throws IOException {
            List<String> lines = wrap(font, 9.5f, value, WIDTH - 110);
            need(13f * lines.size());
            text(bold, 9.5f, MARGIN, label);
            for (String line : lines) {
                text(font, 9.5f, MARGIN + 110, line);
                y -= 13;
            }
        }

        /** One table row; text in each column wraps, and the row is as tall as its tallest cell. */
        void row(float[] widths, boolean strong, String... cells) throws IOException {
            PDType1Font font = strong ? bold : regular;
            List<List<String>> wrapped = new ArrayList<>();
            int lines = 1;
            for (int c = 0; c < cells.length; c++) {
                List<String> w = wrap(font, 8.5f, cells[c], widths[c] - 4);
                wrapped.add(w);
                lines = Math.max(lines, w.size());
            }
            need(11f * lines + 4);
            float x = MARGIN;
            for (int c = 0; c < cells.length; c++) {
                float ly = y;
                for (String line : wrapped.get(c)) {
                    textAt(font, 8.5f, x, ly, line);
                    ly -= 11;
                }
                x += widths[c];
            }
            y -= 11f * lines + 2;
            out.setStrokingColor(0.85f, 0.85f, 0.85f);
            out.moveTo(MARGIN, y + 8);
            out.lineTo(MARGIN + WIDTH, y + 8);
            out.stroke();
            y -= 2;
        }

        private void text(PDType1Font font, float size, float x, String s) throws IOException {
            textAt(font, size, x, y, s);
        }

        private void textAt(PDType1Font font, float size, float x, float atY, String s) throws IOException {
            out.beginText();
            out.setFont(font, size);
            out.newLineAtOffset(x, atY);
            out.showText(safe(font, s));
            out.endText();
        }

        private static List<String> wrap(PDType1Font font, float size, String text, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            String clean = safe(font, text == null ? "" : text);
            for (String paragraph : clean.split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split(" ")) {
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (font.getStringWidth(candidate) / 1000 * size <= width || line.isEmpty()) {
                        line.setLength(0);
                        line.append(candidate);
                    } else {
                        lines.add(line.toString());
                        line.setLength(0);
                        line.append(word);
                    }
                }
                lines.add(line.toString());
            }
            return lines;
        }

        /** The standard PDF fonts only cover Latin text: anything else (e.g. Devanagari) becomes '?'. */
        private static String safe(PDType1Font font, String s) {
            StringBuilder b = new StringBuilder();
            s.codePoints().forEach(cp -> {
                String ch = new String(Character.toChars(cp));
                try {
                    font.encode(ch);
                    b.append(ch);
                } catch (IOException | IllegalArgumentException e) {
                    b.append(cp == '\t' ? " " : "?");
                }
            });
            return b.toString();
        }
    }
}
