package br.com.xopy;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/admin/rental-inquiries")
public class AdminRentalInquiriesController {
 private final JdbcTemplate db;private final Identity identity;
 public AdminRentalInquiriesController(JdbcTemplate db,Identity identity){this.db=db;this.identity=identity;}
 @GetMapping public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt){identity.requireAdmin(jwt);
  var result=new ArrayList<>(db.queryForList("SELECT i.*,'inquiry' AS source FROM rental_inquiries i ORDER BY i.created_at DESC"));
  result.addAll(db.queryForList("SELECT r.id,coalesce(p.full_name,'Cliente') AS name,coalesce(p.phone,'') AS phone,coalesce(d.details->>'city','') AS city,r.equipment_name AS equipment_type,r.equipment_name AS rental_item_name,r.equipment_id AS rental_item_id,r.days AS rental_days,o.total_amount,o.payment_method,(o.rental_info->>'daily_rate')::numeric AS daily_rate,CASE WHEN o.id IS NULL THEN NULL WHEN o.status='paid' THEN 'pago' WHEN o.status IN ('cancelled','refunded') THEN 'cancelado' ELSE 'pendente' END AS payment_status,r.delivery_address AS description,CASE WHEN r.status='approved' THEN 'completed' WHEN r.status='rejected' THEN 'cancelled' ELSE 'pending' END AS status,r.created_at,r.reviewed_at AS updated_at,'request' AS source FROM rental_requests r LEFT JOIN user_profiles p ON p.id=r.customer_id LEFT JOIN profile_details d ON d.user_id=r.customer_id LEFT JOIN LATERAL (SELECT x.* FROM orders x WHERE x.buyer_id=r.customer_id AND x.order_type='rental' AND x.metadata->>'checkout_request_id'=r.checkout_id::text ORDER BY x.created_at DESC,x.id LIMIT 1) o ON true ORDER BY r.created_at DESC"));
  result.sort(Comparator.comparing((Map<String,Object> m)->Objects.toString(m.get("created_at"),"")).reversed());return result;
 }
}
