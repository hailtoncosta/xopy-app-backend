package br.com.xopy;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceEngagementTest {
 JdbcTemplate db;Identity identity;ChatController chat;ServiceEngagementController api;UUID uid,id;Jwt jwt;
 @BeforeEach void setup(){db=mock(JdbcTemplate.class);identity=mock(Identity.class);chat=mock(ChatController.class);api=new ServiceEngagementController(db,identity,chat);uid=UUID.randomUUID();id=UUID.randomUUID();jwt=Jwt.withTokenValue("test").header("alg","HS256").subject(uid.toString()).build();when(identity.id(jwt)).thenReturn(uid);when(db.queryForList(contains("SELECT * FROM service_engagement_status WHERE conversation_id"),eq(id))).thenReturn(List.of(Map.of("conversation_id",id,"engagement_status","negotiating")));}
 ServiceEngagementController.Action action(String action){return new ServiceEngagementController.Action(action,"Motivo registrado");}
 void noWrites(){verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void listIncludesOnlyParticipants(){api.list(jwt);verify(db).queryForList(contains("WHERE customer_id=? OR provider_user_id=?"),eq(uid),eq(uid));}
 @Test void outsiderCannotGetOrCancel()throws Exception{when(chat.participant(id,uid)).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));assertThrows(ResponseStatusException.class,()->api.get(jwt,id));assertThrows(ResponseStatusException.class,()->api.act(jwt,id,action("cancel")));noWrites();}
 @Test void participantCanCancelEarlyNegotiation()throws Exception{api.act(jwt,id,action("cancel"));verify(db).update(contains("UPDATE conversations"),eq("cancelled"),eq("Motivo registrado"),eq(uid),eq(id));verify(db).update(contains("INSERT INTO service_case_events"),eq(id),eq(uid),eq("cancel"),eq("Motivo registrado"));verify(chat).send(eq(jwt),eq(id),any());}
 @Test void confirmedContractCannotBeCancelledDirectly(){when(db.queryForList(contains("SELECT accepted_at,checkout_id"),eq(id))).thenReturn(List.of(Map.of("accepted_at",new Date())));assertThrows(ResponseStatusException.class,()->api.act(jwt,id,action("cancel")));noWrites();}
 @Test void existingCheckoutCannotBeCancelledDirectly(){when(db.queryForList(contains("SELECT accepted_at,checkout_id"),eq(id))).thenReturn(List.of(Map.of("checkout_id",UUID.randomUUID())));assertThrows(ResponseStatusException.class,()->api.act(jwt,id,action("cancel")));noWrites();}
 @Test void disputeRequiresContract(){assertThrows(ResponseStatusException.class,()->api.act(jwt,id,action("dispute")));noWrites();}
 @Test void participantCanOpenDispute()throws Exception{when(db.queryForList(contains("SELECT accepted_at,checkout_id"),eq(id))).thenReturn(List.of(Map.of("status","paid")));api.act(jwt,id,action("dispute"));verify(db).update(contains("UPDATE conversations"),eq("disputed"),eq("Motivo registrado"),eq(uid),eq(id));}
 @Test void reasonAndActionValidated(){for(String reason:List.of(" ","x".repeat(2001)))assertThrows(ResponseStatusException.class,()->api.act(jwt,id,new ServiceEngagementController.Action("cancel",reason)));assertThrows(ResponseStatusException.class,()->api.act(jwt,id,action("paid")));noWrites();}
 @Test void cancelledOrDisputedCaseCannotChangeAgain(){for(String state:List.of("cancelled","disputed")){when(db.queryForList(contains("SELECT service_case_status"),eq(id))).thenReturn(List.of(Map.of("service_case_status",state)));assertThrows(ResponseStatusException.class,()->api.act(jwt,id,action("cancel")));}noWrites();}
 @Test void resolutionRequiresAdministrator(){doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(identity).requireAdmin(jwt);assertThrows(ResponseStatusException.class,()->api.resolve(jwt,id,action("resume")));assertThrows(ResponseStatusException.class,()->api.cases(jwt));noWrites();}
 @Test void adminCanResumeWithAudit(){when(db.queryForList(contains("SELECT service_case_status"),eq(id))).thenReturn(List.of(Map.of("service_case_status","disputed")));api.resolve(jwt,id,action("resume"));verify(db).update(contains("UPDATE conversations"),eq("active"),eq("Motivo registrado"),eq(uid),eq(id));verify(db).update(contains("INSERT INTO service_case_events"),eq(id),eq(uid),eq("resume"),eq("Motivo registrado"));}
 @Test void adminCanResolveAsCancelled(){when(db.queryForList(contains("SELECT service_case_status"),eq(id))).thenReturn(List.of(Map.of("service_case_status","disputed")));api.resolve(jwt,id,action("resolve_cancel"));verify(db).update(contains("UPDATE conversations"),eq("cancelled"),eq("Motivo registrado"),eq(uid),eq(id));}
 @Test void cannotResolveNonDisputedCase(){assertThrows(ResponseStatusException.class,()->api.resolve(jwt,id,action("resume")));noWrites();}
}
