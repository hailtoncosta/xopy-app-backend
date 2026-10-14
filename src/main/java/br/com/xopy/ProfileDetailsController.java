package br.com.xopy;

import java.util.*;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/profile-details")
public class ProfileDetailsController {
    private final JdbcTemplate db;
    private final Identity identity;
    private final ObjectMapper json;

    public ProfileDetailsController(JdbcTemplate db, Identity identity, ObjectMapper json) {
        this.db = db;
        this.identity = identity;
        this.json = json;
    }

    @GetMapping
    public Map<String, Object> get(@AuthenticationPrincipal Jwt jwt) throws Exception {
        UUID uid = identity.id(jwt);
        var rows = db.queryForList("SELECT details FROM profile_details WHERE user_id=?", uid);
        Map<String, Object> details = new LinkedHashMap<>();
        if (!rows.isEmpty()) details.putAll(json.readValue(rows.get(0).get("details").toString(), Map.class));
        boolean completed = !rows.isEmpty();
        String type = db.queryForObject("SELECT user_type FROM user_profiles WHERE id=?", String.class, uid);
        if ("service_provider".equals(type)) {
            var providers = db.queryForList("SELECT * FROM service_providers WHERE user_id=? ORDER BY created_at DESC LIMIT 1", uid);
            if (!providers.isEmpty()) {
                var provider = providers.get(0);
                for (String key : List.of("name", "phone", "category", "experience", "city", "neighborhood", "address", "description", "specialties"))
                    details.put(key, Objects.toString(provider.get(key), ""));
                details.put("dailyRate", Objects.toString(provider.get("daily_rate"), ""));
            }
            completed = !providers.isEmpty() && !Objects.toString(details.get("category"), "").isBlank()
                && !Objects.toString(details.get("experience"), "").isBlank()
                && Objects.toString(details.get("phone"), "").replaceAll("\\D", "").matches("[0-9]{10,11}")
                && providers.get(0).get("daily_rate") != null
                && new BigDecimal(providers.get(0).get("daily_rate").toString()).signum() > 0;
        }
        var reviews = "service_provider".equals(type)
            ? db.queryForList("SELECT r.id,r.rating,r.comment,r.created_at,p.full_name AS reviewer_name FROM professional_reviews r LEFT JOIN user_profiles p ON p.id=r.reviewer_user_id WHERE r.provider_user_id=? ORDER BY r.created_at DESC", uid)
            : List.of();
        return Map.of("completed", completed, "details", details, "reviews", reviews);
    }

    @PutMapping
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(@AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, String> input)
            throws Exception {
        UUID uid = identity.id(jwt);
        String userType = db.queryForObject("SELECT user_type FROM user_profiles WHERE id=?", String.class, uid);
        boolean provider = "service_provider".equals(userType);
        Map<String, String> data = provider ? validateProvider(input) : validate(input);
        if (provider) {
            db.queryForObject("SELECT id FROM user_profiles WHERE id=? FOR UPDATE", UUID.class, uid);
            String email = db.queryForObject("SELECT email FROM user_profiles WHERE id=?", String.class, uid);
            var existing = db.queryForList("SELECT id FROM service_providers WHERE user_id=? ORDER BY created_at DESC LIMIT 1", uid);
            Object[] values = { data.get("name"), email, data.get("phone"), data.get("category"), data.get("experience"),
                new BigDecimal(data.get("dailyRate")), data.get("city"), data.get("neighborhood"), data.get("address"),
                data.get("description"), data.get("specialties"), uid };
            if (existing.isEmpty()) {
                db.update("INSERT INTO service_providers(name,email,phone,category,experience,daily_rate,city,neighborhood,address,description,specialties,user_id,status,documents) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'pending','[]'::jsonb)", values);
            } else {
                List<Object> params = new ArrayList<>(Arrays.asList(values)); params.add(existing.get(0).get("id"));
                db.update("UPDATE service_providers SET name=?,email=?,phone=?,category=?,experience=?,daily_rate=?,city=?,neighborhood=?,address=?,description=?,specialties=?,user_id=?,status='pending',rejection_reason=NULL,updated_at=now() WHERE id=?", params.toArray());
            }
        }
        db.update("UPDATE user_profiles SET full_name=?,phone=?,updated_at=now() WHERE id=?", data.get("name"),
                data.get("phone"), uid);
        db.update(
                "INSERT INTO profile_details(user_id,details) VALUES(?,?::jsonb) ON CONFLICT(user_id) DO UPDATE SET details=excluded.details,updated_at=now()",
                uid, json.writeValueAsString(data));
        return Map.of("completed", true, "details", data);
    }

