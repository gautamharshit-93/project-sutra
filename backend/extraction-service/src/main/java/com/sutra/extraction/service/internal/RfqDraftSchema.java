package com.sutra.extraction.service.internal;

import static com.sutra.extraction.service.internal.ExtractionSchema.nullable;
import static com.sutra.extraction.service.internal.ExtractionSchema.object;
import static com.sutra.extraction.service.internal.ExtractionSchema.prop;
import static com.sutra.extraction.service.internal.ExtractionSchema.props;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strict JSON Schema Groq fills when it reads a buyer's RFQ (a document or pasted text). It mirrors
 * {@link com.sutra.api.rfq.RfqDraftResponses.RfqDraft}; nothing is saved from it until a person confirms.
 */
public final class RfqDraftSchema {

    public static final Map<String, Object> SCHEMA = build();

    private RfqDraftSchema() {
    }

    private static Map<String, Object> build() {
        Map<String, Object> item = object(props(
                "itemCode", nullable("string", "The buyer's item / part code if printed, e.g. MT-101"),
                "description", prop("string", "What is requested, including grade, size, make or model"),
                "quantity", nullable("number", "Requested quantity as a plain number"),
                "unitOfMeasure", nullable("string", "Unit, e.g. Nos, Kg, Tonne, m, Set, Lot, License"),
                "specifications", nullable("string", "Further technical requirements for this line, if any")));

        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "array");
        items.put("description", "Every requested line, in document order");
        items.put("items", item);

        Map<String, Object> warnings = new LinkedHashMap<>();
        warnings.put("type", "array");
        warnings.put("description", "Anything unclear the buyer should check (missing quantity, ambiguous date...)");
        warnings.put("items", Map.of("type", "string"));

        return object(props(
                "title", prop("string", "Short title of what is being bought (max 120 characters)"),
                "description", nullable("string", "1-3 sentences: purpose, delivery place and stated terms"),
                "requiredBy", nullable("string", "Date the goods are needed, YYYY-MM-DD"),
                "quotationsDue", nullable("string", "Last date for vendors to send quotations, YYYY-MM-DD"),
                "currency", nullable("string", "ISO 4217 currency code if stated, e.g. INR"),
                "buyerName", nullable("string", "Company, plant or department issuing the RFQ"),
                "customerName", nullable("string", "End customer the goods are for, if stated"),
                "items", items,
                "warnings", warnings));
    }
}
