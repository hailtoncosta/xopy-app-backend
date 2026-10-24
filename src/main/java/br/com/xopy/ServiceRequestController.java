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
@RequestMapping("/api/service-requests")
public class ServiceRequestController {
 private final JdbcTemplate db; private final Identity identity; private final ObjectMapper json;
 public ServiceRequestController(JdbcTemplate db,Identity identity,ObjectMapper json){this.db=db;this.identity=identity;this.json=json;}
 public record Input(UUID providerId,String message) {}
 @PostMapping
 @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> create(@AuthenticationPrincipal Jwt jwt,@RequestBody Input input) throws Exception {
  UUID uid=identity.id(jwt);
  String message=Objects.toString(input.message(),"").trim();
  if(input.providerId()==null || message.length()<10 || message.length()>2000)throw new ResponseStatusException(BAD_REQUEST,"Descreva o serviço com 10 a 2000 caracteres");
  var rows=db.queryForList("SELECT s.user_id FROM service_providers s JOIN app_users a ON a.id=s.user_id AND a.deleted_at IS NULL WHERE s.id=? AND s.status='approved' FOR UPDATE OF s",input.providerId());
  if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Prestador indisponível");
  UUID recipient=UUID.fromString(rows.get(0).get("user_id").toString());
  if(uid.equals(recipient))throw new ResponseStatusException(BAD_REQUEST,"Você não pode solicitar serviços para si mesmo");
  var customer=db.queryForMap("SELECT full_name,phone,email FROM user_profiles WHERE id=?",uid);
  String name=Objects.toString(customer.get("full_name"),"Cliente");
  String text=name+" solicitou um serviço:\n"+message+"\nTelefone: "+Objects.toString(customer.get("phone"),"Não informado")+"\nE-mail: "+Objects.toString(customer.get("email"),"Não informado");
  UUID requestId=UUID.randomUUID();
  var data=Map.of("request_id",requestId,"customer_id",uid,"provider_id",input.providerId(),"description",message);
  db.update("INSERT INTO notifications(id,user_id,title,message,type,data) VALUES(?,?,?,?,?,?::jsonb)",requestId,recipient,"Nova solicitação de serviços",text,"service_request",json.writeValueAsString(data));
  return Map.of("id",requestId,"sent",true);
 }
}
