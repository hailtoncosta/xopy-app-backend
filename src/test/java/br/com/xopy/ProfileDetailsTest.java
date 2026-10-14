package br.com.xopy;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProfileDetailsTest {
 JdbcTemplate db;Identity identity;ProfileDetailsController api;UUID uid=UUID.randomUUID();
 @BeforeEach void setup(){db=mock(JdbcTemplate.class);identity=mock(Identity.class);when(identity.id(null)).thenReturn(uid);api=new ProfileDetailsController(db,identity,new ObjectMapper());}
 Map<String,String> valid(){return new HashMap<>(Map.of("type","individual","name","Maria Silva","phone","(98) 99999-9999","taxId","123.456.789-00","address","Rua Um, 123","city","São Luís","state","ma","zipCode","65000-000"));}
 @Test void incompleteProfileIsNotMarkedCompleted() throws Exception {assertEquals(false,api.get(null).get("completed"));verify(db).queryForList("SELECT details FROM profile_details WHERE user_id=?",uid);}
 @Test void invalidInformationDoesNotWriteAnything(){var data=valid();data.put("phone","");assertThrows(ResponseStatusException.class,()->api.save(null,data));verify(db, never()).update(anyString(), any(Object[].class));}
 @Test void missingDocumentOrAddressBlocksCompletion(){for(String field:List.of("taxId","address","city","zipCode","name")){var data=valid();data.put(field,"");assertThrows(ResponseStatusException.class,()->api.save(null,data));}verify(db, never()).update(anyString(), any(Object[].class));}
 @Test void savesOnlyAuthenticatedUserAndNormalizesInformation() throws Exception {var data=valid();data.put("user_id",UUID.randomUUID().toString());var result=api.save(null,data);assertEquals(true,result.get("completed"));var saved=(Map<?,?>)result.get("details");assertEquals("98999999999",saved.get("phone"));assertEquals("MA",saved.get("state"));assertFalse(saved.containsKey("user_id"));verify(db).update("UPDATE user_profiles SET full_name=?,phone=?,updated_at=now() WHERE id=?","Maria Silva","98999999999",uid);}
 @Test void businessMustProvideCompanyName(){var data=valid();data.put("type","business");data.put("taxId","12345678000100");assertThrows(ResponseStatusException.class,()->api.save(null,data));verify(db, never()).update(anyString(), any(Object[].class));}
 @Test void persistedCompletionSurvivesNewSession() throws Exception {when(db.queryForList(anyString(),eq(uid))).thenReturn(List.of(Map.of("details",new ObjectMapper().writeValueAsString(valid()))));assertEquals(true,api.get(null).get("completed"));}
 @Test void providerRequiresProfessionalFields(){var data=new HashMap<String,String>();data.put("name","Maria Silva");data.put("phone","98999999999");assertThrows(ResponseStatusException.class,()->ProfileDetailsController.validateProvider(data));}
 @Test void providerNormalizesAndRejectsInvalidRate(){var data=new HashMap<>(Map.of("name","Maria Silva","phone","(98) 99999-9999","category","pedreiro","experience","experiente","dailyRate","150.00"));assertEquals("98999999999",ProfileDetailsController.validateProvider(data).get("phone"));for(String rate:List.of("0","-1","abc","10.001")){data.put("dailyRate",rate);assertThrows(ResponseStatusException.class,()->ProfileDetailsController.validateProvider(data));}}
 @Test void professionalDetailsArePreservedAndBounded(){var data=new HashMap<>(Map.of("name","Maria Silva","phone","98999999999","category","pedreiro","experience","experiente","dailyRate","150.00","nickname","Maria","courses","Curso de alvenaria","taxId","123.456.789-00"));var saved=ProfileDetailsController.validateProvider(data);assertEquals("Maria",saved.get("nickname"));assertEquals("Curso de alvenaria",saved.get("courses"));assertEquals("12345678900",saved.get("taxId"));data.put("portfolioPhotos","javascript:alert(1)");assertThrows(ResponseStatusException.class,()->ProfileDetailsController.validateProvider(data));data.put("portfolioPhotos","");data.put("nickname","x".repeat(151));assertThrows(ResponseStatusException.class,()->ProfileDetailsController.validateProvider(data));}
}

