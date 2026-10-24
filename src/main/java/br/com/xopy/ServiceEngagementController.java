package br.com.xopy;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/service-engagements")
public class ServiceEngagementController {
 private final JdbcTemplate db;private final Identity identity;private final ChatController chat;
 public ServiceEngagementController(JdbcTemplate db,Identity identity,ChatController chat){this.db=db;this.identity=identity;this.chat=chat;}
 public record Action(String action,String reason){}
 @GetMapping
 public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt){UUID uid=identity.id(jwt);return db.queryForList("SELECT * FROM service_engagement_status WHERE customer_id=? OR provider_user_id=? ORDER BY updated_at DESC,conversation_id",uid,uid);}
 @GetMapping("/{id}")
 public Map<String,Object> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){chat.participant(id,identity.id(jwt));return view(id);}
 Map<String,Object> view(UUID id){var rows=db.queryForList("SELECT * FROM service_engagement_status WHERE conversation_id=?",id);if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Contratação indisponível");var result=new LinkedHashMap<>(rows.get(0));result.put("events",db.queryForList("SELECT e.id,e.action,e.reason,e.created_at,p.full_name AS actor_name FROM service_case_events e LEFT JOIN user_profiles p ON p.id=e.actor_id WHERE e.conversation_id=? ORDER BY e.created_at,e.id",id));return result;}
 String reason(Action input){String reason=Objects.toString(input.reason(),"").trim();if(reason.isEmpty()||reason.length()>2000)throw new ResponseStatusException(BAD_REQUEST,"Informe o motivo com até 2000 caracteres");return reason;}
 @PostMapping("/{id}/case") @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> act(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Action input)throws Exception{
  UUID uid=identity.id(jwt);chat.participant(id,uid);String reason=reason(input);
  if(input.action()==null||!Set.of("cancel","dispute").contains(input.action()))throw new ResponseStatusException(BAD_REQUEST,"Ação inválida");
  ServiceCaseGuard.requireActive(db,id);
  var contracts=db.queryForList("SELECT accepted_at,checkout_id,execution_status,status FROM service_contracts WHERE conversation_id=?",id);
  if(input.action().equals("cancel")){
   if(contracts.stream().anyMatch(c->c.get("accepted_at")!=null||c.get("checkout_id")!=null))throw new ResponseStatusException(CONFLICT,"Contrato formalizado ou pagamento iniciado: abra uma disputa para análise do Xopy");
  }else if(contracts.isEmpty())throw new ResponseStatusException(CONFLICT,"Antes da formalização, utilize o cancelamento ou negocie alterações no chat");
  setCase(id,uid,input.action(),reason,input.action().equals("cancel")?"cancelled":"disputed");
  chat.send(jwt,id,new ChatController.Text((input.action().equals("cancel")?"Negociação/contratação cancelada.":"Disputa aberta para análise do Xopy. As novas etapas da contratação estão suspensas.")+"\nMotivo: "+reason));return view(id);
 }
 private void setCase(UUID id,UUID uid,String action,String reason,String state){db.update("UPDATE conversations SET service_case_status=?,service_case_reason=?,service_case_actor=?,service_case_changed_at=now(),updated_at=now() WHERE id=?",state,reason,uid,id);db.update("INSERT INTO service_case_events(conversation_id,actor_id,action,reason) VALUES(?,?,?,?)",id,uid,action,reason);}
 @GetMapping("/admin/cases")
 public List<Map<String,Object>> cases(@AuthenticationPrincipal Jwt jwt){identity.requireAdmin(jwt);return db.queryForList("SELECT * FROM service_engagement_status WHERE service_case_status='disputed' ORDER BY updated_at DESC");}
 @PostMapping("/{id}/resolution") @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> resolve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Action input){identity.requireAdmin(jwt);UUID uid=identity.id(jwt);String reason=reason(input);if(input.action()==null||!Set.of("resume","resolve_cancel").contains(input.action()))throw new ResponseStatusException(BAD_REQUEST,"Resolução inválida");if(!ServiceCaseGuard.state(db,id,true).equals("disputed"))throw new ResponseStatusException(CONFLICT,"Não há disputa aberta para resolver");setCase(id,uid,input.action(),reason,input.action().equals("resume")?"active":"cancelled");db.update("INSERT INTO notifications(user_id,title,message,type,data) SELECT recipient,'Resolução da disputa',?,'new_message',jsonb_build_object('conversation_id',conversation_id) FROM service_engagement_status CROSS JOIN LATERAL (VALUES(customer_id),(provider_user_id)) recipients(recipient) WHERE conversation_id=?",(input.action().equals("resume")?"Contratação retomada. ":"Contratação cancelada. ")+reason,id);return view(id);}
}
