package com.sep490.vsa.common.exception;

import com.sep490.vsa.common.dto.ApiResponse;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.fail(ex.getMessage(), Map.of()));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ex.getMessage(), Map.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage() == null ? "Giá trị không hợp lệ" : error.getDefaultMessage(),
                        (first, second) -> first
                ));
        String first = errors.values().stream().findFirst().orElse("Dữ liệu không hợp lệ");
        return ResponseEntity.badRequest().body(ApiResponse.fail(first, errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("Dữ liệu gửi lên không đúng định dạng", Map.of()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ex.getMessage(), Map.of()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ex.getMessage(), Map.of()));
    }
}
