package br.com.xopy;

import java.util.*;
import java.math.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/service-contracts")
public class ServiceContractController {
 private final JdbcTemplate db;private final Identity identity;private final ChatController chat;private final ObjectMapper json;private final Path root;
 public ServiceContractController(JdbcTemplate db,Identity identity,ChatController chat,ObjectMapper json,@Value("${app.upload-dir}") String directory)throws Exception{this.db=db;this.identity=identity;this.chat=chat;this.json=json;root=Path.of(directory).toAbsolutePath().normalize().resolve("service-private");Files.createDirectories(root);}
 Map<String,Object> authorized(UUID id,UUID uid,boolean lock){
  String sql="SELECT * FROM service_contracts WHERE id=? AND (customer_id=? OR provider_user_id=?)";
  var rows=db.queryForList(sql,id,uid,uid);if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Contrato indisponível");
  UUID cid=(UUID)rows.get(0).get("conversation_id");
  if(lock){ServiceCaseGuard.requireActive(db,cid);rows=db.queryForList(sql+" FOR UPDATE",id,uid,uid);if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Contrato indisponível");}
  var result=new LinkedHashMap<>(rows.get(0));result.put("service_case_status",ServiceCaseGuard.state(db,cid,false));return result;
 }
 static BigDecimal amount(Map<String,String> terms){try{BigDecimal labor=money(terms.get("labor")),materials=money(terms.getOrDefault("materials_cost","0,00"));BigDecimal total=labor.add(materials).setScale(2,RoundingMode.UNNECESSARY);if(labor.signum()<0||materials.signum()<0||total.signum()<=0)throw new IllegalArgumentException();return total;}catch(Exception e){throw new ResponseStatusException(CONFLICT,"A proposta precisa definir um valor total positivo para pagamento");}}
 static BigDecimal money(String value){return new BigDecimal(value.replace(".","").replace(",","."));}
 @GetMapping("/proposal/{proposalId}")
 public Map<String,Object> find(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID proposalId){UUID uid=identity.id(jwt);var rows=db.queryForList("SELECT c.*,v.service_case_status,s.status AS payment_status,s.approved_payment_id,s.amount AS payment_amount FROM service_contracts c JOIN conversations v ON v.id=c.conversation_id LEFT JOIN payment_sessions s ON s.request_id=c.checkout_id AND s.buyer_id=c.customer_id AND s.seller_id=c.provider_user_id WHERE c.proposal_id=? AND (c.customer_id=? OR c.provider_user_id=?)",proposalId,uid,uid);return rows.isEmpty()?Map.of("available",false):rows.get(0);}
 @PostMapping(value="/proposal/{proposalId}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> upload(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID proposalId,@RequestPart MultipartFile contract)throws Exception{
  UUID uid=identity.id(jwt);var proposals=db.queryForList("SELECT * FROM service_proposals WHERE id=?",proposalId);if(proposals.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Proposta indisponível");var proposal=proposals.get(0);UUID cid=(UUID)proposal.get("conversation_id");var c=chat.participant(cid,uid);
  if(!uid.equals(c.get("provider_user_id")))throw new ResponseStatusException(FORBIDDEN,"Somente o prestador pode anexar o contrato");
  if(!"accepted".equals(proposal.get("status")))throw new ResponseStatusException(CONFLICT,"O cliente precisa aceitar a proposta antes do contrato");
  ServiceCaseGuard.requireActive(db,cid);
  if(!db.queryForList("SELECT id FROM service_contracts WHERE proposal_id=?",proposalId).isEmpty())throw new ResponseStatusException(CONFLICT,"O contrato desta proposta já foi registrado");
  if(contract.getSize()>10*1024*1024||contract.isEmpty())throw new ResponseStatusException(BAD_REQUEST,"Envie um PDF de até 10 MB");byte[] bytes=contract.getBytes();if(bytes.length<5||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE,"Envie o contrato em PDF");
  Map<String,String> terms=json.readValue(proposal.get("terms").toString(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});BigDecimal total=amount(terms);
  UUID id=UUID.randomUUID(),doc=UUID.randomUUID();String filename=Objects.toString(contract.getOriginalFilename(),"contrato-servico.pdf").replaceAll("[\\r\\n\\\\/]","_");if(filename.length()>200)filename=filename.substring(filename.length()-200);
  Path file=root.resolve(doc.toString());Files.write(file,bytes,StandardOpenOption.CREATE_NEW);
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(file);}catch(Exception ignored){}}});
  try{db.update("INSERT INTO service_contracts(id,proposal_id,conversation_id,customer_id,provider_user_id,document_id,filename,amount) VALUES(?,?,?,?,?,?,?,?)",id,proposalId,cid,c.get("user_id"),uid,doc,filename,total);chat.send(jwt,cid,new ChatController.Text("Contrato de serviço disponível para leitura e confirmação na aba Propostas formais. Valor total: R$ "+total.toPlainString().replace('.',',')+". O pagamento será liberado após a confirmação do cliente."));return Map.of("id",id,"status","awaiting_acceptance");}catch(Exception e){Files.deleteIfExists(file);throw e;}
 }
 @GetMapping("/{id}/document")
 public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){var c=authorized(id,identity.id(jwt),false);Path file=root.resolve(c.get("document_id").toString());if(!Files.isRegularFile(file))throw new ResponseStatusException(NOT_FOUND,"Contrato não encontrado");return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition",ContentDisposition.attachment().filename(c.get("filename").toString(),StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(file));}
 public record Acceptance(boolean accepted){}
 @PostMapping("/{id}/accept") @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> accept(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Acceptance input)throws Exception{UUID uid=identity.id(jwt);var c=authorized(id,uid,true);if(!uid.equals(c.get("customer_id")))throw new ResponseStatusException(FORBIDDEN,"Somente o cliente pode confirmar o contrato");if(!input.accepted())throw new ResponseStatusException(BAD_REQUEST,"Confirme a leitura e concordância com o contrato");if(c.get("accepted_at")!=null)return Map.of("ok",true);if(!Files.isRegularFile(root.resolve(c.get("document_id").toString())))throw new ResponseStatusException(CONFLICT,"Contrato indisponível para leitura");db.update("UPDATE service_contracts SET accepted_at=now(),status='awaiting_payment',updated_at=now() WHERE id=?",id);chat.send(jwt,(UUID)c.get("conversation_id"),new ChatController.Text("O cliente confirmou a leitura e concordância com o contrato. Pagamento liberado pela plataforma."));return Map.of("ok",true);}
 @PostMapping("/{id}/checkout") @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> checkout(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id)throws Exception{UUID uid=identity.id(jwt);var c=authorized(id,uid,true);if(!uid.equals(c.get("customer_id")))throw new ResponseStatusException(FORBIDDEN,"Somente o cliente pode pagar");if(c.get("accepted_at")==null)throw new ResponseStatusException(CONFLICT,"Confirme o contrato antes do pagamento");if(c.get("checkout_id")!=null)return Map.of("checkoutId",c.get("checkout_id"));if(!"awaiting_payment".equals(c.get("status")))throw new ResponseStatusException(CONFLICT,"Contrato indisponível para pagamento");BigDecimal total=new BigDecimal(c.get("amount").toString());if(total.signum()<=0)throw new ResponseStatusException(CONFLICT,"Valor do contrato inválido");UUID checkout=UUID.randomUUID(),order=UUID.randomUUID();String metadata=json.writeValueAsString(Map.of("checkout_request_id",checkout,"service_contract_id",id,"service_proposal_id",c.get("proposal_id"),"title","Contratação de serviço Xopy"));db.update("INSERT INTO checkout_requests(buyer_id,request_id,fingerprint,result) VALUES(?,?,?,?::jsonb)",uid,checkout,"service:"+id,json.writeValueAsString(Map.of("orderIds",List.of(order),"total",total,"status","pending")));db.update("INSERT INTO orders(id,buyer_id,seller_id,order_type,status,payment_method,total_amount,shipping_cost,marketplace_fee,seller_amount,metadata) VALUES(?,?,?,'service','pending','pending',?,0,0,?,?::jsonb)",order,uid,c.get("provider_user_id"),total,total,metadata);db.update("UPDATE service_contracts SET checkout_id=?,updated_at=now() WHERE id=?",checkout,id);return Map.of("checkoutId",checkout);}
}
