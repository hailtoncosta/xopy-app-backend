package br.com.xopy;

import java.math.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/rental-requests")
public class RentalCheckoutController {
    private final JdbcTemplate db;
    private final Identity identity;
    private final ObjectMapper json;
    private final String adminEmail;

    public RentalCheckoutController(JdbcTemplate db, Identity identity, ObjectMapper json,
            @Value("${app.bootstrap-admin-email}") String adminEmail) {
        this.db = db;
        this.identity = identity;
        this.json = json;
        this.adminEmail = adminEmail;
    }

    @PostMapping("/{id}/checkout")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkout(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) throws Exception {
        UUID buyer = identity.id(jwt);
        var requests = db.queryForList("SELECT * FROM rental_requests WHERE id=? AND customer_id=? FOR UPDATE", id,
                buyer);
        if (requests.isEmpty())
            throw new ResponseStatusException(NOT_FOUND, "Solicitação não encontrada na sua conta");
        var rental = requests.get(0);
        if (!"approved".equals(rental.get("status")))
            throw new ResponseStatusException(CONFLICT, "Aguarde a aprovação da empresa antes de pagar");
        if (rental.get("contract_document_id") == null)
            throw new ResponseStatusException(CONFLICT,
                    "A empresa precisa enviar o contrato de locação antes do pagamento");
        if (rental.get("checkout_id") != null)
            return Map.of("checkoutId", rental.get("checkout_id"));
        UUID seller = (UUID) rental.get("company_id");
        if (seller == null)
            seller = (UUID) rental.get("reviewed_by");
        if (seller == null) {
            var admins = db.queryForList("SELECT id FROM app_users WHERE lower(email)=lower(?) AND role='ADMIN'",
                    adminEmail);
            if (admins.isEmpty())
                throw new ResponseStatusException(CONFLICT,
                        "A empresa responsável precisa ser definida antes do pagamento");
            seller = (UUID) admins.get(0).get("id");
        }
        if (buyer.equals(seller))
            throw new ResponseStatusException(BAD_REQUEST, "Não é possível pagar um aluguel da própria conta");
        UUID equipment = (UUID) rental.get("equipment_id");
        var prices = db.queryForList("SELECT price::text AS price FROM products WHERE id=? AND user_id=?", equipment,
                seller);
        if (prices.isEmpty() && rental.get("company_id") == null)
            prices = db.queryForList(
                    "SELECT price FROM dynamic_rentals WHERE id=? UNION ALL SELECT price FROM dynamic_products WHERE id=? AND source_product_id IS NULL AND product_group='rental'",
                    equipment, equipment);
        if (prices.isEmpty() || prices.get(0).get("price") == null)
            throw new ResponseStatusException(CONFLICT, "A empresa precisa definir a diária do equipamento");
        BigDecimal daily = parsePrice(prices.get(0).get("price").toString());
        int days = ((Number) rental.get("days")).intValue();
        BigDecimal shipping = new BigDecimal(Objects.toString(rental.get("shipping_cost"), "0")).setScale(2,
                RoundingMode.HALF_UP);
        if (shipping.signum() < 0)
            throw new ResponseStatusException(CONFLICT, "Frete inválido");
        BigDecimal amount = daily.multiply(BigDecimal.valueOf(days)).add(shipping).setScale(2, RoundingMode.HALF_UP);
        UUID checkout = UUID.randomUUID(), order = UUID.randomUUID();
        var result = Map.of("orderIds", List.of(order), "total", amount, "status", "pending");
        db.update("INSERT INTO checkout_requests(buyer_id,request_id,fingerprint,result) VALUES(?,?,?,?::jsonb)", buyer,
                checkout, "rental:" + id, json.writeValueAsString(result));
        String metadata = json.writeValueAsString(Map.of("checkout_request_id", checkout, "rental_request_id", id,
                "days", days, "unit_price", daily, "shipping_cost", shipping, "title", rental.get("equipment_name")));
        String address = json.writeValueAsString(Map.of("address", rental.get("delivery_address")));
        String info = json.writeValueAsString(Map.of("days", days, "daily_rate", daily, "shipping_cost", shipping,
                "equipment_name", rental.get("equipment_name"), "rental_request_id", id));
        db.update(
                "INSERT INTO orders(id,buyer_id,seller_id,rental_id,order_type,status,payment_method,delivery_type,delivery_address,total_amount,shipping_cost,marketplace_fee,seller_amount,metadata,rental_info) VALUES(?,?,?,?,'rental','pending','pending','delivery',?::jsonb,?,?,0,?,?::jsonb,?::jsonb)",
                order, buyer, seller, equipment, address, amount, shipping, amount, metadata, info);
        db.update("UPDATE rental_requests SET checkout_id=? WHERE id=?", checkout, id);
        return Map.of("checkoutId", checkout);
    }

    static BigDecimal parsePrice(String raw) {
        try {
            String value = raw.replaceAll("[^0-9.,-]", "");
            if (value.contains(","))
                value = value.replace(".", "").replace(',', '.');
            BigDecimal price = new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
            if (price.signum() <= 0)
                throw new NumberFormatException();
            return price;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(CONFLICT, "Diária inválida. Entre em contato com a empresa");
        }
    }
}
