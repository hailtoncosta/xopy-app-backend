package br.com.xopy;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceProposalTest {
 JdbcTemplate db;Identity identity;ChatController chat;ServiceProposalController api;Jwt jwt;UUID uid,cid,pid,other;Map<String,String> terms;
 @BeforeEach void setup(){db=mock(JdbcTemplate.class);identity=mock(Identity.class);chat=mock(ChatController.class);api=new ServiceProposalController(db,identity,chat,new ObjectMapper());uid=UUID.randomUUID();cid=UUID.randomUUID();pid=UUID.randomUUID();other=UUID.randomUUID();jwt=Jwt.withTokenValue("test").header("alg","HS256").subject(uid.toString()).build();when(identity.id(jwt)).thenReturn(uid);provider();terms=new HashMap<>(Map.of("scope","Instalação completa","labor","1.500,00","materials","Cabos e conectores","supplier","Cliente","location","Rua A, 10","date","2026-11-01","deadline","Dois dias úteis","payment","Pix, metade na conclusão","other",""));}
 void provider(){when(chat.participant(cid,uid)).thenReturn(Map.of("user_id",other,"provider_user_id",uid,"status","active"));}
 void customer(){when(chat.participant(cid,uid)).thenReturn(Map.of("user_id",uid,"provider_user_id",other,"status","active"));}
 void pending(){when(db.queryForList(contains("FROM service_proposals"),any(Object[].class))).thenReturn(List.of(Map.of("id",pid,"version",1,"status","pending")));}
 void result(String status)throws Exception{when(db.queryForMap(anyString(),any(Object[].class))).thenReturn(Map.of("id",pid,"version",1,"status",status,"terms",new ObjectMapper().writeValueAsString(terms)));}
 @Test void customerCannotSendFormalProposal(){customer();assertThrows(ResponseStatusException.class,()->api.create(jwt,cid,new ServiceProposalController.Input(terms)));verifyNoInteractions(db);}
 @Test void providerCannotDecide(){assertThrows(ResponseStatusException.class,()->api.decide(jwt,cid,pid,new ServiceProposalController.Decision("accepted","")));verifyNoInteractions(db);}
 @Test void outsiderCannotRead(){when(chat.participant(cid,uid)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));assertThrows(ResponseStatusException.class,()->api.list(jwt,cid));verifyNoInteractions(db);}
 @Test void requireAllAgreedFields(){for(String key:List.of("scope","labor","materials","supplier","location","date","deadline","payment")){var input=new HashMap<>(terms);input.remove(key);assertThrows(ResponseStatusException.class,()->api.validate(input));}}
 @Test void rejectInvalidMoneyAndDate(){for(String value:List.of("-1,00","1.00","abc","1,2","9999999999,99")){var input=new HashMap<>(terms);input.put("labor",value);assertThrows(ResponseStatusException.class,()->api.validate(input));}terms.put("date","2026-02-30");assertThrows(ResponseStatusException.class,()->api.validate(terms));}
 @Test void cannotReplacePendingOrAccepted(){for(String status:List.of("pending","accepted")){when(db.queryForList(contains("SELECT version,status"),eq(cid))).thenReturn(List.of(Map.of("version",1,"status",status)));assertThrows(ResponseStatusException.class,()->api.create(jwt,cid,new ServiceProposalController.Input(terms)));}verify(db,never()).queryForMap(anyString(),any(Object[].class));}
 @Test void revisionCreatesNewVersionAndLogsMessage()throws Exception{when(db.queryForList(contains("SELECT version,status"),eq(cid))).thenReturn(List.of(Map.of("version",2,"status","changes_requested")));result("pending");api.create(jwt,cid,new ServiceProposalController.Input(terms));verify(db).queryForMap(contains("INSERT INTO service_proposals"),eq(cid),eq(3),anyString(),eq(uid));verify(chat).send(eq(jwt),eq(cid),argThat(text->text.content().contains("Versão 3")&&text.content().contains("1.500,00")));}
 @Test void clientAcceptsAndDecisionIsLogged()throws Exception{customer();pending();result("accepted");assertEquals("accepted",api.decide(jwt,cid,pid,new ServiceProposalController.Decision("accepted","Combinado")).get("status"));verify(db).queryForMap(contains("UPDATE service_proposals"),eq("accepted"),eq("Combinado"),eq(uid),eq(pid),eq(cid));verify(chat).send(eq(jwt),eq(cid),argThat(text->text.content().contains("aceita pelo cliente")));}
 @Test void clientCanReject()throws Exception{customer();pending();result("rejected");assertEquals("rejected",api.decide(jwt,cid,pid,new ServiceProposalController.Decision("rejected","Fora do orçamento")).get("status"));}
 @Test void clientRequestsChangesWithReason()throws Exception{customer();pending();result("changes_requested");assertEquals("changes_requested",api.decide(jwt,cid,pid,new ServiceProposalController.Decision("changes_requested","Alterar prazo para cinco dias")).get("status"));}
 @Test void changesNeedDescription(){customer();assertThrows(ResponseStatusException.class,()->api.decide(jwt,cid,pid,new ServiceProposalController.Decision("changes_requested"," ")));verify(db,never()).queryForMap(anyString(),any(Object[].class));}
 @Test void cannotRespondTwice(){customer();when(db.queryForList(contains("FROM service_proposals"),any(Object[].class))).thenReturn(List.of(Map.of("status","accepted")));assertThrows(ResponseStatusException.class,()->api.decide(jwt,cid,pid,new ServiceProposalController.Decision("rejected","")));verify(db,never()).queryForMap(anyString(),any(Object[].class));}
 @Test void proposalFromAnotherConversationIsUnavailable(){customer();assertThrows(ResponseStatusException.class,()->api.decide(jwt,cid,pid,new ServiceProposalController.Decision("accepted","")));verify(db).queryForList(contains("WHERE id=? AND conversation_id=?"),eq(pid),eq(cid));}
 @Test void closedConversationCannotReceiveProposal(){when(chat.participant(cid,uid)).thenReturn(Map.of("user_id",other,"provider_user_id",uid,"status","closed"));assertThrows(ResponseStatusException.class,()->api.create(jwt,cid,new ServiceProposalController.Input(terms)));verifyNoInteractions(db);}

 @Test void providerCannotMarkClientViewed(){assertThrows(ResponseStatusException.class,()->api.viewed(jwt,cid,pid));verifyNoInteractions(db);}
 @Test void clientViewRegistersOnlyCorrectProposal()throws Exception{customer();when(db.queryForList(contains("SELECT * FROM service_proposals"),eq(pid),eq(cid))).thenReturn(List.of(Map.of("status","pending","terms",new ObjectMapper().writeValueAsString(terms))));result("pending");api.viewed(jwt,cid,pid);verify(db).queryForMap(contains("SET client_viewed_at=now() WHERE id=? AND conversation_id=?"),eq(pid),eq(cid));}
 @Test void viewedProposalIsNotMarkedTwice()throws Exception{customer();when(db.queryForList(contains("SELECT * FROM service_proposals"),eq(pid),eq(cid))).thenReturn(List.of(Map.of("status","pending","client_viewed_at",new Date(),"terms",new ObjectMapper().writeValueAsString(terms))));api.viewed(jwt,cid,pid);verify(db,never()).queryForMap(anyString(),any(Object[].class));}
 @Test void cannotMarkProposalFromOtherConversation(){customer();assertThrows(ResponseStatusException.class,()->api.viewed(jwt,cid,pid));verify(db,never()).queryForMap(anyString(),any(Object[].class));}
}
