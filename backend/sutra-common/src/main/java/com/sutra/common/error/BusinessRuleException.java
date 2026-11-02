package com.sutra.common.error;

import org.springframework.http.HttpStatus;

/** A request is well-formed but violates a business rule (e.g. approving a quotation that is still under review). */
public class BusinessRuleException extends SutraException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
