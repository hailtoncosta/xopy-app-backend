package br.com.xopy;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/catalog")
public class PublicCatalogController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public PublicCatalogController(JdbcTemplate db, ObjectMapper json) { this.db=db; this.json=json; }

    @GetMapping("/providers")
    public List<Map<String,Object>> providers() {
        return db.queryForList("SELECT s.id,s.name,s.category,s.city,s.neighborhood,p.avatar_url FROM service_providers s JOIN app_users a ON a.id=s.user_id AND a.deleted_at IS NULL LEFT JOIN user_profiles p ON p.id=s.user_id WHERE s.status='approved' ORDER BY lower(s.name),s.id");
    }

    @GetMapping("/providers/{id}")
    public Map<String,Object> provider(@PathVariable UUID id) throws Exception {
        var rows=db.queryForList("SELECT s.*,p.avatar_url,d.details FROM service_providers s JOIN app_users a ON a.id=s.user_id AND a.deleted_at IS NULL LEFT JOIN user_profiles p ON p.id=s.user_id LEFT JOIN profile_details d ON d.user_id=s.user_id WHERE s.id=? AND s.status='approved'",id);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND,"Prestador não encontrado ou indisponível");
        var row=rows.get(0);
        Map<String,Object> saved=row.get("details")==null ? Map.of() : json.readValue(row.get("details").toString(),Map.class);
        Map<String,Object> details=new LinkedHashMap<>();
        for(String key:List.of("name","category","experience","city","neighborhood","description","specialties")) details.put(key,Objects.toString(row.get(key),""));
        for(String key:List.of("nickname","profession","certification","courses","previousCompanies","experienceTime","portfolioPhotos","completedServices","serviceConditions","schedule")) details.put(key,Objects.toString(saved.get(key),""));
        details.put("dailyRate",Objects.toString(row.get("daily_rate"),""));
        var reviews=db.queryForList("SELECT r.id,r.rating,r.comment,r.created_at,p.full_name AS reviewer_name FROM professional_reviews r LEFT JOIN user_profiles p ON p.id=r.reviewer_user_id WHERE r.provider_user_id=? ORDER BY r.created_at DESC",row.get("user_id"));
        return Map.of("details",details,"reviews",reviews,"profile",Map.of("name",Objects.toString(row.get("name"),""),"avatar",Objects.toString(row.get("avatar_url"),"")));
    }

    @GetMapping("/rental-owners")
    public List<Map<String,Object>> rentalOwners() {
        return db.queryForList("SELECT t.id,COALESCE(NULLIF(d.details->>'companyName',''),NULLIF(t.seller_info->>'businessName',''),NULLIF(p.full_name,''),NULLIF(t.seller_info->>'name',''),'Locador não informado') AS owner_name FROM products t LEFT JOIN user_profiles p ON p.id=t.user_id LEFT JOIN profile_details d ON d.user_id=t.user_id WHERE t.approval_status='approved' AND t.status='active' AND (t.product_type='rental' OR t.description LIKE '[LOCAÇÃO]%' OR EXISTS (SELECT 1 FROM dynamic_products z WHERE z.source_product_id=t.id AND z.is_active=true AND z.product_group='rental'))");
    }
}
