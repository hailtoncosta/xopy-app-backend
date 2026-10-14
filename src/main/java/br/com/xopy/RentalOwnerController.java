package br.com.xopy;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
@RestController
@RequestMapping("/api/site/rental-owners")
public class RentalOwnerController {
 private final JdbcTemplate db;
 public RentalOwnerController(JdbcTemplate db) {this.db=db;}
 private static final String PUBLISHED="""
 x.status='active' AND x.approval_status='approved' AND
 (x.product_type='rental' OR x.description LIKE '[LOCAÇÃO]%' OR EXISTS
 (SELECT 1 FROM dynamic_products z WHERE z.source_product_id=x.id AND z.is_active=true AND z.product_group='rental'))
 """;
 @GetMapping("/equipment/{id}")
 public Map<String,Object> equipment(@PathVariable UUID id) {
  var rows=db.queryForList("SELECT x.user_id FROM products x WHERE (x.id=? OR x.id IN (SELECT source_product_id FROM dynamic_products WHERE id=? AND is_active=true AND product_group='rental')) AND "+PUBLISHED,id,id);
  if(rows.isEmpty() || rows.get(0).get("user_id")==null)return Map.of("available",false);
  return owner(UUID.fromString(rows.get(0).get("user_id").toString()));
 }
 @GetMapping("/{id}")
 public Map<String,Object> owner(@PathVariable UUID id) {
  var rows=db.queryForList("""
   SELECT u.id,coalesce(nullif(d.details->>'companyName',''),nullif(p.seller_info->>'businessName',''),nullif(d.details->>'name',''),u.full_name) AS name,
   u.avatar_url,coalesce(nullif(p.seller_info->>'businessPhone',''),nullif(d.details->>'phone',''),u.phone) AS phone,
   u.created_at,(SELECT count(*) FROM rental_requests r WHERE r.company_id=u.id AND r.status='approved') AS total_rentals
   FROM user_profiles u JOIN app_users a ON a.id=u.id AND a.deleted_at IS NULL
   LEFT JOIN profile_details d ON d.user_id=u.id
   JOIN LATERAL (SELECT x.seller_info FROM products x WHERE x.user_id=u.id AND
   """+PUBLISHED+" ORDER BY x.updated_at DESC NULLS LAST,x.id LIMIT 1) p ON true WHERE u.id=?",id);
  if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Responsável pelo equipamento não encontrado");
  return rows.get(0);
 }
}
