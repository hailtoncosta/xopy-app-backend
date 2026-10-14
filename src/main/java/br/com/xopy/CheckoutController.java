package br.com.xopy;

import java.math.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@RequestMapping("/api/checkout")
public class CheckoutController {
    private final JdbcTemplate db;
    private final Identity identity;
    private final ObjectMapper json;

    public CheckoutController(JdbcTemplate db, Identity identity, ObjectMapper json) {
        this.db = db;
        this.identity = identity;
        this.json = json;
    }

    public record Item(@NotNull UUID id, @Min(1) @Max(999) int quantity) {
    }

    public record Checkout(@NotEmpty @Size(max = 50) List<@Valid Item> items, @NotNull String deliveryType,
            UUID addressId, UUID requestId, BigDecimal expectedTotal) {
    }

    private record Line(UUID id, UUID seller, String title, BigDecimal price, int quantity) {
    }

    private record Summary(List<Line> lines, Map<String, Object> address, BigDecimal subtotal, BigDecimal shipping) {
        BigDecimal total() {
            return subtotal.add(shipping);
        }
    }

    private Summary summarize(Checkout request, UUID buyer, boolean lock) {
        if (!Set.of("pickup", "delivery").contains(request.deliveryType()))
            throw new ResponseStatusException(BAD_REQUEST, "Escolha entrega ou retirada");
        Map<String, Object> address = Map.of();
        if (request.deliveryType().equals("delivery")) {
            if (request.addressId() == null)
                throw new ResponseStatusException(BAD_REQUEST, "Selecione um endereço");
            var rows = db.queryForList(
                    "select street,number,neighborhood,city,state,zip_code,complement from user_addresses where id=? and user_id=?",
                    request.addressId(), buyer);
            if (rows.isEmpty())
                throw new ResponseStatusException(BAD_REQUEST, "Endereço não encontrado na sua conta");
            address = rows.get(0);
            for (String field : List.of("street", "number", "city", "state", "zip_code"))
                if (address.get(field) == null || address.get(field).toString().isBlank())
                    throw new ResponseStatusException(BAD_REQUEST, "Complete seu endereço antes da compra");
        }
        var quantities = new TreeMap<UUID, Integer>();
        for (var item : request.items()) {
            UUID id = item.id();
            var catalog = db.queryForList("select source_product_id,is_active from dynamic_products where id=?", id);
            if (!catalog.isEmpty()) {
                if (!Boolean.TRUE.equals(catalog.get(0).get("is_active"))
                        || catalog.get(0).get("source_product_id") == null)
                    throw new ResponseStatusException(CONFLICT,
                            "Produto do catálogo ainda não está disponível para compra");
                id = (UUID) catalog.get(0).get("source_product_id");
            }
            quantities.merge(id, item.quantity(), Integer::sum);
        }
        var lines = new ArrayList<Line>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (var entry : quantities.entrySet()) {
            var rows = db.queryForList(
                    "select id,user_id,title,price,quantity,status,approval_status from products where id=?"
                            + (lock ? " for update" : ""),
                    entry.getKey());
            if (rows.isEmpty())
                throw new ResponseStatusException(CONFLICT, "Produto não encontrado. Atualize o carrinho");
            var product = rows.get(0);
            if (!"active".equals(product.get("status")) || !"approved".equals(product.get("approval_status")))
                throw new ResponseStatusException(CONFLICT, "Produto indisponível: " + product.get("title"));
            UUID seller = (UUID) product.get("user_id");
            if (seller == null || seller.equals(buyer))
                throw new ResponseStatusException(BAD_REQUEST, "Não é possível comprar um produto da própria conta");
            BigDecimal stock = new BigDecimal(String.valueOf(product.get("quantity")));
            if (stock.compareTo(BigDecimal.valueOf(entry.getValue())) < 0)
                throw new ResponseStatusException(CONFLICT, "Estoque insuficiente: " + product.get("title"));
            if (product.get("price") == null)
                throw new ResponseStatusException(CONFLICT, "Produto sem preço definido");
            BigDecimal price = new BigDecimal(product.get("price").toString()).setScale(2, RoundingMode.HALF_UP);
            if (price.signum() < 0)
                throw new ResponseStatusException(CONFLICT, "Preço inválido");
            var line = new Line(entry.getKey(), seller, String.valueOf(product.get("title")), price, entry.getValue());
            lines.add(line);
            subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(line.quantity())));
        }
        BigDecimal shipping = request.deliveryType().equals("delivery")
                && subtotal.compareTo(new BigDecimal("200")) <= 0 ? new BigDecimal("15.00") : BigDecimal.ZERO;
        return new Summary(lines, address, subtotal, shipping);
    }

    private Map<String, Object> response(Summary s) {
        return Map.of("items",
                s.lines().stream()
                        .map(l -> Map.of("id", l.id(), "name", l.title(), "price", l.price(), "quantity", l.quantity(),
                                "total", l.price().multiply(BigDecimal.valueOf(l.quantity()))))
                        .toList(),
                "subtotal", s.subtotal(), "shipping", s.shipping(), "total", s.total());
    }

    @PostMapping("/quote")
    @Transactional(readOnly = true)
    public Map<String, Object> quote(@Valid @RequestBody Checkout request, @AuthenticationPrincipal Jwt jwt) {
        return response(summarize(request, identity.id(jwt), false));
    }

    @PostMapping("/orders")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(@Valid @RequestBody Checkout request, @AuthenticationPrincipal Jwt jwt)
            throws Exception {
        UUID buyer = identity.id(jwt);
        if (request.requestId() == null || request.expectedTotal() == null)
            throw new ResponseStatusException(BAD_REQUEST, "Revise o pedido antes de confirmar");
        String fingerprint = json.writeValueAsString(Map.of("items",
                request.items().stream().sorted(Comparator.comparing(Item::id)).toList(), "delivery",
                request.deliveryType(), "address", request.addressId() == null ? "" : request.addressId().toString()));
        db.update("insert into checkout_requests(buyer_id,request_id,fingerprint) values(?,?,?) on conflict do nothing",
                buyer, request.requestId(), fingerprint);
        var saved = db.queryForMap(
                "select fingerprint,result from checkout_requests where buyer_id=? and request_id=? for update", buyer,
                request.requestId());
        if (!fingerprint.equals(saved.get("fingerprint")))
            throw new ResponseStatusException(CONFLICT, "Identificador já usado por outro pedido");
        if (saved.get("result") != null)
            return json.readValue(saved.get("result").toString(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        Summary summary = summarize(request, buyer, true);
        if (summary.total().compareTo(request.expectedTotal()) != 0)
            throw new ResponseStatusException(CONFLICT, "O preço mudou. Atualize a revisão do pedido");
        var ids = new ArrayList<UUID>();
        boolean first = true;
        for (Line line : summary.lines()) {
            UUID id = UUID.randomUUID();
            BigDecimal shipping = first ? summary.shipping() : BigDecimal.ZERO;
            first = false;
            BigDecimal amount = line.price().multiply(BigDecimal.valueOf(line.quantity()));
            String metadata = json.writeValueAsString(Map.of("quantity", line.quantity(), "unit_price", line.price(),
                    "title", line.title(), "checkout_request_id", request.requestId()));
            db.update("update products set quantity=quantity-?,updated_at=now() where id=?", line.quantity(),
                    line.id());
            db.update(
                    "insert into orders(id,buyer_id,seller_id,product_id,order_type,status,payment_method,delivery_type,delivery_address,total_amount,shipping_cost,marketplace_fee,seller_amount,metadata) values(?,?,?,?,'product','pending','pending',?,?::jsonb,?,?,0,?,?::jsonb)",
                    id, buyer, line.seller(), line.id(), request.deliveryType(),
                    json.writeValueAsString(summary.address()), amount.add(shipping), shipping, amount, metadata);
            db.update("insert into notifications(user_id,title,message,type,data) values(?,?,?,'new_order',?::jsonb)",
                    line.seller(), "Novo pedido",
                    "Pedido de " + line.quantity() + " unidade(s) de " + line.title() + " aguardando pagamento",
                    json.writeValueAsString(Map.of("order_id", id)));
            ids.add(id);
        }
        var result = new LinkedHashMap<>(response(summary));
        result.put("orderIds", ids);
        result.put("status", "pending");
        db.update("update checkout_requests set result=?::jsonb where buyer_id=? and request_id=?",
                json.writeValueAsString(result), buyer, request.requestId());
        return result;
    }

    @PostMapping("/orders/{id}/cancel")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) throws Exception {
        UUID buyer = identity.id(jwt);
        var lookup = db.queryForList("select metadata,seller_id from orders where id=? and buyer_id=?", id, buyer);
        if (lookup.isEmpty())
            throw new ResponseStatusException(NOT_FOUND, "Pedido não encontrado");
        var initial = json.readTree(String.valueOf(lookup.get(0).get("metadata")));
        if (initial.has("checkout_request_id")) {
            UUID requestId = UUID.fromString(initial.path("checkout_request_id").asText());
            db.queryForList("select request_id from checkout_requests where buyer_id=? and request_id=? for update",
                    buyer, requestId);
            if (!db.queryForList(
                    "select id from payment_sessions where buyer_id=? and request_id=? and (seller_id=? or seller_id is null)",
                    buyer, requestId, lookup.get(0).get("seller_id")).isEmpty())
                throw new ResponseStatusException(CONFLICT,
                        "Pagamento já iniciado. Consulte o Mercado Pago ou solicite cancelamento ao Xopy antes de liberar o estoque.");
        }
        var rows = db.queryForList(
                "select product_id,status,metadata,seller_id from orders where id=? and buyer_id=? for update", id,
                buyer);
        if (rows.isEmpty())
            throw new ResponseStatusException(NOT_FOUND, "Pedido não encontrado");
        var order = rows.get(0);
        if ("cancelled".equals(order.get("status")))
            return Map.of("status", "cancelled");
        if (!"pending".equals(order.get("status")))
            throw new ResponseStatusException(CONFLICT, "Esse pedido não pode ser cancelado");
        var metadata = json.readTree(String.valueOf(order.get("metadata")));
        if (!metadata.has("checkout_request_id"))
            throw new ResponseStatusException(CONFLICT, "Pedido antigo exige análise do administrador");
        db.update("update products set quantity=quantity+?,updated_at=now() where id=?",
                metadata.path("quantity").asInt(), order.get("product_id"));
        db.update("update orders set status='cancelled',cancelled_at=now(),updated_at=now() where id=?", id);
        db.update("insert into notifications(user_id,title,message,type) values(?,?,?,'order_cancelled')",
                order.get("seller_id"), "Pedido cancelado", "O comprador cancelou o pedido " + id);
        return Map.of("status", "cancelled");
    }
}
