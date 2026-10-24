package br.com.xopy;

import java.util.*;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/chat/conversations/{conversationId}/proposals")
public class ServiceProposalController {
 private final JdbcTemplate db; private final Identity identity; private final ChatController chat; private final ObjectMapper json;
 public ServiceProposalController(JdbcTemplate db,Identity identity,ChatController chat,ObjectMapper json){this.db=db;this.identity=identity;this.chat=chat;this.json=json;}
 public record Input(Map<String,String> terms){}
 public record Decision(String status,String note){}
 static final List<String> KEYS=List.of("scope","labor","materials_cost","materials","supplier","location","date","deadline","payment","other");
 static final List<String> LABELS=List.of("Descrição e escopo","Mão de obra (R$)","Valor dos materiais cobrado na plataforma (R$)","Materiais necessários","Responsável pelos materiais","Local","Data de início","Prazo de execução","Forma e condições de pagamento","Outras condições");
 Map<String,Object> authorize(UUID conversationId,UUID uid,boolean provider){
  var c=chat.participant(conversationId,uid);
  String required=c.get(provider?"provider_user_id":"user_id").toString();
  if(!uid.toString().equals(required))throw new ResponseStatusException(FORBIDDEN,provider?"Somente o prestador pode enviar a proposta formal":"Somente o cliente pode responder à proposta");
  if(!"active".equals(c.get("status")))throw new ResponseStatusException(CONFLICT,"Conversa encerrada");
  ServiceCaseGuard.requireActive(db,conversationId);
  return c;
 }
 Map<String,String> validate(Map<String,String> input){
  if(input==null||!new HashSet<>(KEYS).containsAll(input.keySet()))throw new ResponseStatusException(BAD_REQUEST,"Detalhes da proposta inválidos");
  Map<String,String> terms=new LinkedHashMap<>();
  for(String key:KEYS){String value=Objects.toString(input.get(key),key.equals("materials_cost")?"0,00":"").trim();if(value.length()>(key.equals("scope")?1500:700)||(!key.equals("other")&&value.isEmpty()))throw new ResponseStatusException(BAD_REQUEST,"Preencha todos os detalhes obrigatórios da proposta");terms.put(key,value);}
  try{if(!terms.get("labor").matches("(?:\\d{1,3}(?:\\.\\d{3})*|\\d+),\\d{2}"))throw new IllegalArgumentException();BigDecimal amount=new BigDecimal(terms.get("labor").replace(".","").replace(",","."));if(amount.signum()<0||amount.compareTo(new BigDecimal("999999999.99"))>0)throw new IllegalArgumentException();BigDecimal materials=ServiceContractController.money(terms.get("materials_cost"));if(materials.signum()<0||materials.compareTo(new BigDecimal("999999999.99"))>0||!terms.get("materials_cost").matches("(?:\\d{1,3}(?:\\.\\d{3})*|\\d+),\\d{2}"))throw new IllegalArgumentException();if(amount.add(materials).signum()<=0)throw new IllegalArgumentException();LocalDate.parse(terms.get("date"));}catch(Exception e){throw new ResponseStatusException(BAD_REQUEST,"Informe valor em reais e data válidos");}
  if(!Set.of("Cliente","Prestador","Ambos, conforme combinado no chat").contains(terms.get("supplier")))throw new ResponseStatusException(BAD_REQUEST,"Defina quem fornecerá os materiais");
  return terms;
 }
 Map<String,Object> decode(Map<String,Object> row)throws Exception{
  var result=new LinkedHashMap<>(row);result.put("terms",json.readValue(row.get("terms").toString(),new TypeReference<Map<String,String>>(){}));return result;
 }
 @GetMapping
 public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID conversationId)throws Exception{
  chat.participant(conversationId,identity.id(jwt));var rows=db.queryForList("SELECT * FROM service_proposals WHERE conversation_id=? ORDER BY version",conversationId);var result=new ArrayList<Map<String,Object>>();for(var row:rows)result.add(decode(row));return result;
 }
 @PostMapping @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> create(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID conversationId,@RequestBody Input input)throws Exception{
  UUID uid=identity.id(jwt);authorize(conversationId,uid,true);var terms=validate(input.terms());
  var latest=db.queryForList("SELECT version,status FROM service_proposals WHERE conversation_id=? ORDER BY version DESC LIMIT 1",conversationId);
  int version=1;
  if(!latest.isEmpty()){var previous=latest.get(0);if(Set.of("pending","accepted").contains(previous.get("status")))throw new ResponseStatusException(CONFLICT,"A proposta está aguardando resposta ou já foi aceita");version=((Number)previous.get("version")).intValue()+1;}
  var row=db.queryForMap("INSERT INTO service_proposals(conversation_id,version,terms,created_by) VALUES(?,?,?::jsonb,?) RETURNING *",conversationId,version,json.writeValueAsString(terms),uid);
  StringBuilder content=new StringBuilder("PROPOSTA FORMAL — Versão "+version+"\n");
  for(int i=0;i<KEYS.size();i++)if(!terms.get(KEYS.get(i)).isBlank())content.append("\n").append(LABELS.get(i)).append(": ").append(terms.get(KEYS.get(i))).append("\n");
  content.append("\nAguardando decisão do cliente na área Propostas formais.");
  chat.send(jwt,conversationId,new ChatController.Text(content.toString()));
  return decode(row);
 }
 @PostMapping("/{proposalId}/view") @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> viewed(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID conversationId,@PathVariable UUID proposalId)throws Exception{
  authorize(conversationId,identity.id(jwt),false);
  var rows=db.queryForList("SELECT * FROM service_proposals WHERE id=? AND conversation_id=? FOR UPDATE",proposalId,conversationId);
  if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Proposta indisponível");
  if(!"pending".equals(rows.get(0).get("status"))||rows.get(0).get("client_viewed_at")!=null)return decode(rows.get(0));
  return decode(db.queryForMap("UPDATE service_proposals SET client_viewed_at=now() WHERE id=? AND conversation_id=? RETURNING *",proposalId,conversationId));
 }
 @PostMapping("/{proposalId}/decision") @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> decide(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID conversationId,@PathVariable UUID proposalId,@RequestBody Decision input)throws Exception{
  UUID uid=identity.id(jwt);authorize(conversationId,uid,false);
  if(input.status()==null||!Set.of("accepted","rejected","changes_requested").contains(input.status()))throw new ResponseStatusException(BAD_REQUEST,"Decisão inválida");
  String note=Objects.toString(input.note(),"").trim();if(note.length()>2000||(input.status().equals("changes_requested")&&note.isEmpty()))throw new ResponseStatusException(BAD_REQUEST,"Descreva as alterações solicitadas (até 2000 caracteres)");
  var rows=db.queryForList("SELECT * FROM service_proposals WHERE id=? AND conversation_id=? FOR UPDATE",proposalId,conversationId);
  if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Proposta indisponível");
  if(!"pending".equals(rows.get(0).get("status")))throw new ResponseStatusException(CONFLICT,"Esta proposta já recebeu uma resposta. Atualize a conversa.");
  var row=db.queryForMap("UPDATE service_proposals SET status=?,decision_note=?,decided_by=?,decided_at=now() WHERE id=? AND conversation_id=? RETURNING *",input.status(),note,uid,proposalId,conversationId);
  String label=switch(input.status()){case "accepted"->"aceita";case "rejected"->"recusada";default->"com alterações solicitadas";};
  chat.send(jwt,conversationId,new ChatController.Text("Proposta formal — Versão "+rows.get(0).get("version")+": "+label+" pelo cliente."+(note.isEmpty()?"":"\n"+note)));
  return decode(row);
 }
}
