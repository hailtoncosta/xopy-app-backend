package br.com.xopy;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
public class SiteController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Identity identity;

    public SiteController(JdbcTemplate db, ObjectMapper json, Identity identity) {
        this.db = db;
        this.json = json;
        this.identity = identity;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/api/site/content")
    public Map<String, Object> content() {
        Map<String, Object> result = new LinkedHashMap<>();
        db.query("select section,data::text from site_content", rs -> {
            try {
                result.put(rs.getString(1), json.readValue(rs.getString(2), Object.class));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return result;
    }

    @PutMapping("/api/site/content/{section}")
    public void save(@PathVariable String section, @RequestBody Object body, @AuthenticationPrincipal Jwt jwt)
            throws Exception {
        identity.requireAdmin(jwt);
        if (!Set.of("hero", "about", "features", "benefits", "steps", "testimonials", "contact", "blog", "faq",
                "settings").contains(section))
            throw new ResponseStatusException(BAD_REQUEST, "Seção inválida");
        db.update(
                "insert into site_content(section,data) values(?,?::jsonb) on conflict(section) do update set data=excluded.data,updated_at=now()",
                section, json.writeValueAsString(body));
    }

    @PostMapping("/api/rpc/is_admin")
    public Map<String, Object> admin(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("data", identity.admin(jwt));
    }

    @PostMapping("/api/functions/{name}")
    public void pending(@PathVariable String name) {
        throw new ResponseStatusException(NOT_IMPLEMENTED, "Função ainda não convertida: " + name);
    }
}
