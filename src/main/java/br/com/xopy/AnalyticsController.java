package br.com.xopy;
import java.util.*;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
@RestController
@RequestMapping("/api")
public class AnalyticsController {
 private final JdbcTemplate db;private final Identity identity;
 public AnalyticsController(JdbcTemplate db,Identity identity){this.db=db;this.identity=identity;}
 public record Visit(UUID sessionId,String path,String source,String device){}
 @PostMapping("/analytics/visit") public Map<String,Object> visit(@RequestBody Visit v,@AuthenticationPrincipal Jwt jwt){
  if(v.sessionId()==null || v.path()==null || !v.path().startsWith("/") || v.path().length()>300 || v.path().contains("?") || !Set.of("Direto","Busca orgânica","Redes sociais","Email","Anúncios pagos","Referência").contains(Objects.toString(v.source(),"")) || !Set.of("Desktop","Mobile","Tablet").contains(Objects.toString(v.device(),"")))throw new ResponseStatusException(BAD_REQUEST,"Visita inválida");
  if(v.path().startsWith("/admin"))return Map.of("recorded",false);
  db.update("INSERT INTO analytics_visits(session_id,user_id,path,source,device) VALUES(?,?,?,?,?)",v.sessionId(),jwt==null?null:identity.id(jwt),v.path(),v.source(),v.device());return Map.of("recorded",true);
 }
 private Map<String,Object> metrics(java.sql.Timestamp from,java.sql.Timestamp to){
  var data=new LinkedHashMap<String,Object>();
  data.putAll(db.queryForMap("SELECT coalesce(sum(total_amount) FILTER(WHERE paid_at>=? AND paid_at<? AND status NOT IN ('cancelled','refunded')),0) AS revenue, count(*) FILTER(WHERE created_at>=? AND created_at<?) AS orders, coalesce(avg(total_amount) FILTER(WHERE paid_at>=? AND paid_at<? AND status NOT IN ('cancelled','refunded')),0) AS \"averageOrderValue\" FROM orders",from,to,from,to,from,to));
  data.put("users",db.queryForObject("SELECT count(*) FROM app_users WHERE deleted_at IS NULL AND created_at>=? AND created_at<?",Long.class,from,to));
  var visits=db.queryForMap("SELECT count(*) AS \"pageViews\",count(DISTINCT session_id) AS sessions FROM analytics_visits WHERE created_at>=? AND created_at<?",from,to);data.putAll(visits);
  Long converted=db.queryForObject("SELECT count(DISTINCT v.session_id) FROM analytics_visits v WHERE v.created_at>=? AND v.created_at<? AND EXISTS(SELECT 1 FROM orders o WHERE o.buyer_id=v.user_id AND o.paid_at>=v.created_at AND o.paid_at<v.created_at+interval '30 minutes' AND o.status NOT IN ('cancelled','refunded'))",Long.class,from,to);
  long sessions=((Number)visits.get("sessions")).longValue();data.put("conversionRate",sessions==0?0:100.0*converted/sessions);return data;
 }
 @GetMapping("/admin/analytics") public Map<String,Object> get(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="7") int days){
  identity.requireAdmin(jwt);if(!Set.of(7,30,90,365).contains(days))throw new ResponseStatusException(BAD_REQUEST,"Período inválido");
  ZoneId zone=ZoneId.of("America/Fortaleza");LocalDate today=LocalDate.now(zone);
  var end=java.sql.Timestamp.from(today.plusDays(1).atStartOfDay(zone).toInstant());
  var start=java.sql.Timestamp.from(today.minusDays(days-1).atStartOfDay(zone).toInstant());
  var previous=java.sql.Timestamp.from(today.minusDays(days*2L-1).atStartOfDay(zone).toInstant());
  var daily=new ArrayList<Map<String,Object>>();for(int i=6;i>=0;i--){LocalDate day=today.minusDays(i);var a=java.sql.Timestamp.from(day.atStartOfDay(zone).toInstant());var b=java.sql.Timestamp.from(day.plusDays(1).atStartOfDay(zone).toInstant());var m=db.queryForMap("SELECT coalesce(sum(total_amount) FILTER(WHERE paid_at>=? AND paid_at<? AND status NOT IN ('cancelled','refunded')),0) AS revenue,count(*) FILTER(WHERE created_at>=? AND created_at<?) AS orders FROM orders",a,b,a,b);m.put("date",day.toString());daily.add(m);}
  var top=db.queryForList("SELECT o.product_id AS id,coalesce(p.title,'Produto indisponível') AS name,sum(CASE WHEN o.metadata->>'quantity' ~ '^[0-9]+$' THEN (o.metadata->>'quantity')::numeric ELSE 1 END) AS sales,coalesce(sum(o.total_amount-coalesce(o.shipping_cost,0)),0) AS revenue FROM orders o LEFT JOIN products p ON p.id=o.product_id WHERE o.paid_at>=? AND o.paid_at<? AND o.status NOT IN ('cancelled','refunded') AND o.product_id IS NOT NULL GROUP BY o.product_id,p.title ORDER BY sales DESC,revenue DESC LIMIT 10",start,end);
  var sources=db.queryForList("SELECT source,count(*) AS sessions FROM (SELECT DISTINCT ON (session_id) session_id,source FROM analytics_visits WHERE created_at>=? AND created_at<? ORDER BY session_id,created_at,id) v GROUP BY source ORDER BY sessions DESC",start,end);
  var devices=db.queryForList("SELECT device,count(*) AS sessions FROM (SELECT DISTINCT ON (session_id) session_id,device FROM analytics_visits WHERE created_at>=? AND created_at<? ORDER BY session_id,created_at,id) v GROUP BY device ORDER BY sessions DESC",start,end);
  return Map.of("current",metrics(start,end),"previous",metrics(previous,start),"daily",daily,"topProducts",top,"sources",sources,"devices",devices,"trackingSince",Objects.toString(db.queryForObject("SELECT min(created_at) FROM analytics_visits",java.sql.Timestamp.class),""));
 }
}
