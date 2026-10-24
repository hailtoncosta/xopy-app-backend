package br.com.xopy;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ServiceRequestTest {
 JdbcTemplate db;Identity identity;ServiceRequestController api;
 UUID uid=UUID.randomUUID(),provider=UUID.randomUUID(),recipient=UUID.randomUUID();
 @BeforeEach void setup(){db=mock(JdbcTemplate.class);identity=mock(Identity.class);when(identity.id(null)).thenReturn(uid);api=new ServiceRequestController(db,identity,new ObjectMapper());}
 @Test void invalidDescriptionDoesNotWrite(){assertThrows(ResponseStatusException.class,()->api.create(null,new ServiceRequestController.Input(provider,"curto")));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void unavailableProviderDoesNotWrite(){when(db.queryForList(anyString(),eq(provider))).thenReturn(List.of());assertThrows(ResponseStatusException.class,()->api.create(null,new ServiceRequestController.Input(provider,"Preciso reformar uma parede.")));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void selfRequestDoesNotWrite(){when(db.queryForList(anyString(),eq(provider))).thenReturn(List.of(Map.of("user_id",uid)));assertThrows(ResponseStatusException.class,()->api.create(null,new ServiceRequestController.Input(provider,"Preciso reformar uma parede.")));verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void requestIsDeliveredToRegisteredProvider() throws Exception {
  when(db.queryForList(anyString(),eq(provider))).thenReturn(List.of(Map.of("user_id",recipient)));
  when(db.queryForMap(anyString(),eq(uid))).thenReturn(Map.of("full_name","Maria Silva","phone","98999999999","email","maria@example.com"));
  var result=api.create(null,new ServiceRequestController.Input(provider,"Preciso reformar uma parede."));
  assertEquals(true,result.get("sent"));
  verify(db).update(eq("INSERT INTO notifications(id,user_id,title,message,type,data) VALUES(?,?,?,?,?,?::jsonb)"),eq(result.get("id")),eq(recipient),eq("Nova solicitação de serviços"),contains("Preciso reformar uma parede."),eq("service_request"),contains(uid.toString()));
 }
}
