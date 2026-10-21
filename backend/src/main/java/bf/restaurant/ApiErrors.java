package bf.restaurant;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<?> denied(Exception e) { return ResponseEntity.status(403).body(Map.of("message","Autorisation insuffisante")); }
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ResponseEntity<?> changed(Exception e) { return ResponseEntity.status(409).body(Map.of("message","Donnée modifiée par un autre utilisateur. Actualisez.")); }
    @ExceptionHandler({java.util.NoSuchElementException.class,org.springframework.dao.EmptyResultDataAccessException.class})
    ResponseEntity<?> missing(Exception e) { return ResponseEntity.status(404).body(Map.of("message","Élément introuvable")); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> business(ResponseStatusException e) { return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", e.getReason() == null ? "Requête refusée" : e.getReason())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("message", "Données invalides. Vérifiez les champs.")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("message", "Conflit : référence utilisée ou donnée déjà enregistrée.")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unavailable(Exception e) { return ResponseEntity.status(500).body(Map.of("message", "Opération non confirmée. Vérifiez l’état avant de réessayer.")); }
    static void require(boolean condition, int status, String message) { if (!condition) throw new ResponseStatusException(HttpStatus.valueOf(status), message); }
}
