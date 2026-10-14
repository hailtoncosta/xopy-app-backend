package br.com.xopy;

import java.time.Instant;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final Identity identity;
    private final String issuer;
    private final String dummy;

    public AuthController(JdbcTemplate db, PasswordEncoder passwords, JwtEncoder encoder, Identity identity,
            @Value("${app.issuer}") String issuer) {
        this.db = db;
        this.passwords = passwords;
        this.encoder = encoder;
        this.identity = identity;
        this.issuer = issuer;
        this.dummy = passwords.encode(UUID.randomUUID().toString());
    }

    public record Login(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record Register(@Email @NotBlank String email, @Size(min = 8, max = 72) @NotBlank String password,
            @NotBlank @Size(max = 150) String name, String phone, String type) {
    }

    public record Password(@NotBlank String currentPassword, @Size(min = 8, max = 72) @NotBlank String newPassword) {
    }

    @PostMapping("/register")
    @Transactional
    public Map<String, Object> register(@Valid @RequestBody Register r) {
        String type = r.type() == null ? "customer" : r.type();
        if (!Set.of("customer", "store_owner", "service_provider", "rental_company").contains(type))
            throw new ResponseStatusException(BAD_REQUEST, "Tipo de conta inválido");
        UUID id = UUID.randomUUID();
        String email = r.email().trim().toLowerCase(Locale.ROOT);
        if (Boolean.TRUE.equals(
                db.queryForObject("select exists(select 1 from app_users where email=?)", Boolean.class, email)))
            throw new ResponseStatusException(CONFLICT, "E-mail já cadastrado");
        db.update("insert into app_users(id,email,password_hash) values(?,?,?)", id, email,
                passwords.encode(r.password()));
        db.update("insert into user_profiles(id,email,full_name,phone,user_type,is_verified) values(?,?,?,?,?,false)",
                id, email, r.name(), r.phone(), type);
        return session(db.queryForMap("select * from app_users where id=?", id));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody Login r) {
        List<Map<String, Object>> rows = db.queryForList("select * from app_users where email=? AND deleted_at IS NULL",
                r.email().trim().toLowerCase(Locale.ROOT));
        String hash = rows.isEmpty() ? dummy : (String) rows.get(0).get("password_hash");
        boolean valid = passwords.matches(r.password(), hash);
        if (rows.isEmpty() || !valid)
            throw new ResponseStatusException(UNAUTHORIZED, "Usuário ou senha inválidos");
        return session(rows.get(0));
    }

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        return user(identity.require(jwt));
    }

    @PostMapping("/logout")
    public void logout(@AuthenticationPrincipal Jwt jwt) {
        db.update("update app_users set token_version=token_version+1 where id=?", identity.id(jwt));
    }

    @PutMapping("/password")
    @Transactional
    public void password(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Password p) {
        UUID id = identity.id(jwt);
        String hash = db.queryForObject("select password_hash from app_users where id=?", String.class, id);
        if (!passwords.matches(p.currentPassword(), hash))
            throw new ResponseStatusException(UNAUTHORIZED, "Senha atual inválida");
        db.update("update app_users set password_hash=?,token_version=token_version+1 where id=?",
                passwords.encode(p.newPassword()), id);
    }

    private Map<String, Object> session(Map<String, Object> account) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(account.get("id").toString()).issuedAt(now)
                .expiresAt(now.plusSeconds(3600)).claim("ver", account.get("token_version")).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return Map.of("access_token", token, "token_type", "bearer", "expires_in", 3600, "expires_at",
                now.plusSeconds(3600).getEpochSecond(), "user", user(account));
    }

    private Map<String, Object> user(Map<String, Object> a) {
        Map<String, Object> profile = db.queryForMap("select full_name,user_type,phone from user_profiles where id=?",
                a.get("id"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", a.get("id"));
        result.put("email", a.get("email"));
        result.put("role", a.get("role"));
        result.put("user_metadata", profile);
        return result;
    }
}
