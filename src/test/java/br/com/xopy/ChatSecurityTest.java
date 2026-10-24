package br.com.xopy;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatSecurityTest {
 JdbcTemplate db; Identity identity; ChatController api; Jwt jwt; UUID uid,id;
 @BeforeEach void setup(){db=mock(JdbcTemplate.class);identity=mock(Identity.class);api=new ChatController(db,identity,new ObjectMapper());uid=UUID.randomUUID();id=UUID.randomUUID();jwt=Jwt.withTokenValue("test").header("alg","HS256").subject(uid.toString()).build();when(identity.id(jwt)).thenReturn(uid);}
 @Test void outsiderCannotRead(){assertThrows(ResponseStatusException.class,()->api.messages(jwt,id));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void outsiderCannotSend(){assertThrows(ResponseStatusException.class,()->api.send(jwt,id,new ChatController.Text("Olá")));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void outsiderCannotMarkRead(){assertThrows(ResponseStatusException.class,()->api.read(jwt,id));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void rejectSelfConversation(){when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("user_id",uid)));assertThrows(ResponseStatusException.class,()->api.start(jwt,new ChatController.Start(UUID.randomUUID())));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void rejectsEmptyAndOversizeMessages(){when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("user_id",uid,"provider_user_id",UUID.randomUUID(),"status","active")));for(String content:List.of("  ","x".repeat(8001)))assertThrows(ResponseStatusException.class,()->api.send(jwt,id,new ChatController.Text(content)));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void closedConversationCannotSend(){when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("user_id",uid,"status","closed")));assertThrows(ResponseStatusException.class,()->api.send(jwt,id,new ChatController.Text("Olá")));verify(db,never()).update(anyString(),any(Object[].class));}

 @Test void reuseExistingConversation()throws Exception{
  UUID provider=UUID.randomUUID();
  when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("user_id",UUID.randomUUID())),List.of(Map.of("id",id)));
  assertEquals(id,api.start(jwt,new ChatController.Start(provider)).get("id"));
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void serverDerivesProviderSenderAndNotifiesCustomer()throws Exception{
  UUID customer=UUID.randomUUID();
  when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("user_id",customer,"provider_user_id",uid,"status","active")));
  when(db.queryForMap(anyString(),any(Object[].class))).thenReturn(Map.of("id",UUID.randomUUID()));
  api.send(jwt,id,new ChatController.Text("Proposta"));
  verify(db).queryForMap(anyString(),eq(id),eq(uid),eq("service_provider"),eq("Proposta"));
  verify(db).update(contains("INSERT INTO notifications"),eq(customer),eq("Nova mensagem"),eq("Proposta"),eq("new_message"),anyString());
 }
}
