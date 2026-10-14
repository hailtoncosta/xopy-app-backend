package br.com.xopy;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/admin/rental-equipment")
public class RentalAdminController {
    private final JdbcTemplate db;
    private final Identity identity;
    private final ObjectMapper json;
    private static final String RENTAL = "(p.description LIKE '[LOCAÇÃO]%' OR p.product_type='rental')";

    public RentalAdminController(JdbcTemplate db, Identity identity, ObjectMapper json) {
        this.db = db; this.identity = identity; this.json = json;
    }

    @GetMapping
    public Map<String, Object> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="all") String status,
            @RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="0") int page) {
        identity.requireAdmin(jwt);
        if (!Set.of("all", "pending", "approved", "rejected").contains(status))
            throw new ResponseStatusException(BAD_REQUEST, "Status inválido");
        List<Object> args = new ArrayList<>();
        String where = " WHERE " + RENTAL;
        if (!status.equals("all")) { where += " AND coalesce(p.approval_status,'pending')=?"; args.add(status); }
        if (!search.isBlank()) {
            where += " AND (p.title ILIKE ? OR p.category ILIKE ? OR p.seller_info::text ILIKE ?)";
            for (int i=0; i<3; i++) args.add("%" + search.trim() + "%");
        }
        Long count = db.queryForObject("SELECT count(*) FROM products p" + where, Long.class, args.toArray());
        args.add(Math.max(0, page) * 20L);
        List<Object> rows = db.query("SELECT to_jsonb(p)::text FROM products p" + where
                + " ORDER BY p.created_at DESC,p.id LIMIT 20 OFFSET ?", (rs,n) -> parse(rs.getString(1)), args.toArray());
        return Map.of("data", rows, "count", count == null ? 0 : count);
    }

    public record Decision(String status, String comments) {}

    @PostMapping("/{id}/review")
    @Transactional
    public Map<String,Object> review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody Decision decision) {
        identity.requireAdmin(jwt);
        if (!Set.of("approved", "rejected").contains(Objects.toString(decision.status(), "")))
            throw new ResponseStatusException(BAD_REQUEST, "Decisão inválida");
        String comments = Objects.toString(decision.comments(), "").trim();
        if (decision.status().equals("rejected") && comments.isBlank())
            throw new ResponseStatusException(BAD_REQUEST, "Informe o motivo da rejeição");
        List<Map<String,Object>> rows = db.queryForList("SELECT p.id FROM products p WHERE p.id=? AND " + RENTAL + " FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Equipamento não encontrado");
        boolean approved = decision.status().equals("approved");
        db.update("UPDATE products SET approval_status=?, approval_date=now(), admin_comments=?, status=?, updated_at=now() WHERE id=?",
                decision.status(), comments.isBlank() ? null : comments, approved ? "active" : "inactive", id);
        // Keep publication and review in the same transaction, including retries.
        db.update("UPDATE dynamic_products SET is_active=false,updated_at=now() WHERE source_product_id=?", id);
        if (approved) {
            db.update("INSERT INTO dynamic_products (name,description,category,price,image_url,product_group,type,source_product_id,is_active) "
                + "SELECT p.title,p.description,p.category,'R$ ' || replace(p.price::text,'.',','),p.images[1],'rental','locação',p.id,true "
                + "FROM products p WHERE p.id=? AND NOT EXISTS (SELECT 1 FROM dynamic_products WHERE source_product_id=p.id)", id);
            db.update("UPDATE dynamic_products d SET name=p.title,description=p.description,category=p.category,"
                + "price='R$ ' || replace(p.price::text,'.',','),image_url=p.images[1],product_group='rental',type='locação',is_active=true,updated_at=now() "
                + "FROM products p WHERE p.id=? AND d.id=(SELECT id FROM dynamic_products WHERE source_product_id=p.id ORDER BY created_at,id LIMIT 1)", id);
        }
        return Map.of("status", decision.status());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String,Object> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        identity.requireAdmin(jwt);
        List<Map<String,Object>> rows = db.queryForList("SELECT p.approval_status FROM products p WHERE p.id=? AND " + RENTAL + " FOR UPDATE", id);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Equipamento não encontrado");
        if (!"rejected".equals(rows.get(0).get("approval_status")))
            throw new ResponseStatusException(CONFLICT, "Somente equipamentos rejeitados podem ser excluídos");
        db.update("DELETE FROM dynamic_products WHERE source_product_id=?", id);
        db.update("DELETE FROM products WHERE id=? AND approval_status='rejected'", id);
        return Map.of("status", "deleted");
    }

    private Object parse(String value) {
        try { return json.readValue(value, Object.class); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
