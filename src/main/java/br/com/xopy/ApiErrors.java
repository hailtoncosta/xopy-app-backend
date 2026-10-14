package br.com.xopy;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",
                e.getReason() == null ? "Falha na operação" : e.getReason(), "code", e.getStatusCode().value()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Dados inválidos", "fields", e.getBindingResult()
                .getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage()).toList()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> integrity(DataIntegrityViolationException e) {
        return ResponseEntity.status(409).body(Map.of("message", "Registro viola uma regra de integridade"));
    }
}
