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
@RequestMapping("/api/service-contracts")
public class ServiceExecutionController {
 private final JdbcTemplate db;private final Identity identity;private final ServiceContractController contracts;private final ChatController chat;
 public ServiceExecutionController(JdbcTemplate db,Identity identity,ServiceContractController contracts,ChatController chat){this.db=db;this.identity=identity;this.contracts=contracts;this.chat=chat;}
 public record Update(String action,String note,boolean confirmed){}
 public record Review(Integer rating,String comment){}
 @GetMapping
 public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt){UUID uid=identity.id(jwt);return db.queryForList("SELECT c.id,c.conversation_id,c.proposal_id,c.status,c.execution_status,c.amount,c.created_at,c.updated_at,c.customer_id,c.provider_user_id,p.terms->>'scope' AS scope,customer.full_name AS customer_name,provider.full_name AS provider_name FROM service_contracts c JOIN service_proposals p ON p.id=c.proposal_id LEFT JOIN user_profiles customer ON customer.id=c.customer_id LEFT JOIN user_profiles provider ON provider.id=c.provider_user_id WHERE c.customer_id=? OR c.provider_user_id=? ORDER BY c.updated_at DESC,c.id",uid,uid);}
 @GetMapping("/{id}/execution")
 public Map<String,Object> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){var c=contracts.authorized(id,identity.id(jwt),false);var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","status","execution_status","started_at","reported_completed_at","delivery_confirmed_at","service_case_status"))result.put(key,c.get(key));result.put("events",db.queryForList("SELECT e.id,e.event_type,e.note,e.created_at,e.actor_id,p.full_name AS actor_name FROM service_execution_events e LEFT JOIN user_profiles p ON p.id=e.actor_id WHERE e.contract_id=? ORDER BY e.created_at,e.id",id));var reviews=db.queryForList("SELECT id,rating,comment,created_at FROM professional_reviews WHERE contract_id=?",id);result.put("review",reviews.isEmpty()?null:reviews.get(0));return result;}
 @PostMapping("/{id}/execution") @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> update(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Update input)throws Exception{
  UUID uid=identity.id(jwt);var c=contracts.authorized(id,uid,true);
  if(input.action()==null||!Set.of("start","progress","complete","confirm_delivery","request_adjustments").contains(input.action()))throw new ResponseStatusException(BAD_REQUEST,"Ação inválida");
  boolean customerAction=Set.of("confirm_delivery","request_adjustments").contains(input.action());
  if(!uid.equals(c.get(customerAction?"customer_id":"provider_user_id")))throw new ResponseStatusException(FORBIDDEN,customerAction?"Somente o cliente pode confirmar a entrega ou solicitar ajustes":"Somente o prestador pode registrar a execução");
  String note=Objects.toString(input.note(),"").trim();if(note.length()>2000||Set.of("progress","request_adjustments").contains(input.action())&&note.isBlank())throw new ResponseStatusException(BAD_REQUEST,"Descreva a atualização ou os ajustes necessários (até 2000 caracteres)");
  if(c.get("accepted_at")==null||!"paid".equals(c.get("status")))throw new ResponseStatusException(CONFLICT,"A execução requer contrato confirmado e pagamento aprovado");
  String state=Objects.toString(c.get("execution_status"),"scheduled"),next,label;
  switch(input.action()){
   case "start" -> {requireState(state,"scheduled");next="in_progress";label="O prestador iniciou o serviço conforme o contrato.";db.update("UPDATE service_contracts SET execution_status='in_progress',started_at=now(),updated_at=now() WHERE id=?",id);}
   case "progress" -> {requireState(state,"in_progress");next=state;label="Atualização do andamento pelo prestador.";db.update("UPDATE service_contracts SET updated_at=now() WHERE id=?",id);}
   case "complete" -> {requireState(state,"in_progress");next="awaiting_confirmation";label="O prestador informou a conclusão do serviço. Aguardando confirmação de entrega pelo cliente.";db.update("UPDATE service_contracts SET execution_status='awaiting_confirmation',reported_completed_at=now(),updated_at=now() WHERE id=?",id);}
   case "request_adjustments" -> {requireState(state,"awaiting_confirmation");next="in_progress";label="O cliente solicitou ajustes na entrega. O serviço voltou para execução.";db.update("UPDATE service_contracts SET execution_status='in_progress',updated_at=now() WHERE id=?",id);}
   case "confirm_delivery" -> {requireState(state,"awaiting_confirmation");if(!input.confirmed())throw new ResponseStatusException(BAD_REQUEST,"Confirme que recebeu o serviço conforme acordado");next="completed";label="O cliente confirmou a entrega do serviço. Contratação concluída, avaliação disponível.";db.update("UPDATE service_contracts SET execution_status='completed',delivery_confirmed_at=now(),updated_at=now() WHERE id=?",id);}
   default -> throw new ResponseStatusException(BAD_REQUEST,"Ação inválida");
  }
  event(id,uid,input.action(),note);chat.send(jwt,(UUID)c.get("conversation_id"),new ChatController.Text(label+(note.isEmpty()?"":"\n"+note)));return Map.of("execution_status",next);
 }
 private void requireState(String current,String required){if(!required.equals(current))throw new ResponseStatusException(CONFLICT,"Esta ação não está disponível no estágio atual. Atualize a contratação.");}
 private void event(UUID id,UUID uid,String action,String note){db.update("INSERT INTO service_execution_events(contract_id,actor_id,event_type,note) VALUES(?,?,?,?)",id,uid,action,note);}
 @PostMapping("/{id}/review") @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> review(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Review input)throws Exception{
  UUID uid=identity.id(jwt);var c=contracts.authorized(id,uid,true);
  if(!uid.equals(c.get("customer_id")))throw new ResponseStatusException(FORBIDDEN,"Somente o cliente desta contratação pode avaliar o profissional");
  if(!"completed".equals(c.get("execution_status"))||c.get("delivery_confirmed_at")==null)throw new ResponseStatusException(CONFLICT,"Confirme a entrega do serviço antes de avaliar");
  String comment=Objects.toString(input.comment(),"").trim();if(input.rating()==null||input.rating()<1||input.rating()>5||comment.length()>5000)throw new ResponseStatusException(BAD_REQUEST,"Informe nota de 1 a 5 e comentário de até 5000 caracteres");
  if(!db.queryForList("SELECT id FROM professional_reviews WHERE contract_id=?",id).isEmpty())throw new ResponseStatusException(CONFLICT,"Você já avaliou esta contratação");
  UUID reviewId=UUID.randomUUID();db.update("INSERT INTO professional_reviews(id,contract_id,provider_user_id,reviewer_user_id,rating,comment) VALUES(?,?,?,?,?,?)",reviewId,id,c.get("provider_user_id"),uid,input.rating(),comment);event(id,uid,"review","Avaliação registrada: "+input.rating()+" / 5");db.update("UPDATE service_contracts SET updated_at=now() WHERE id=?",id);chat.send(jwt,(UUID)c.get("conversation_id"),new ChatController.Text("O cliente avaliou o serviço com "+input.rating()+" / 5 estrelas."+(comment.isEmpty()?"":"\n"+comment)));return Map.of("id",reviewId,"rating",input.rating(),"comment",comment);
 }
}
