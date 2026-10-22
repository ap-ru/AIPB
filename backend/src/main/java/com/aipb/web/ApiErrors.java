package com.aipb.web;

import com.aipb.assistant.AssistantOrchestrator;
import com.aipb.source.SourceException;
import com.aipb.widget.WidgetLibrary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(SourceException.class)
    public ResponseEntity<Map<String, String>> sourceUnavailable(SourceException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** A spec refused by the validator: 422 with the full report, so the author (or the assistant) can fix it. */
    @ExceptionHandler(WidgetLibrary.SpecInvalidException.class)
    public ResponseEntity<Map<String, Object>> specInvalid(WidgetLibrary.SpecInvalidException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", String.valueOf(e.getMessage()), "report", e.report()));
    }

    @ExceptionHandler(WidgetLibrary.DuplicateException.class)
    public ResponseEntity<Map<String, String>> duplicate(WidgetLibrary.DuplicateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler({AssistantOrchestrator.BusyException.class, AssistantOrchestrator.ConfirmationRequired.class})
    public ResponseEntity<Map<String, String>> conflict(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
