package br.com.xopy;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final JdbcTemplate db;
    private final Identity identity;
    private final MercadoPagoGateway mp;
    private final TransactionTemplate tx;
    private final SellerMercadoPago sellers;

    public PaymentController(JdbcTemplate db, Identity identity, MercadoPagoGateway mp,
            PlatformTransactionManager manager, SellerMercadoPago sellers) {
        this.db = db;
        this.identity = identity;
        this.mp = mp;
        this.tx = new TransactionTemplate(manager);
        this.sellers = sellers;
    }

    @GetMapping("/config")
    public Map<String, Object> config(@AuthenticationPrincipal Jwt jwt) {
        identity.id(jwt);
        return Map.of("enabled", mp.enabled(), "sandbox", mp.sandbox(), "recipient", "xopy", "provider",
                "Mercado Pago");
    }

    @GetMapping("/admin/config")
    public Map<String, Object> adminConfig(@AuthenticationPrincipal Jwt jwt) {
        identity.requireAdmin(jwt);
        return Map.of("enabled", mp.enabled(), "sandbox", mp.sandbox(), "recipient", "xopy", "webhookUrl", mp.webhook(),
                "frontendUrl", mp.frontend(), "collectorId", mp.collector(), "missing",mp.missingConfiguration());
    }

    @PostMapping("/admin/verify")
    public Map<String,Object> verifyXopyAccount(@AuthenticationPrincipal Jwt jwt) {
        identity.requireAdmin(jwt);
        if(!mp.enabled())throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Configure no servidor: "+String.join(", ",mp.missingConfiguration()));
        var account=mp.call("GET","/users/me",null);
        String accountId=account.path("id").asText();
        if(!mp.collector().equals(accountId))throw new ResponseStatusException(CONFLICT,"O Access Token não pertence ao ID da conta Mercado Pago configurada para o Xopy");
        return Map.of("verified",true,"collectorId",accountId,"name",account.path("nickname").asText(),"sandbox",mp.sandbox(),"recipient","xopy");
    }

    private void lock(UUID buyer, UUID request) {
        if (db.queryForList("select request_id from checkout_requests where buyer_id=? and request_id=? for update",
                buyer, request).isEmpty())
            throw new ResponseStatusException(NOT_FOUND, "Compra não encontrada");
    }

    private List<Map<String, Object>> orders(UUID buyer, UUID request, UUID seller) {
        return db.queryForList(
                "select id,seller_id,total_amount,status from orders where buyer_id=? and metadata->>'checkout_request_id'=?"
                        + (seller == null ? "" : " and seller_id=?") + " order by id for update",
                seller == null ? new Object[] { buyer, request.toString() }
                        : new Object[] { buyer, request.toString(), seller });
    }

    @PostMapping("/checkouts/{request}/start")
    public Map<String, Object> start(@PathVariable UUID request, @RequestParam UUID seller,
            @AuthenticationPrincipal Jwt jwt) {
        var account = identity.require(jwt);
        UUID buyer = (UUID) account.get("id");
        if (!mp.enabled())
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "O pagamento ainda não foi ativado pelo Xopy. Seu pedido continua salvo em Meus Pedidos.");
        // Commit a durable creation marker before contacting MP; an ambiguous timeout
        // never creates a second preference.
        var session = tx.execute(s -> {
            lock(buyer, request);
            if (!db.queryForList(
                    "select id from payment_sessions where buyer_id=? and request_id=? and seller_id is null", buyer,
                    request).isEmpty())
                throw new ResponseStatusException(CONFLICT,
                        "Esta compra foi iniciada no modelo anterior de recebimento. Procure o Xopy para regularização");
            var existing = db.queryForList(
                    "select * from payment_sessions where buyer_id=? and request_id=? and seller_id=? for update",
                    buyer, request, seller);
            if (!existing.isEmpty())
                return existing.get(0);
            var rows = orders(buyer, request, seller);
            if (rows.isEmpty() || rows.stream().anyMatch(o -> !"pending".equals(o.get("status"))))
                throw new ResponseStatusException(CONFLICT, "Essa compra não está disponível para pagamento");
            BigDecimal amount = rows.stream().map(o -> new BigDecimal(o.get("total_amount").toString()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (amount.signum() <= 0)
                throw new ResponseStatusException(CONFLICT, "Valor da compra inválido");
            UUID id = UUID.randomUUID();
            var credentials = new SellerMercadoPago.Credentials("", mp.collector(), mp.sandbox());
            db.update(
                    "insert into payment_sessions(id,buyer_id,request_id,seller_id,amount,collector_id,sandbox,recipient,status) values(?,?,?,?,?,?,?,'xopy','creating')",
                    id, buyer, request, seller, amount, credentials.collector(), credentials.sandbox());
            var result = new HashMap<String, Object>();
            result.put("id", id);
            result.put("amount", amount);
            result.put("status", "creating");
            result.put("new", true);
            result.put("credentials", credentials);
            result.put("recipient", "xopy");
            result.put("seller_id", seller);
            result.put("collector_id", credentials.collector());
            return result;
        });
        if (!Boolean.TRUE.equals(session.get("new")) && !"xopy".equals(session.get("recipient")))
            throw new ResponseStatusException(CONFLICT,
                    "Pagamento iniciado no modelo anterior. Consulte o status ou procure o Xopy antes de pagar novamente.");
        if (!Boolean.TRUE.equals(session.get("new"))) {
            refreshSession(session);
            return view(db.queryForMap("select * from payment_sessions where id=?", session.get("id")));
        }
        try {
            var credentials = (SellerMercadoPago.Credentials) session.get("credentials");
            if (!credentials.collector().equals(mp.call("GET", "/users/me", null).path("id").asText()))
                throw new ResponseStatusException(CONFLICT, "Credenciais não pertencem à conta Mercado Pago do Xopy");
            boolean rentalCheckout = !db.queryForList(
                    "select id from rental_requests where checkout_id=? and customer_id=?", request, buyer).isEmpty();
            String back = mp.frontend() + "/pagamento/" + request + (rentalCheckout ? "?rental=1" : "");
            var body = new LinkedHashMap<String, Object>();
            body.put("items", List.of(Map.of("id", request.toString(), "title", "Compra Xopy", "quantity", 1,
                    "unit_price", session.get("amount"), "currency_id", "BRL")));
            body.put("payer", Map.of("email", account.get("email")));
            body.put("external_reference", session.get("id").toString());
            body.put("notification_url", mp.webhook() + "?session_id=" + session.get("id"));
            body.put("back_urls", Map.of("success", back, "pending", back, "failure", back));
            body.put("auto_return", "approved");
            body.put("payment_methods",
                    Map.of("excluded_payment_types", List.of(Map.of("id", "ticket")), "installments", 12));
            var preference = mp.call("POST", "/checkout/preferences", body);
            if (preference.path("id").asText().isBlank()
                    || !credentials.collector().equals(preference.path("collector_id").asText()))
                throw new ResponseStatusException(BAD_GATEWAY, "Preferência não corresponde à conta do Xopy");
            String url = MercadoPagoGateway
                    .safeCheckoutUrl(preference.path(mp.sandbox() ? "sandbox_init_point" : "init_point").asText());
            tx.executeWithoutResult(s -> {
                lock(buyer, request);
                db.update(
                        "update payment_sessions set preference_id=?,checkout_url=?,status=case when status='creating' then 'ready' else status end,updated_at=now() where id=?",
                        preference.path("id").asText(), url, session.get("id"));
            });
            return view(db.queryForMap("select * from payment_sessions where id=?", session.get("id")));
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> db.update(
                    "update payment_sessions set status='review_required',updated_at=now() where id=? and status='creating'",
                    session.get("id")));
            throw e;
        }
    }

    private Map<String, Object> view(Map<String, Object> session) {
        var result = new HashMap<String, Object>();
        for (String key : List.of("amount", "status", "checkout_url", "approved_payment_id", "seller_id",
                "collector_id", "recipient"))
            if (session.get(key) != null)
                result.put(key, session.get(key));
        result.put("enabled", mp.enabled());
        return result;
    }

    @GetMapping("/checkouts/{request}")
    public Map<String, Object> get(@PathVariable UUID request, @AuthenticationPrincipal Jwt jwt) {
        UUID buyer = identity.id(jwt);
        return tx.execute(s -> {
            lock(buyer, request);
            var rows = orders(buyer, request, null);
            if (rows.isEmpty())
                throw new ResponseStatusException(NOT_FOUND, "Pedidos não encontrados");
            var sessions = db.queryForList(
                    "select * from payment_sessions where buyer_id=? and request_id=? order by seller_id", buyer,
                    request);
            var groups = new ArrayList<Map<String, Object>>();
            if (sessions.stream().anyMatch(p -> p.get("seller_id") == null)) {
                var legacy = view(sessions.stream().filter(p -> p.get("seller_id") == null).findFirst().orElseThrow());
                legacy.remove("checkout_url");
                legacy.put("legacy", true);
                legacy.put("sellerName", "Compra do modelo anterior");
                groups.add(legacy);
            } else {
                var sellerIds = new TreeSet<UUID>();
                for (var order : rows)
                    sellerIds.add((UUID) order.get("seller_id"));
                for (UUID seller : sellerIds) {
                    var saved = sessions.stream().filter(p -> seller.equals(p.get("seller_id"))).findFirst();
                    var group = saved.isPresent() ? view(saved.get()) : new HashMap<String, Object>();
                    var sellerOrders = rows.stream().filter(o -> seller.equals(o.get("seller_id"))).toList();
                    if (saved.isEmpty()) {
                        group.put("seller_id", seller);
                        group.put("amount",
                                sellerOrders.stream().map(o -> new BigDecimal(o.get("total_amount").toString()))
                                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                        group.put("status",
                                sellerOrders.stream().allMatch(o -> "pending".equals(o.get("status"))) ? "not_started"
                                        : "closed");
                    }
                    var names = db.queryForList("select full_name from user_profiles where id=?", seller);
                    group.put("sellerName",
                            names.isEmpty() || names.get(0).get("full_name") == null
                                    ? "Vendedor " + seller.toString().substring(0, 8)
                                    : names.get(0).get("full_name"));
                    boolean historical = saved.isPresent() && !"xopy".equals(saved.get().get("recipient"));
                    group.put("historical", historical);
                    group.put("receivingEnabled", mp.enabled() && !historical);
                    if (historical)
                        group.remove("checkout_url");
                    groups.add(group);
                }
            }
            return Map.of("enabled", mp.enabled(), "groups", groups, "amount",
                    rows.stream().map(o -> new BigDecimal(o.get("total_amount").toString())).reduce(BigDecimal.ZERO,
                            BigDecimal::add));
        });
    }

    private JsonNode callForSession(Map<String, Object> session, String method, String path, Object body) {
        if ("xopy".equals(session.get("recipient")) || session.get("seller_id") == null) {
            if (!mp.collector().equals(session.get("collector_id"))
                    || mp.sandbox() != Boolean.TRUE.equals(session.get("sandbox")))
                throw new ResponseStatusException(CONFLICT,
                        "A conta Xopy configurada não corresponde ao recebimento original");
            return mp.call(method, path, body);
        }
        var credentials = sellers.credentials((UUID) session.get("seller_id"), false);
        if (!credentials.collector().equals(session.get("collector_id"))
                || credentials.sandbox() != Boolean.TRUE.equals(session.get("sandbox")))
            throw new ResponseStatusException(CONFLICT, "A conta conectada não corresponde ao recebedor desta compra");
        return mp.call(credentials.token(), method, path, body);
    }

    private void refreshSession(Map<String, Object> session) {
        var found = callForSession(session, "GET", "/v1/payments/search?external_reference=" + session.get("id")
                + "&sort=date_created&criteria=desc&limit=100", null);
        for (var payment : found.path("results")) {
            String id = payment.path("id").asText();
            if (id.matches("[0-9]+"))
                reconcile(callForSession(session, "GET", "/v1/payments/" + id, null));
        }
    }

    @PostMapping("/checkouts/{request}/refresh")
    public Map<String, Object> refresh(@PathVariable UUID request, @AuthenticationPrincipal Jwt jwt) {
        UUID buyer = identity.id(jwt);
        var sessions = db.queryForList("select * from payment_sessions where buyer_id=? and request_id=?", buyer,
                request);
        var errors = new ArrayList<String>();
        for (var session : sessions)
            try {
                refreshSession(session);
            } catch (ResponseStatusException e) {
                errors.add("Não foi possível atualizar um recebimento. Consulte novamente ou procure o Xopy.");
            }
        var result = new HashMap<>(get(request, jwt));
        if (!errors.isEmpty())
            result.put("refreshErrors", errors);
        return result;
    }

    @PostMapping("/webhook")
    public Map<String, Object> webhook(@RequestParam(name = "data.id", required = false) String id,
            @RequestParam(name = "session_id", required = false) UUID sessionId,
            @RequestHeader(name = "x-request-id", required = false) String requestId,
            @RequestHeader(name = "x-signature", required = false) String signature,
            @RequestBody(required = false) JsonNode body) {
        mp.verify(id, requestId, signature);
        if (id == null || !id.matches("[0-9]+"))
            throw new ResponseStatusException(BAD_REQUEST, "Identificador inválido");
        if (body != null && body.has("type") && !"payment".equals(body.path("type").asText()))
            return Map.of("received", true);
        JsonNode payment;
        if (sessionId != null) {
            var rows = db.queryForList("select * from payment_sessions where id=?", sessionId);
            if (rows.isEmpty())
                return Map.of("received", true);
            payment = callForSession(rows.get(0), "GET", "/v1/payments/" + id, null);
            if (!sessionId.toString().equals(payment.path("external_reference").asText()))
                throw new ResponseStatusException(CONFLICT, "Notificação não corresponde ao recebimento");
        } else {
            var known = db.queryForList(
                    "select s.* from payment_sessions s join payment_events e on e.session_id=s.id where e.payment_id=?",
                    id);
            if (!known.isEmpty())
                payment = callForSession(known.get(0), "GET", "/v1/payments/" + id, null);
            else {
                String collector = body == null ? "" : body.path("user_id").asText();
                var receivers = collector.matches("[0-9]+")
                        ? db.queryForList("select seller_id from seller_mp_accounts where collector_id=?", collector)
                        : List.<Map<String, Object>>of();
                if (receivers.isEmpty() || mp.collector().equals(collector))
                    payment = mp.call("GET", "/v1/payments/" + id, null);
                else {
                    var credentials = sellers.credentials((UUID) receivers.get(0).get("seller_id"), false);
                    payment = mp.call(credentials.token(), "GET", "/v1/payments/" + id, null);
                }
            }
        }
        reconcile(payment);
        return Map.of("received", true);
    }

    static boolean matchesPayment(JsonNode payment, Map<String, Object> session) {
        return session.get("id").toString().equals(payment.path("external_reference").asText())
                && session.get("collector_id").toString().equals(payment.path("collector_id").asText())
                && "BRL".equals(payment.path("currency_id").asText()) && payment.path("transaction_amount").isNumber()
                && new BigDecimal(session.get("amount").toString())
                        .compareTo(payment.path("transaction_amount").decimalValue()) == 0
                && payment.path("live_mode").isBoolean()
                && payment.path("live_mode").asBoolean() != Boolean.TRUE.equals(session.get("sandbox"));
    }

    private void updateOrders(Map<String, Object> session, String status, String method, UUID buyer, UUID request) {
        String sql = "update orders set status=?,payment_method=?,updated_at=now()"
                + (status.equals("paid") ? ",paid_at=now()" : "")
                + " where buyer_id=? and metadata->>'checkout_request_id'=?"
                + (session.get("seller_id") == null ? "" : " and seller_id=?")
                + (status.equals("paid") ? " and status='pending'" : "");
        db.update(sql, session.get("seller_id") == null ? new Object[] { status, method, buyer, request.toString() }
                : new Object[] { status, method, buyer, request.toString(), session.get("seller_id") });
    }

    void reconcile(JsonNode payment) {
        UUID sessionId;
        try {
            sessionId = UUID.fromString(payment.path("external_reference").asText());
        } catch (Exception e) {
            return;
        }
        var candidates = db.queryForList("select buyer_id,request_id from payment_sessions where id=?", sessionId);
        if (candidates.isEmpty())
            return;
        tx.executeWithoutResult(s -> {
            var info = candidates.get(0);
            UUID buyer = (UUID) info.get("buyer_id"), request = (UUID) info.get("request_id");
            lock(buyer, request);
            var session = db.queryForMap("select * from payment_sessions where id=? for update", sessionId);
            if (!matchesPayment(payment, session))
                throw new ResponseStatusException(CONFLICT, "Pagamento não corresponde à compra ou à conta recebedora");
            String id = payment.path("id").asText(), status = payment.path("status").asText(),
                    method = payment.path("payment_method_id").asText();
            if (!id.matches("[0-9]+"))
                throw new ResponseStatusException(CONFLICT, "Pagamento inválido");
            db.update(
                    "insert into payment_events(payment_id,session_id,status,method,amount) values(?,?,?,?,?) on conflict(payment_id) do update set status=excluded.status,method=excluded.method,updated_at=now()",
                    id, sessionId, status, method, session.get("amount"));
            if ("approved".equals(status)) {
                if (session.get("approved_payment_id") != null) {
                    if (!id.equals(session.get("approved_payment_id")))
                        db.update("update payment_sessions set status='duplicate_review',updated_at=now() where id=?",
                                sessionId);
                    return;
                }
                var rows = orders(buyer, request, (UUID) session.get("seller_id"));
                if (rows.isEmpty() || rows.stream().anyMatch(o -> !"pending".equals(o.get("status")))) {
                    db.update("update payment_sessions set status='review_required',updated_at=now() where id=?",
                            sessionId);
                    return;
                }
                updateOrders(session, "paid", method, buyer, request);
                db.update(
                        "update payment_sessions set status='approved',approved_payment_id=?,updated_at=now() where id=?",
                        id, sessionId);
                db.update("insert into notifications(user_id,title,message,type) values(?,?,?,'payment_approved')",
                        buyer, "Pagamento aprovado", "O Mercado Pago confirmou o recebimento na conta do Xopy.");
                db.update(
                        "insert into notifications(user_id,title,message,type) select distinct seller_id,'Pagamento aprovado','Pagamento confirmado para os pedidos desta compra.','payment_approved' from orders where buyer_id=? and metadata->>'checkout_request_id'=?"
                                + (session.get("seller_id") == null ? "" : " and seller_id=?"),
                        session.get("seller_id") == null ? new Object[] { buyer, request.toString() }
                                : new Object[] { buyer, request.toString(), session.get("seller_id") });
            } else if (id.equals(session.get("approved_payment_id"))
                    && Set.of("refunded", "charged_back").contains(status)) {
                db.update("update payment_sessions set status=?,updated_at=now() where id=?", status, sessionId);
                updateOrders(session, status, method, buyer, request);
            } else if (session.get("approved_payment_id") == null
                    && Set.of("pending", "in_process", "authorized").contains(status))
                db.update(
                        "update payment_sessions set status='pending',updated_at=now() where id=? and status not in ('review_required','duplicate_review')",
                        sessionId);
            else if (session.get("approved_payment_id") == null && Set.of("rejected", "cancelled").contains(status)) {
                var active = db.queryForList(
                        "select payment_id from payment_events where session_id=? and status in ('pending','in_process','authorized')",
                        sessionId);
                if (active.isEmpty() && session.get("checkout_url") != null)
                    db.update(
                            "update payment_sessions set status='ready',updated_at=now() where id=? and status not in ('review_required','duplicate_review')",
                            sessionId);
            }
        });
    }
}
