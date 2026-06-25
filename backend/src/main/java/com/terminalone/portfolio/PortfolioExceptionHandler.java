package com.terminalone.portfolio;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates portfolio-domain failures into clean HTTP responses: unknown ids → 404,
 * bad/missing fields → 400 (rather than a 500 stack trace leaking out).
 */
@RestControllerAdvice(assignableTypes = PortfolioController.class)
class PortfolioExceptionHandler {

    @ExceptionHandler(PositionNotFoundException.class)
    ResponseEntity<Map<String, String>> handleNotFound(PositionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
