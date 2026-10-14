package br.com.xopy;

import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUsersController {
    private final JdbcTemplate db;
    private final Identity identity;
    public AdminUsersController(JdbcTemplate db, Identity identity) {
        this.db = db; this.identity = identity;
    }
    public record Selection(@NotEmpty @Size(max=200) List<@NotNull UUID> ids) {}

    @PostMapping("/delete")
    @Transactional
    public Map<String,Object> delete(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Selection selection) {
        identity.requireAdmin(jwt);
        UUID actor = identity.id(jwt);
        List<UUID> ids = selection.ids().stream().distinct().sorted().toList();
        if (ids.isEmpty() || ids.size() > 200)
            throw new ResponseStatusException(BAD_REQUEST, "Selecione de 1 a 200 usuários");
        if (ids.contains(actor))
            throw new ResponseStatusException(CONFLICT, "Você não pode excluir sua própria conta");
        // Lock and validate the entire selection before changing anything.
        for (UUID id : ids) {
            List<Map<String,Object>> rows = db.queryForList(
                    "SELECT id FROM app_users WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
            if (rows.isEmpty())
                throw new ResponseStatusException(NOT_FOUND, "Usuário não encontrado. Atualize a lista");
        }
        for (UUID id : ids) {
            // Keep the account key for order/payment references; revoke all access.
            db.update("UPDATE app_users SET deleted_at=now(),token_version=token_version+1,email=?,password_hash=? WHERE id=?",
                    "deleted-" + id + "@invalid.local", UUID.randomUUID().toString(), id);
            db.update("UPDATE products SET status='inactive' WHERE user_id=?", id);
            db.update("UPDATE service_providers SET status='rejected' WHERE user_id=?", id);
            db.update("UPDATE dynamic_products SET is_active=false WHERE source_product_id IN (SELECT id FROM products WHERE user_id=?)", id);
            db.update("UPDATE seller_mp_accounts SET receiving_enabled=false WHERE seller_id=?", id);
            db.update("DELETE FROM seller_mp_oauth_states WHERE seller_id=?", id);
            db.update("DELETE FROM profile_details WHERE user_id=?", id);
            db.update("DELETE FROM user_profiles WHERE id=?", id);
        }
        return Map.of("deleted", ids.size());
    }
}
