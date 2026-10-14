package br.com.xopy;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DataSecurityTest {
 JdbcTemplate db;Identity identity;DataController api;Jwt jwt;UUID uid;
 @BeforeEach void setup()throws Exception{
  db=mock(JdbcTemplate.class);identity=mock(Identity.class);api=new DataController(db,new ObjectMapper(),identity);uid=UUID.randomUUID();
  jwt=Jwt.withTokenValue("test").header("alg","HS256").subject(uid.toString()).claim("ver",0).build();when(identity.id(jwt)).thenReturn(uid);when(identity.admin(jwt)).thenReturn(false);
 }
 DataController.Query q(String action,List<DataController.Filter> filters,Object values){return new DataController.Query(action,"*",filters,List.of(),null,null,values,null,false);}
 @Test void rejectsSqlIdentifierInjection(){assertThrows(ResponseStatusException.class,()->api.query("products;DROP TABLE app_users",q("select",List.of(),null),jwt));verifyNoInteractions(db);}
 @Test void rejectsOwnerSpoof(){assertThrows(ResponseStatusException.class,()->api.query("products",q("insert",List.of(),Map.of("user_id",UUID.randomUUID().toString())),jwt));verifyNoInteractions(db);}
 @Test void rejectsClientApproval(){assertThrows(ResponseStatusException.class,()->api.query("products",q("update",List.of(new DataController.Filter("id","eq",UUID.randomUUID())),Map.of("approval_status","approved")),jwt));verifyNoInteractions(db);}
 @Test void rejectsFinancialMutation(){assertThrows(ResponseStatusException.class,()->api.query("orders",q("insert",List.of(),Map.of("status","paid")),jwt));verifyNoInteractions(db);}
 @Test void rejectsUnboundedUpdate(){assertThrows(ResponseStatusException.class,()->api.query("user_addresses",q("update",List.of(),Map.of("city","São Luís")),jwt));verifyNoInteractions(db);}
 @Test void rejectsAnonymousPrivateData(){assertThrows(ResponseStatusException.class,()->api.query("user_profiles",q("select",List.of(),null),null));verifyNoInteractions(db);}
 @Test void genericDeleteCannotBypassRentalRejectionRule(){
  when(identity.admin(jwt)).thenReturn(true);
  when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("id",UUID.randomUUID(),"description","[LOCAÇÃO] Equipamento","approval_status","approved")));
  assertThrows(ResponseStatusException.class,()->api.query("products",q("delete",List.of(new DataController.Filter("id","eq",UUID.randomUUID())),null),jwt));
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void providerRejectionRequiresReason(){when(identity.admin(jwt)).thenReturn(true);for(String reason:List.of("", "   ", "x".repeat(2001))){assertThrows(ResponseStatusException.class,()->api.query("service_providers",q("update",List.of(new DataController.Filter("id","eq",UUID.randomUUID())),Map.of("status","rejected","rejection_reason",reason)),jwt));}verifyNoInteractions(db);}
 @Test void providerCannotAlterRejectionReason(){assertThrows(ResponseStatusException.class,()->api.query("service_providers",q("update",List.of(new DataController.Filter("id","eq",UUID.randomUUID())),Map.of("rejection_reason","Outro motivo")),jwt));verifyNoInteractions(db);}
}
