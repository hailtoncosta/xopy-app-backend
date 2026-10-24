package br.com.xopy;
import java.util.*;
import java.math.BigDecimal;
import java.nio.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceContractTest {
 @TempDir Path directory;
 JdbcTemplate db;Identity identity;ChatController chat;ServiceContractController api;Jwt jwt;UUID uid,id,cid,pid,other,doc;Map<String,Object> contract;
 @BeforeEach void setup()throws Exception{db=mock(JdbcTemplate.class);identity=mock(Identity.class);chat=mock(ChatController.class);api=new ServiceContractController(db,identity,chat,new ObjectMapper(),directory.toString());uid=UUID.randomUUID();id=UUID.randomUUID();cid=UUID.randomUUID();pid=UUID.randomUUID();other=UUID.randomUUID();doc=UUID.randomUUID();jwt=Jwt.withTokenValue("test").header("alg","HS256").subject(uid.toString()).build();when(identity.id(jwt)).thenReturn(uid);contract=new HashMap<>(Map.of("id",id,"customer_id",uid,"provider_user_id",other,"conversation_id",cid,"proposal_id",pid,"document_id",doc,"amount",new BigDecimal("1525.50"),"status","awaiting_acceptance"));when(db.queryForList(contains("SELECT * FROM service_contracts"),any(Object[].class))).thenReturn(List.of(contract));}
 void proposal(String status)throws Exception{when(db.queryForList(contains("SELECT * FROM service_proposals"),eq(pid))).thenReturn(List.of(Map.of("id",pid,"conversation_id",cid,"status",status,"terms",new ObjectMapper().writeValueAsString(Map.of("labor","1.500,00","materials_cost","25,50")))));when(chat.participant(cid,uid)).thenReturn(Map.of("user_id",other,"provider_user_id",uid));}
 MockMultipartFile pdf(){return new MockMultipartFile("contract","contrato.pdf","application/pdf","%PDF-1.7 teste".getBytes());}
 @Test void calculatesAcceptedAmounts(){assertEquals(new BigDecimal("1525.50"),ServiceContractController.amount(Map.of("labor","1.500,00","materials_cost","25,50")));assertEquals(new BigDecimal("1500.00"),ServiceContractController.amount(Map.of("labor","1.500,00")));}
 @Test void rejectsZeroAndNegativeAmounts(){for(var input:List.of(Map.of("labor","0,00"),Map.of("labor","-1,00"),Map.of("labor","10,00","materials_cost","-1,00")))assertThrows(ResponseStatusException.class,()->ServiceContractController.amount(input));}
 @Test void outsiderCannotDownload(){when(db.queryForList(contains("SELECT * FROM service_contracts"),any(Object[].class))).thenReturn(List.of());assertThrows(ResponseStatusException.class,()->api.download(jwt,id));}
 @Test void customerCannotAttachContract()throws Exception{proposal("accepted");when(chat.participant(cid,uid)).thenReturn(Map.of("user_id",uid,"provider_user_id",other));assertThrows(ResponseStatusException.class,()->api.upload(jwt,pid,pdf()));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void proposalMustBeAccepted()throws Exception{proposal("pending");assertThrows(ResponseStatusException.class,()->api.upload(jwt,pid,pdf()));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void immutableContractCannotBeReplaced()throws Exception{proposal("accepted");when(db.queryForList(contains("SELECT id FROM service_contracts"),eq(pid))).thenReturn(List.of(Map.of("id",id)));assertThrows(ResponseStatusException.class,()->api.upload(jwt,pid,pdf()));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void rejectNonPdf()throws Exception{proposal("accepted");assertThrows(ResponseStatusException.class,()->api.upload(jwt,pid,new MockMultipartFile("contract","x.pdf","application/pdf","not a pdf".getBytes())));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void providerUploadsPrivatePdf()throws Exception{proposal("accepted");var result=api.upload(jwt,pid,pdf());assertEquals("awaiting_acceptance",result.get("status"));try(var files=Files.list(directory.resolve("service-private"))){assertEquals(1,files.count());}verify(db).update(contains("INSERT INTO service_contracts"),any(),eq(pid),eq(cid),eq(other),eq(uid),any(),eq("contrato.pdf"),eq(new BigDecimal("1525.50")));}
 @Test void paymentBlockedUntilContractConfirmed(){assertThrows(ResponseStatusException.class,()->api.checkout(jwt,id));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void providerCannotPay(){contract.put("customer_id",other);contract.put("provider_user_id",uid);contract.put("accepted_at",new Date());assertThrows(ResponseStatusException.class,()->api.checkout(jwt,id));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void explicitAcceptanceAndDocumentRequired(){assertThrows(ResponseStatusException.class,()->api.accept(jwt,id,new ServiceContractController.Acceptance(false)));assertThrows(ResponseStatusException.class,()->api.accept(jwt,id,new ServiceContractController.Acceptance(true)));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void customerConfirmsExistingContract()throws Exception{Files.writeString(directory.resolve("service-private").resolve(doc.toString()),"%PDF-1.7");api.accept(jwt,id,new ServiceContractController.Acceptance(true));verify(db).update(contains("accepted_at=now()"),eq(id));verify(chat).send(eq(jwt),eq(cid),any());}
 @Test void checkoutIsReused()throws Exception{UUID checkout=UUID.randomUUID();contract.put("accepted_at",new Date());contract.put("checkout_id",checkout);assertEquals(checkout,api.checkout(jwt,id).get("checkoutId"));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void checkoutUsesFrozenAmountAndCorrectProvider()throws Exception{contract.put("accepted_at",new Date());contract.put("status","awaiting_payment");var result=api.checkout(jwt,id);assertNotNull(result.get("checkoutId"));verify(db).update(contains("INSERT INTO orders"),any(),eq(uid),eq(other),eq(new BigDecimal("1525.50")),eq(new BigDecimal("1525.50")),contains("service_contract_id"));}

 @Test void genericOrderCancellationCannotBypassServiceContract(){
  when(db.queryForList(contains("select metadata,seller_id from orders"),eq(id),eq(uid))).thenReturn(List.of(Map.of("metadata","{\"service_contract_id\":\""+id+"\"}")));
  var checkout=new CheckoutController(db,identity,new ObjectMapper());
  assertThrows(ResponseStatusException.class,()->checkout.cancel(id,jwt));verify(db,never()).update(anyString(),any(Object[].class));
 }

 @Test void blockedCaseCannotConfirmContractOrCreateCheckout(){
  contract.put("accepted_at",new Date());
  for(String state:List.of("disputed","cancelled")){
   when(db.queryForList(contains("SELECT service_case_status"),eq(cid))).thenReturn(List.of(Map.of("service_case_status",state)));
   assertThrows(ResponseStatusException.class,()->api.accept(jwt,id,new ServiceContractController.Acceptance(true)));
   assertThrows(ResponseStatusException.class,()->api.checkout(jwt,id));
  }
  verify(db,never()).update(anyString(),any(Object[].class));
 }
}
