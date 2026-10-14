package br.com.xopy;

import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class SellerMercadoPago {
    private final JdbcTemplate db;
    private final MercadoPagoGateway mp;
    private final PaymentTokenCipher cipher;
    private final TransactionTemplate tx;

    public SellerMercadoPago(JdbcTemplate db, MercadoPagoGateway mp, PaymentTokenCipher cipher,
            PlatformTransactionManager manager) {
        this.db = db;
        this.mp = mp;
        this.cipher = cipher;
        this.tx = new TransactionTemplate(manager);
    }

    public record Credentials(String token, String collector, boolean sandbox) {
    }

    public boolean enabled() {
        return mp.oauthEnabled() && cipher.enabled();
    }

    static String random() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String digest(String input) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String challenge(String input) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String input) {
        return URLEncoder.encode(input, StandardCharsets.UTF_8);
    }

    public Map<String, Object> status(UUID seller) {
        var rows = db.queryForList(
                "select collector_id,receiving_enabled,sandbox,expires_at from seller_mp_accounts where seller_id=?",
                seller);
        var result = new HashMap<String, Object>();
        result.put("configured", enabled());
        result.put("connected", !rows.isEmpty());
        result.put("receivingEnabled", !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).get("receiving_enabled")));
        if (!rows.isEmpty()) {
            result.put("collectorId", rows.get(0).get("collector_id"));
            result.put("sandbox", rows.get(0).get("sandbox"));
        }
        return result;
    }

    public Map<String, String> authorize(UUID seller, int tokenVersion) {
        if (!enabled())
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "A conexão de vendedores ao Mercado Pago ainda não foi configurada pelo Xopy");
        String state = random(), verifier = random();
        db.update("delete from seller_mp_oauth_states where expires_at<now()");
        db.update(
                "insert into seller_mp_oauth_states(state_hash,seller_id,token_version,verifier_enc,expires_at) values(?,?,?,?,now()+interval '10 minutes')",
                digest(state), seller, tokenVersion, cipher.encrypt(verifier, seller + ":pkce"));
        String url = "https://auth.mercadopago.com.br/authorization?client_id=" + enc(mp.clientId())
                + "&response_type=code&platform_id=mp&redirect_uri=" + enc(mp.redirect()) + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge(verifier)) + "&code_challenge_method=S256";
        return Map.of("authorizationUrl", url, "state", state);
    }

    public void callback(String state, String cookie, String code) {
        if (state == null || cookie == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                cookie.getBytes(StandardCharsets.UTF_8)) || code == null || code.isBlank())
            throw new ResponseStatusException(UNAUTHORIZED, "Autorização inválida ou expirada");
        var row = tx.execute(s -> {
            var rows = db.queryForList(
                    "select o.seller_id,o.token_version,o.verifier_enc,u.token_version as current_version from seller_mp_oauth_states o join app_users u on u.id=o.seller_id where o.state_hash=? and o.used_at is null and o.expires_at>now() for update of o,u",
                    digest(state));
            if (rows.isEmpty())
                throw new ResponseStatusException(UNAUTHORIZED, "Autorização expirada ou já utilizada");
            var saved = rows.get(0);
            if (!saved.get("token_version").equals(saved.get("current_version")))
                throw new ResponseStatusException(UNAUTHORIZED, "Sessão encerrada");
            db.update("update seller_mp_oauth_states set used_at=now() where state_hash=?", digest(state));
            return saved;
        });
        UUID seller = (UUID) row.get("seller_id");
        var response = mp.oauth(Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", mp.redirect(),
                "state", state, "code_verifier", cipher.decrypt(row.get("verifier_enc").toString(), seller + ":pkce")));
        validate(response);
        String collector = response.path("user_id").asText();
        String access = response.path("access_token").asText();
        if (!collector.equals(mp.call(access, "GET", "/users/me", null).path("id").asText()))
            throw new ResponseStatusException(CONFLICT, "Titular da conta não corresponde à autorização");
        tx.executeWithoutResult(s -> {
            var user = db.queryForMap("select id,token_version from app_users where id=? for update", seller);
            if (!user.get("token_version").equals(row.get("token_version")))
                throw new ResponseStatusException(UNAUTHORIZED, "Sessão encerrada");
            var old = db.queryForList("select collector_id from seller_mp_accounts where seller_id=? for update",
                    seller);
            if (!old.isEmpty() && !collector.equals(old.get(0).get("collector_id"))
                    && !db.queryForList("select id from payment_sessions where seller_id=? limit 1", seller).isEmpty())
                throw new ResponseStatusException(CONFLICT,
                        "Sua conta possui histórico de pagamentos. Procure o Xopy antes de trocar a conta recebedora");
            var owner = db.queryForList(
                    "select seller_id from seller_mp_accounts where collector_id=? and seller_id<>?", collector,
                    seller);
            if (!owner.isEmpty())
                throw new ResponseStatusException(CONFLICT,
                        "Essa conta Mercado Pago já está vinculada a outro vendedor");
            db.update(
                    "insert into seller_mp_accounts(seller_id,collector_id,access_token_enc,refresh_token_enc,expires_at,sandbox,receiving_enabled) values(?,?,?,?,?,?,true) on conflict(seller_id) do update set collector_id=excluded.collector_id,access_token_enc=excluded.access_token_enc,refresh_token_enc=excluded.refresh_token_enc,expires_at=excluded.expires_at,sandbox=excluded.sandbox,receiving_enabled=true,updated_at=now()",
                    seller, collector, cipher.encrypt(access, seller + ":access"),
                    cipher.encrypt(response.path("refresh_token").asText(), seller + ":refresh"),
                    Timestamp.from(Instant.now().plusSeconds(response.path("expires_in").asLong())), mp.sandbox());
        });
    }

    private void validate(JsonNode response) {
        if (!response.path("user_id").asText().matches("[0-9]+") || response.path("access_token").asText().isBlank()
                || response.path("refresh_token").asText().isBlank() || response.path("expires_in").asLong() <= 0
                || !response.path("live_mode").isBoolean() || response.path("live_mode").asBoolean() == mp.sandbox()
                || !Arrays.asList(response.path("scope").asText().split(" ")).contains("offline_access"))
            throw new ResponseStatusException(CONFLICT, "Autorização Mercado Pago incompleta ou de outro ambiente");
    }

    public Credentials credentials(UUID seller, boolean newPayment) {
        if (!enabled())
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Recebimentos Mercado Pago não configurados");
        return tx.execute(s -> {
            db.queryForList("select id from app_users where id=? for update", seller);
            var rows = db.queryForList("select * from seller_mp_accounts where seller_id=? for update", seller);
            if (rows.isEmpty())
                throw new ResponseStatusException(CONFLICT,
                        "Este vendedor precisa conectar sua conta Mercado Pago para receber");
            var row = rows.get(0);
            if (newPayment && !Boolean.TRUE.equals(row.get("receiving_enabled")))
                throw new ResponseStatusException(CONFLICT, "O vendedor pausou novos recebimentos");
            if (Boolean.TRUE.equals(row.get("sandbox")) != mp.sandbox())
                throw new ResponseStatusException(CONFLICT, "O vendedor precisa conectar a conta no ambiente atual");
            var expiry = ((Timestamp) row.get("expires_at")).toInstant();
            String token = cipher.decrypt(row.get("access_token_enc").toString(), seller + ":access");
            if (expiry.isBefore(Instant.now().plusSeconds(300))) {
                var response = mp.oauth(Map.of("grant_type", "refresh_token", "refresh_token",
                        cipher.decrypt(row.get("refresh_token_enc").toString(), seller + ":refresh")));
                validate(response);
                if (!response.path("user_id").asText().equals(row.get("collector_id")))
                    throw new ResponseStatusException(CONFLICT, "Conta de recebimento mudou durante renovação");
                token = response.path("access_token").asText();
                db.update(
                        "update seller_mp_accounts set access_token_enc=?,refresh_token_enc=?,expires_at=?,updated_at=now() where seller_id=?",
                        cipher.encrypt(token, seller + ":access"),
                        cipher.encrypt(response.path("refresh_token").asText(), seller + ":refresh"),
                        Timestamp.from(Instant.now().plusSeconds(response.path("expires_in").asLong())), seller);
            }
            return new Credentials(token, row.get("collector_id").toString(), Boolean.TRUE.equals(row.get("sandbox")));
        });
    }

    public void pause(UUID seller) {
        tx.executeWithoutResult(s -> {
            db.queryForList("select id from app_users where id=? for update", seller);
            db.update("update seller_mp_accounts set receiving_enabled=false,updated_at=now() where seller_id=?",
                    seller);
        });
    }
}
