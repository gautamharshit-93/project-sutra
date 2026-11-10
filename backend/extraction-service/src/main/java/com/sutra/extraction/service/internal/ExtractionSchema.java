package com.sutra.extraction.service.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema for {@link ExtractedQuotation}, sent to Groq as the strict structured-output contract.
 * Optional values are nullable ({@code ["number","null"]}) and still required, so the model writes null
 * instead of guessing. A unit test keeps the property names in step with the record.
 */
public final class ExtractionSchema {

    public static final Map<String, Object> SCHEMA = buildSchema();

    private ExtractionSchema() {
    }

    private static Map<String, Object> buildSchema() {
        Map<String, Object> item = object(props(
                "itemCode", nullable("string", "Item code as printed (e.g. IT-001), usually the buyer's code"),
                "description", prop("string", "Item description including make, model and key specifications"),
                "quantity", nullable("number", "Quantity as printed"),
                "unitOfMeasure", nullable("string", "Unit of measure, e.g. Nos, kg, m, set, license"),
                "unitPrice", nullable("number", "Price per unit before discount and tax"),
                "discountPercent", nullable("number", "Discount percentage 0-100, if stated"),
                "discountAmount", nullable("number", "Discount amount for the line, if stated"),
                "taxPercent", nullable("number", "Tax percentage 0-100 for the line, if stated"),
                "taxAmount", nullable("number", "Tax amount for the line, if stated"),
                "lineTotal", nullable("number", "Line total exactly as printed"),
                "confidence", prop("number", "0 to 1: how sure you are this line was read correctly"),
                "notes", nullable("string", "Anything unusual about this line")));

        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "array");
        items.put("description", "Every priced line item, in document order");
        items.put("items", item);

        Map<String, Object> warnings = new LinkedHashMap<>();
        warnings.put("type", "array");
        warnings.put("description", "Ambiguous, illegible or conflicting values a reviewer should check");
        warnings.put("items", Map.of("type", "string"));

        return object(props(
                "vendorName", nullable("string", "Vendor/supplier company name as printed"),
                "quotationNumber", nullable("string", "Quotation or reference number"),
                "quotationDate", nullable("string", "Quotation date as YYYY-MM-DD"),
                "validUntil", nullable("string", "Last date the offer is valid, YYYY-MM-DD"),
                "currency", nullable("string", "ISO 4217 currency code, e.g. INR, USD"),
                "items", items,
                "subtotal", nullable("number", "Subtotal as printed (usually before tax)"),
                "discountTotal", nullable("number", "Total discount as printed"),
                "taxTotal", nullable("number", "Total tax as printed (GST/VAT)"),
                "grandTotal", nullable("number", "Grand total / amount payable as printed"),
                "deliveryTerms", nullable("string", "Delivery terms, lead time, shipping"),
                "paymentTerms", nullable("string", "Payment terms"),
                "warrantyTerms", nullable("string", "Warranty or guarantee terms"),
                "notes", nullable("string", "Other commercial notes such as freight or installation charges"),
                "overallConfidence", prop("number", "0 to 1: how sure you are the whole extraction is correct"),
                "warnings", warnings));
    }

    static Map<String, Object> object(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    static Map<String, Object> props(Object... nameSchemaPairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < nameSchemaPairs.length; i += 2) {
            properties.put((String) nameSchemaPairs[i], nameSchemaPairs[i + 1]);
        }
        return properties;
    }

    static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    static Map<String, Object> nullable(String type, String description) {
        return Map.of("type", List.of(type, "null"), "description", description);
    }
}
