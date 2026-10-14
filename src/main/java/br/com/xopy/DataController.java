package br.com.xopy;

import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/**
 * Transitional API: names and columns are server whitelisted, values
 * parameterized.
 * Unsupported domain operations fail explicitly rather than bypass
 * authorization.
 */
@RestController
@RequestMapping("/api/data")
public class DataController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Identity identity;
    private final Map<String, Map<String, String>> schema;
    private final Set<String> owned = Set.of("user_addresses", "seller_stores", "service_providers", "products",
            "push_subscriptions", "clip_likes", "clip_comments");
    private final Set<String> unsupported = Set.of("mercado_pago_tokens", "mercado_pago_transactions",
            "user_payment_methods", "rental_inquiries", "product_images", "user_roles", "conversations", "messages");

    public DataController(JdbcTemplate db, ObjectMapper json, Identity identity) throws IOException {
        this.db = db;
        this.json = json;
        this.identity = identity;
        schema = json.readValue(new ClassPathResource("schema-metadata.json").getInputStream(), new TypeReference<>() {
        });
    }

    public record Filter(String column, String op, Object value) {
    }

    public record Sort(String column, boolean ascending) {
    }

    public record Query(String action, String select, List<Filter> filters, List<Sort> order, Integer limit,
            Integer offset, Object values, String single, boolean head) {
    }

    @PostMapping("/{resource}")
    @Transactional
    public Map<String, Object> query(@PathVariable String resource, @RequestBody Query q,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        String table = resource.equals("products_public_safe") ? "products"
                : resource.equals("service_providers_public") ? "service_providers" : resource;
        if (!schema.containsKey(table))
            throw new ResponseStatusException(NOT_FOUND, "Recurso desconhecido");
        boolean admin = identity.admin(jwt);
        if (unsupported.contains(table) && !(admin && table.equals("rental_inquiries")))
            throw new ResponseStatusException(NOT_IMPLEMENTED, "Módulo ainda não convertido: " + table);
        String action = q.action() == null ? "select" : q.action();
        boolean read = action.equals("select");
        UUID uid = jwt == null ? null : identity.id(jwt);
        List<Object> params = new ArrayList<>();
        List<String> clauses = new ArrayList<>();
        boolean dynamic = table.startsWith("dynamic_");
        if (!read) {
            if (uid == null)
                throw new ResponseStatusException(UNAUTHORIZED, "Faça login");
            if (table.equals("orders") || table.equals("platform_settings"))
                throw new ResponseStatusException(NOT_IMPLEMENTED,
                        "Alteração exige serviço de negócio ainda não convertido");
            if (!admin && !owned.contains(table) && !table.equals("user_profiles") && !table.equals("notifications"))
                throw new ResponseStatusException(FORBIDDEN, "Operação não permitida");
            if (!admin && table.equals("notifications") && !Set.of("update", "delete").contains(action))
                throw new ResponseStatusException(FORBIDDEN, "Notificações só podem ser criadas pelo servidor");
            if (!admin && table.equals("user_profiles") && !Set.of("update", "upsert").contains(action))
                throw new ResponseStatusException(FORBIDDEN,
                        "Cadastro e exclusão de conta exigem serviço de autenticação");
        }
        if (!admin) {
            if (dynamic) {
                clauses.add("t.is_active = true");
            } else if (table.equals("products")) {
                clauses.add("(t.approval_status='approved' and t.status='active'"
                        + (uid == null ? "" : " OR t.user_id=?") + ")");
                if (uid != null)
                    params.add(uid);
            } else if (table.equals("service_providers")) {
                clauses.add("(t.status='approved'" + (uid == null ? "" : " OR t.user_id=?") + ")");
                if (uid != null)
                    params.add(uid);
            } else if (table.equals("seller_stores")) {
                /* public projection below */} else if (table.equals("platform_settings")) {
                /* public commission setting */} else {
                if (uid == null)
                    throw new ResponseStatusException(UNAUTHORIZED, "Faça login");
                if (table.equals("user_profiles")) {
                    clauses.add("t.id=?");
                    params.add(uid);
                } else if (table.equals("orders")) {
                    clauses.add("(t.buyer_id=? OR t.seller_id=?)");
                    params.add(uid);
                    params.add(uid);
                } else if (owned.contains(table) || table.equals("notifications")) {
                    clauses.add("t.user_id=?");
                    params.add(uid);
                } else
                    throw new ResponseStatusException(FORBIDDEN, "Acesso administrativo necessário");
            }
            if (!read && owned.contains(table)) {
                clauses.add("t.user_id=?");
                params.add(uid);
            }
        }
        for (Filter f : q.filters() == null ? List.<Filter>of() : q.filters()) {
            column(table, f.column());
            String expr = "to_jsonb(t)->> '" + f.column() + "'";
            switch (f.op()) {
                case "eq", "neq" -> {
                    clauses.add(expr + (f.op().equals("eq") ? " = ?" : " <> ?"));
                    params.add(String.valueOf(f.value()));
                }
                case "is" -> {
                    if (f.value() != null)
                        throw new ResponseStatusException(BAD_REQUEST, "is aceita apenas null nesta versão");
                    clauses.add(expr + " IS NULL");
                }
                case "in" -> {
                    if (!(f.value() instanceof List<?> v) || v.size() > 200)
                        throw new ResponseStatusException(BAD_REQUEST, "Filtro in inválido");
                    if (v.isEmpty())
                        clauses.add("false");
                    else {
                        clauses.add(expr + " IN (" + String.join(",", Collections.nCopies(v.size(), "?")) + ")");
                        v.forEach(x -> params.add(String.valueOf(x)));
                    }
                }
                case "ilike" -> {
                    clauses.add(expr + " ILIKE ?");
                    params.add(String.valueOf(f.value()));
                }
                default -> throw new ResponseStatusException(NOT_IMPLEMENTED, "Filtro ainda não convertido: " + f.op());
            }
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        if (read) {
            String projection = "to_jsonb(t)";
            if (!admin && Set.of("products", "service_providers", "seller_stores").contains(table)) {
                // Public responses exclude contact info, internal review notes and ownership
                // IDs.
                String[] privateFields = table.equals("products")
                        ? new String[] { "seller_info", "admin_comments", "user_id" }
                        : table.equals("service_providers")
                                ? new String[] { "email", "phone", "address", "documents", "user_id", "rejection_reason" }
                                : new String[] { "phone", "address", "user_id" };
                for (String field : privateFields)
                    projection += " - '" + field + "'";
            }
            String order = "";
            List<String> sorts = new ArrayList<>();
            for (Sort s : q.order() == null ? List.<Sort>of() : q.order()) {
                column(table, s.column());
                sorts.add("t.\"" + s.column() + "\" " + (s.ascending() ? "ASC" : "DESC") + " NULLS LAST");
            }
            if (!sorts.isEmpty())
                order = " ORDER BY " + String.join(",", sorts);
            else
                order = " ORDER BY t.id";
            int limit = q.limit() == null ? 200 : Math.min(Math.max(q.limit(), 1), 500);
            int offset = q.offset() == null ? 0 : Math.max(q.offset(), 0);
            Long count = db.queryForObject("SELECT count(*) FROM \"" + table + "\" t" + where, Long.class,
                    params.toArray());
            List<Object> rows = q.head() ? List.of()
                    : db.query("SELECT (" + projection + ")::text FROM \"" + table + "\" t" + where + order + " LIMIT "
                            + limit + " OFFSET " + offset, (rs, n) -> parse(rs.getString(1)), params.toArray());
            rows = project(rows, q.select());
            return result(rows, q, count);
        }
        if (Set.of("insert", "upsert").contains(action)) {
            List<?> values = q.values() instanceof List<?> l ? l : Collections.singletonList(q.values());
            if (values.isEmpty() || values.size() > 100)
                throw new ResponseStatusException(BAD_REQUEST, "Lote deve conter de 1 a 100 registros");
            List<Object> rows = new ArrayList<>();
            for (Object value : values) {
                Map<String, Object> body = body(value);
                validateWrite(table, body, admin, uid, true);
                if (body.isEmpty())
                    throw new ResponseStatusException(BAD_REQUEST, "Registro vazio");
                if (!admin && owned.contains(table))
                    body.put("user_id", uid.toString());
                if (table.equals("products") && !admin) {
                    body.put("approval_status", "pending");
                    body.put("status", "active");
                }
                if (table.equals("service_providers") && !admin)
                    body.put("status", "pending");
                if (table.equals("user_profiles")) {
                    // Auth creates profiles. A compatibility upsert only updates the caller's
                    // existing row.
                    if (!action.equals("upsert"))
                        throw new ResponseStatusException(FORBIDDEN, "Use /api/auth/register");
                    body.remove("id");
                    body.remove("email");
                    body.remove("is_verified");
                    body.remove("user_type");
                    List<Object> ownParams = new ArrayList<>(List.of(uid));
                    rows.addAll(update(table, body, " WHERE t.id=?", ownParams));
                    continue;
                }
                if (action.equals("upsert"))
                    throw new ResponseStatusException(NOT_IMPLEMENTED, "Upsert convertido apenas para user_profiles");
                if (table.equals("user_addresses") && Boolean.TRUE.equals(body.get("is_default")))
                    clearAddressDefault(uid);
                List<String> keys = new ArrayList<>(body.keySet());
                keys.forEach(k -> column(table, k));
                String names = String.join(",", keys.stream().map(k -> "\"" + k + "\"").toList());
                String fields = String.join(",", keys.stream().map(k -> "r.\"" + k + "\"").toList());
                rows.addAll(db.query(
                        "INSERT INTO \"" + table + "\" (" + names + ") SELECT " + fields
                                + " FROM jsonb_populate_record(NULL::\"" + table
                                + "\",?::jsonb) r RETURNING to_jsonb(\"" + table + "\")::text",
                        (rs, n) -> parse(rs.getString(1)), json.writeValueAsString(body)));
            }
            return result(rows, q, (long) rows.size());
        }
        if (q.filters() == null || q.filters().isEmpty())
            throw new ResponseStatusException(BAD_REQUEST, "Filtro explícito obrigatório para alterar ou excluir");
        if (action.equals("update")) {
            Map<String, Object> body = body(q.values());
            validateWrite(table, body, admin, uid, false);
            if (table.equals("products") && !admin)
                body.put("approval_status", "pending");
            if (table.equals("service_providers") && !admin)
                body.put("status", "pending");
            if (table.equals("user_addresses") && Boolean.TRUE.equals(body.get("is_default"))) {
                if (q.filters().size() != 1 || !"id".equals(q.filters().get(0).column())
                        || !"eq".equals(q.filters().get(0).op()))
                    throw new ResponseStatusException(BAD_REQUEST, "Selecionar um endereço por id");
                Long n = db.queryForObject("select count(*) from user_addresses t" + where, Long.class,
                        params.toArray());
                if (n == null || n != 1)
                    throw new ResponseStatusException(NOT_FOUND, "Endereço não encontrado");
                clearAddressDefault(uid);
            }
            List<Object> rows = update(table, body, where, params);
            return result(rows, q, (long) rows.size());
        }
        if (action.equals("delete")) {
            if (admin && table.equals("products")) {
                List<Map<String,Object>> targets = db.queryForList(
                        "SELECT t.id,t.description,t.product_type,t.approval_status FROM products t" + where + " FOR UPDATE", params.toArray());
                for (Map<String,Object> target : targets) {
                    boolean rental = "rental".equals(target.get("product_type"))
                            || Objects.toString(target.get("description"), "").startsWith("[LOCAÇÃO]");
                    if (rental && !"rejected".equals(target.get("approval_status")))
                        throw new ResponseStatusException(CONFLICT, "Somente equipamentos rejeitados podem ser excluídos");
                }
                for (Map<String,Object> target : targets)
                    db.update("DELETE FROM dynamic_products WHERE source_product_id=?", target.get("id"));
            }
            if (table.equals("user_profiles"))
                throw new ResponseStatusException(NOT_IMPLEMENTED,
                        "Excluir conta exige rotina de retenção e anonimização");
            List<Object> rows = db.query("DELETE FROM \"" + table + "\" t" + where + " RETURNING to_jsonb(t)::text",
                    (rs, n) -> parse(rs.getString(1)), params.toArray());
            return result(rows, q, (long) rows.size());
        }
        throw new ResponseStatusException(BAD_REQUEST, "Ação desconhecida");
    }

    private void clearAddressDefault(UUID uid) {
        db.queryForObject("select pg_advisory_xact_lock(?)", Object.class, (long) uid.hashCode());
        db.update("update user_addresses set is_default=false where user_id=?", uid);
    }

    private List<Object> update(String table, Map<String, Object> body, String where, List<Object> params)
            throws Exception {
        if (body.isEmpty())
            throw new ResponseStatusException(BAD_REQUEST, "Alteração vazia");
        List<String> set = new ArrayList<>();
        body.keySet().forEach(k -> {
            column(table, k);
            set.add("\"" + k + "\"=r.\"" + k + "\"");
        });
        if (schema.get(table).containsKey("updated_at")) {
            body.put("updated_at", java.time.Instant.now().toString());
            if (set.stream().noneMatch(s -> s.startsWith("\"updated_at\"")))
                set.add("updated_at=r.updated_at");
        }
        List<Object> args = new ArrayList<>();
        args.add(json.writeValueAsString(body));
        args.addAll(params);
        return db.query(
                "UPDATE \"" + table + "\" t SET " + String.join(",", set) + " FROM jsonb_populate_record(NULL::\""
                        + table + "\",?::jsonb) r" + where + " RETURNING to_jsonb(t)::text",
                (rs, n) -> parse(rs.getString(1)), args.toArray());
    }

    private void validateWrite(String table, Map<String, Object> body, boolean admin, UUID uid, boolean insert) {
        body.keySet().forEach(k -> column(table, k));
        if (!admin) {
            String owner = table.equals("user_profiles") ? "id" : "user_id";
            if (body.containsKey(owner) && !Objects.equals(String.valueOf(body.get(owner)), uid.toString()))
                throw new ResponseStatusException(FORBIDDEN, "Dono do registro inválido");
            if (!insert && (body.containsKey("id") || body.containsKey("user_id") || body.containsKey("created_at")))
                throw new ResponseStatusException(FORBIDDEN, "Identidade do registro não pode ser alterada");
            if (table.equals("products") && body.keySet().stream().anyMatch(Set.of("approval_status", "approval_date",
                    "admin_comments", "is_featured", "expires_at")::contains))
                throw new ResponseStatusException(FORBIDDEN, "Aprovação controlada pelo administrador");
            if (table.equals("service_providers") && (body.containsKey("status") || body.containsKey("rejection_reason")))
                throw new ResponseStatusException(FORBIDDEN, "Aprovação controlada pelo administrador");
            if (table.equals("user_profiles") && !insert
                    && body.keySet().stream().anyMatch(Set.of("email", "is_verified", "user_type")::contains))
                throw new ResponseStatusException(FORBIDDEN, "Campo protegido do perfil");
            if (table.equals("notifications") && !Set.of("is_read").containsAll(body.keySet()))
                throw new ResponseStatusException(FORBIDDEN, "Só é permitido marcar leitura");
        }
        if (table.equals("rental_inquiries") && (!admin || !Set.of("status","admin_notes","whatsapp_contact","updated_at").containsAll(body.keySet())))
            throw new ResponseStatusException(FORBIDDEN,"Somente dados de atendimento podem ser alterados");
        if (table.equals("rental_inquiries") && body.containsKey("status") && !Set.of("pending","in_progress","completed","cancelled").contains(Objects.toString(body.get("status"),"")))
            throw new ResponseStatusException(BAD_REQUEST,"Status inválido");
        if (table.equals("service_providers") && admin && "rejected".equals(body.get("status"))) {
            String reason=Objects.toString(body.get("rejection_reason"), "").trim();
            if (reason.isEmpty() || reason.length()>2000)
                throw new ResponseStatusException(BAD_REQUEST, "Informe o motivo da rejeição (até 2000 caracteres)");
            body.put("rejection_reason",reason);
        }
        if (table.equals("service_providers") && admin && "approved".equals(body.get("status")))
            body.put("rejection_reason",null);
        if (table.equals("products")) {
            for (String number : List.of("price", "quantity", "shipping_cost"))
                if (body.containsKey(number) && (!(body.get(number) instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0))
                    throw new ResponseStatusException(BAD_REQUEST, "Preço e quantidade devem ser não negativos");
            if (insert)
                for (String field : List.of("title", "description", "category", "price", "product_type", "seller_type"))
                    if (body.get(field) == null || body.get(field).toString().isBlank())
                        throw new ResponseStatusException(BAD_REQUEST, "Campo obrigatório: " + field);
        }
    }

    private Map<String, Object> body(Object input) {
        if (!(input instanceof Map<?, ?>))
            throw new ResponseStatusException(BAD_REQUEST, "Objeto JSON obrigatório");
        return json.convertValue(input, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private void column(String table, String col) {
        if (!schema.get(table).containsKey(col))
            throw new ResponseStatusException(BAD_REQUEST, "Coluna inválida: " + col);
    }

    private Object parse(String text) {
        try {
            return json.readValue(text, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Object> project(List<Object> rows, String select) {
        if (select == null || select.isBlank() || select.equals("*"))
            return rows;
        if (select.contains("(") || select.contains(":"))
            throw new ResponseStatusException(NOT_IMPLEMENTED, "Join ou alias de seleção ainda não convertido");
        List<String> fields = List.of(select.split(","));
        return rows.stream().map(row -> {
            Map<?, ?> src = (Map<?, ?>) row;
            Map<String, Object> dst = new LinkedHashMap<>();
            for (String f : fields)
                if (src.containsKey(f.trim()))
                    dst.put(f.trim(), src.get(f.trim()));
            return (Object) dst;
        }).toList();
    }

    private Map<String, Object> result(List<Object> rows, Query q, Long count) {
        Object data = rows;
        if ("single".equals(q.single()) && rows.size() != 1)
            throw new ResponseStatusException(NOT_FOUND, "Esperado exatamente um registro");
        if ("maybe".equals(q.single()) && rows.size() > 1)
            throw new ResponseStatusException(CONFLICT, "Mais de um registro encontrado");
        if (q.single() != null)
            data = rows.isEmpty() ? null : rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        result.put("error", null);
        result.put("count", count);
        return result;
    }
}
