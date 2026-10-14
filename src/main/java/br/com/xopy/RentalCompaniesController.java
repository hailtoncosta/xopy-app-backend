package br.com.xopy;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/rental-companies")
public class RentalCompaniesController {
    private final JdbcTemplate db;
    private final Identity identity;
    public RentalCompaniesController(JdbcTemplate db, Identity identity) { this.db=db;this.identity=identity; }

    @GetMapping
    public Map<String,Object> list(@AuthenticationPrincipal Jwt jwt) {
        identity.requireAdmin(jwt);
        var rows=db.queryForList("""
            SELECT u.id,
              coalesce(nullif(d.details->>'companyName',''),nullif(p.seller_info->>'businessName',''),u.full_name,'') AS name,
              u.avatar_url,
              coalesce(nullif(d.details->>'address',''),p.seller_info->>'businessAddress','') AS address,
              coalesce(nullif(d.details->>'city',''),p.seller_info->>'businessCity','') AS city,
              coalesce(nullif(d.details->>'state',''),p.seller_info->>'businessState','') AS state,
              coalesce(nullif(d.details->>'phone',''),nullif(p.seller_info->>'businessPhone',''),u.phone,'') AS phone,
              coalesce(nullif(p.seller_info->>'businessEmail',''),u.email,'') AS email,
              coalesce(d.details->>'companyDescription','') AS description, NULL::numeric AS rating,
              (SELECT count(*) FROM rental_requests r WHERE r.company_id=u.id AND r.status='approved') AS total_rentals,
              u.created_at,u.updated_at
            FROM user_profiles u
            JOIN app_users a ON a.id=u.id AND a.deleted_at IS NULL
            LEFT JOIN profile_details d ON d.user_id=u.id
            LEFT JOIN LATERAL (
              SELECT x.seller_info FROM products x WHERE x.user_id=u.id AND x.seller_type='business'
              AND (x.product_type='rental' OR x.description LIKE '[LOCAÇÃO]%')
              ORDER BY x.updated_at DESC NULLS LAST,x.id LIMIT 1
            ) p ON true
            WHERE u.user_type='rental_company' OR d.details->>'type'='business' OR p.seller_info IS NOT NULL
            ORDER BY lower(coalesce(nullif(d.details->>'companyName',''),nullif(p.seller_info->>'businessName',''),u.full_name,'')),u.id
            """);
        return Map.of("data",rows,"count",rows.size());
    }
}