    static Map<String, String> validateProvider(Map<String, String> input) {
        Map<String, String> data = new LinkedHashMap<>();
        for (String key : List.of("name", "phone", "category", "experience", "dailyRate", "city", "neighborhood", "address", "description", "specialties", "nickname", "profession", "taxId", "certification", "courses", "previousCompanies", "experienceTime", "portfolioPhotos", "completedServices", "serviceConditions", "schedule"))
            data.put(key, Objects.toString(input.get(key), "").trim());
        for (String field : List.of("nickname", "profession", "experienceTime"))
            if (data.get(field).length() > 150) fail("Um dos campos excede 150 caracteres");
        for (String field : List.of("certification", "courses", "previousCompanies", "serviceConditions", "schedule"))
            if (data.get(field).length() > 2000) fail("Um dos campos excede 2000 caracteres");
        if (data.get("completedServices").length() > 5000) fail("Descrição de serviços muito longa");
        data.put("taxId", data.get("taxId").replaceAll("\\D", ""));
        if (!data.get("taxId").isEmpty() && !data.get("taxId").matches("[0-9]{11}|[0-9]{14}")) fail("Informe CPF com 11 dígitos ou CNPJ com 14 dígitos");
        String photos = data.get("portfolioPhotos");
        if (photos.length() > 12000) fail("Galeria muito longa");
        List<String> photoUrls = photos.lines().map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (photoUrls.size() > 12) fail("A galeria permite até 12 fotos");
        for (String url : photoUrls) {
            try {
                java.net.URI uri = java.net.URI.create(url);
                if (!(Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) && uri.getHost() != null)
                    && !url.startsWith("/api/files/public/portfolio-images/")) fail("Endereço de foto inválido");
            } catch (IllegalArgumentException e) { fail("Endereço de foto inválido"); }
        }
        data.put("portfolioPhotos", String.join("\n", photoUrls));
        data.put("type", "individual");
        data.put("phone", data.get("phone").replaceAll("\\D", ""));
        if (data.get("name").length() < 3 || data.get("name").length() > 150) fail("Informe seu nome completo");
        if (!data.get("phone").matches("[0-9]{10,11}")) fail("Informe o telefone com DDD");
        if (!Set.of("pedreiro", "pintor", "eletricista", "encanador", "engenheiro", "arquiteto", "marceneiro", "jardineiro").contains(data.get("category"))) fail("Selecione uma categoria válida");
        if (!Set.of("iniciante", "intermediario", "experiente", "especialista").contains(data.get("experience"))) fail("Selecione a experiência");
        try {
            BigDecimal rate = new BigDecimal(data.get("dailyRate"));
            if (rate.signum() <= 0 || rate.compareTo(new BigDecimal("999999999.99")) > 0 || rate.scale() > 2) fail("Informe um valor por dia válido");
        } catch (NumberFormatException e) { fail("Informe um valor por dia válido"); }
        if (data.get("city").length() > 100 || data.get("neighborhood").length() > 100 || data.get("address").length() > 500 || data.get("description").length() > 5000 || data.get("specialties").length() > 1000) fail("Um dos campos excede o limite de caracteres");
        return data;
    }

    static Map<String, String> validate(Map<String, String> input) {
        Map<String, String> data = new LinkedHashMap<>();
        for (String field : List.of("type", "name", "taxId", "phone", "address", "city", "state", "zipCode",
                "companyName"))
            data.put(field, Objects.toString(input.get(field), "").trim());
        String type = data.get("type");
        if (!Set.of("individual", "business").contains(type))
            fail("Selecione pessoa física ou empresa");
        for (String field : List.of("taxId", "phone", "zipCode"))
            data.put(field, data.get(field).replaceAll("\\D", ""));
        data.put("state", data.get("state").toUpperCase(Locale.ROOT));
        if (data.get("name").length() < 3 || data.get("name").length() > 150)
            fail("Informe seu nome completo");
        if (!data.get("taxId").matches(type.equals("business") ? "[0-9]{14}" : "[0-9]{11}"))
            fail(type.equals("business") ? "Informe o CNPJ com 14 dígitos" : "Informe o CPF com 11 dígitos");
        if (!data.get("phone").matches("[0-9]{10,11}"))
            fail("Informe o telefone com DDD");
        if (data.get("address").length() < 5 || data.get("address").length() > 500 || data.get("city").length() < 2
                || data.get("city").length() > 100)
            fail("Informe endereço e cidade");
        if (!Set.of("AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO", "MA", "MT", "MS", "MG", "PA", "PB", "PR",
                "PE", "PI", "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO").contains(data.get("state")))
            fail("Selecione um estado válido");
        if (!data.get("zipCode").matches("[0-9]{8}"))
            fail("Informe o CEP com 8 dígitos");
        if (type.equals("business") && (data.get("companyName").length() < 2 || data.get("companyName").length() > 200))
            fail("Informe o nome da empresa");
        if (type.equals("individual"))
            data.put("companyName", "");
        return data;
    }

    private static void fail(String message) {
        throw new ResponseStatusException(BAD_REQUEST, message);
    }
}
