package br.com.xopy;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Component
public class Identity {
    private final JdbcTemplate db;

    public Identity(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> require(Jwt jwt) {
        if (jwt == null)
            throw new ResponseStatusException(UNAUTHORIZED, "Faça login");
        List<Map<String, Object>> rows = db.queryForList("select id,email,role,token_version from app_users where id=? AND deleted_at IS NULL",
                UUID.fromString(jwt.getSubject()));
        if (rows.isEmpty()
                || ((Number) rows.get(0).get("token_version")).intValue() != ((Number) jwt.getClaim("ver")).intValue())
            throw new ResponseStatusException(UNAUTHORIZED, "Sessão encerrada");
        return rows.get(0);
    }

    public boolean admin(Jwt jwt) {
        return jwt != null && "ADMIN".equals(require(jwt).get("role"));
    }

    public UUID id(Jwt jwt) {
        return (UUID) require(jwt).get("id");
    }

    public void requireAdmin(Jwt jwt) {
        if (!admin(jwt))
            throw new ResponseStatusException(FORBIDDEN, "Acesso administrativo necessário");
    }
}
