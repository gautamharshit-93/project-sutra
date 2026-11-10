package com.sutra.common.error;

import com.sutra.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Converts every exception into an {@link ApiError}. Messages never include file content or credentials. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SutraException.class)
    public ResponseEntity<ApiError> handleSutra(SutraException ex, HttpServletRequest req) {
        if (ex.getStatus().is5xxServerError()) {
            log.warn("{} on {}: {}", ex.getCode(), req.getRequestURI(), ex.getMessage());
        }
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        List<ApiError.FieldError> global = ex.getBindingResult().getGlobalErrors().stream()
                .map(g -> new ApiError.FieldError(g.getObjectName(), g.getDefaultMessage()))
                .toList();
        List<ApiError.FieldError> all = new java.util.ArrayList<>(fields);
        all.addAll(global);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more fields are invalid", req, all);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        List<ApiError.FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more fields are invalid", req, fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex, HttpServletRequest req) {
        String message = "Malformed request body";
        if (ex instanceof MissingServletRequestPartException m) {
            message = "Missing required part '" + m.getRequestPartName() + "'";
        } else if (ex instanceof MissingServletRequestParameterException m) {
            message = "Missing required parameter '" + m.getParameterName() + "'";
        } else if (ex instanceof MethodArgumentTypeMismatchException m) {
            message = "Invalid value for '" + m.getName() + "'";
        }
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, req, null);
    }

    /** No file attached / not a file upload, or a body in a format the endpoint does not take. */
    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class,
            org.springframework.web.HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ApiError> handleNotMultipart(Exception ex, HttpServletRequest req) {
        if (ex instanceof MaxUploadSizeExceededException tooLarge) {
            return handleTooLarge(tooLarge, req);
        }
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex instanceof org.springframework.web.HttpMediaTypeNotSupportedException
                ? "This endpoint expects a different kind of request (for example a file upload)"
                : "Attach at least one file", req, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                "The uploaded file exceeds the maximum allowed size", req, null);
    }

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    public ResponseEntity<ApiError> handleAuth(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "Invalid username or password", req, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action", req, null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The record was changed by someone else. Reload and try again.", req, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Data integrity violation on {}: {}", req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                "The change conflicts with existing data or violates a database constraint", req, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", ex.getMessage(), req, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "No endpoint at this path", req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error on {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred", req, null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                           HttpServletRequest req, List<ApiError.FieldError> fieldErrors) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), code, message,
                req.getRequestURI(), fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors,
                CorrelationIdFilter.current());
        return ResponseEntity.status(status).body(body);
    }
}
