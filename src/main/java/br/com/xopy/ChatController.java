package br.com.xopy;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
 private final JdbcTemplate db; private final Identity identity; private final ObjectMapper json;
 public ChatController(JdbcTemplate db,Identity identity,ObjectMapper json){this.db=db;this.identity=identity;this.json=json;}
 public record Start(UUID providerId){}
 public record Text(String content){}
 Map<String,Object> participant(UUID id,UUID uid){
  var rows=db.queryForList("SELECT c.*,s.user_id AS provider_user_id FROM conversations c JOIN service_providers s ON s.id=c.service_provider_id WHERE c.id=? AND (c.user_id=? OR s.user_id=?)",id,uid,uid);
  if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Conversa indisponível");
  return rows.get(0);
 }
 @PostMapping("/conversations") @Transactional
 public Map<String,Object> start(@AuthenticationPrincipal Jwt jwt,@RequestBody Start input)throws Exception{
  UUID uid=identity.id(jwt);
  if(input.providerId()==null)throw new ResponseStatusException(BAD_REQUEST,"Informe o prestador");
  var providers=db.queryForList("SELECT s.user_id FROM service_providers s JOIN app_users a ON a.id=s.user_id AND a.deleted_at IS NULL WHERE s.id=? AND s.status='approved' FOR UPDATE OF s",input.providerId());
  if(providers.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Prestador indisponível");
  UUID recipient=UUID.fromString(providers.get(0).get("user_id").toString());
  if(uid.equals(recipient))throw new ResponseStatusException(BAD_REQUEST,"Você não pode solicitar serviços para si mesmo");
  var existing=db.queryForList("SELECT * FROM conversations WHERE user_id=? AND service_provider_id=? AND status='active' AND service_case_status<>'cancelled' ORDER BY created_at,id LIMIT 1",uid,input.providerId());
  if(!existing.isEmpty())return existing.get(0);
  UUID id=UUID.randomUUID();
  var row=db.queryForMap("INSERT INTO conversations(id,user_id,service_provider_id,last_message_at) VALUES(?,?,?,now()) RETURNING *",id,uid,input.providerId());
  notify(recipient,id,"conversation_started","Nova solicitação de serviços","Um cliente abriu uma conversa para negociar um serviço.");
  return row;
 }
 @GetMapping("/conversations")
 public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt){
  UUID uid=identity.id(jwt);
  var rows=db.queryForList("SELECT c.*,p.full_name AS provider_name,p.avatar_url AS provider_avatar,s.category,u.full_name AS customer_name,u.avatar_url AS customer_avatar,(SELECT content FROM messages m WHERE m.conversation_id=c.id ORDER BY created_at DESC,id DESC LIMIT 1) AS last_content,(SELECT count(*) FROM messages m WHERE m.conversation_id=c.id AND m.sender_id<>? AND m.is_read=false) AS unread_count FROM conversations c JOIN service_providers s ON s.id=c.service_provider_id LEFT JOIN user_profiles p ON p.id=s.user_id LEFT JOIN user_profiles u ON u.id=c.user_id WHERE (c.user_id=? OR s.user_id=?) AND c.status='active' ORDER BY c.last_message_at DESC NULLS LAST,c.id",uid,uid,uid);
  for(var row:rows){
   var provider=new HashMap<String,Object>();provider.put("id",row.get("service_provider_id"));provider.put("name",row.get("provider_name"));provider.put("avatar",row.get("provider_avatar"));provider.put("category",row.get("category"));row.put("service_provider",provider);
   var customer=new HashMap<String,Object>();customer.put("id",row.get("user_id"));customer.put("name",row.get("customer_name"));customer.put("avatar",row.get("customer_avatar"));row.put("user",customer);
   if(row.get("last_content")!=null)row.put("last_message",Map.of("content",row.get("last_content"),"created_at",row.get("last_message_at")));
  }return rows;
 }
 @GetMapping("/conversations/{id}/messages")
 public List<Map<String,Object>> messages(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){participant(id,identity.id(jwt));return db.queryForList("SELECT * FROM messages WHERE conversation_id=? ORDER BY created_at,id",id);}
 @PostMapping("/conversations/{id}/messages") @Transactional
 public Map<String,Object> send(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Text input)throws Exception{
  UUID uid=identity.id(jwt);var conversation=participant(id,uid);
  if(!"active".equals(conversation.get("status")))throw new ResponseStatusException(CONFLICT,"Conversa encerrada");
  String content=Objects.toString(input.content(),"").trim();
  if(content.isEmpty()||content.length()>8000)throw new ResponseStatusException(BAD_REQUEST,"A mensagem deve conter de 1 a 8000 caracteres");
  boolean customer=uid.toString().equals(conversation.get("user_id").toString());
  var message=db.queryForMap("INSERT INTO messages(conversation_id,sender_id,sender_type,content) VALUES(?,?,?,?) RETURNING *",id,uid,customer?"user":"service_provider",content);
  db.update("UPDATE conversations SET last_message_at=now(),updated_at=now() WHERE id=?",id);
  UUID recipient=UUID.fromString(conversation.get(customer?"provider_user_id":"user_id").toString());
  notify(recipient,id,"new_message","Nova mensagem",content.substring(0,Math.min(content.length(),180)));
  return message;
 }
 @PostMapping("/conversations/{id}/read") @Transactional
 public Map<String,Object> read(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){
  UUID uid=identity.id(jwt);participant(id,uid);
  db.update("UPDATE messages SET is_read=true WHERE conversation_id=? AND sender_id<>?",id,uid);
  db.update("UPDATE notifications SET is_read=true WHERE user_id=? AND data->>'conversation_id'=?",uid,id.toString());
  return Map.of("ok",true);
 }
 private void notify(UUID recipient,UUID conversation,String type,String title,String text)throws Exception{
  db.update("INSERT INTO notifications(user_id,title,message,type,data) VALUES(?,?,?,?,?::jsonb)",recipient,title,text,type,json.writeValueAsString(Map.of("conversation_id",conversation)));
 }
}
