package br.com.xopy;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ServiceCaseGuardTest {
 @Test void activeCaseAllowsFurtherSteps(){var db=mock(JdbcTemplate.class);UUID id=UUID.randomUUID();when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("service_case_status","active")));assertDoesNotThrow(()->ServiceCaseGuard.requireActive(db,id));verify(db).queryForList(contains("FOR UPDATE"),eq(id));}
 @Test void blockedCaseCannotAdvance(){for(String status:List.of("disputed","cancelled")){var db=mock(JdbcTemplate.class);UUID id=UUID.randomUUID();when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("service_case_status",status)));assertThrows(ResponseStatusException.class,()->ServiceCaseGuard.requireActive(db,id));}}
 @Test void checkoutGuardUsesBuyerAndConversation(){var db=mock(JdbcTemplate.class);UUID id=UUID.randomUUID(),buyer=UUID.randomUUID(),cid=UUID.randomUUID();when(db.queryForList(contains("select conversation_id from service_contracts"),eq(id),eq(buyer))).thenReturn(List.of(Map.of("conversation_id",cid)));when(db.queryForList(contains("SELECT service_case_status"),eq(cid))).thenReturn(List.of(Map.of("service_case_status","disputed")));assertThrows(ResponseStatusException.class,()->ServiceCaseGuard.checkout(db,id,buyer));verify(db).queryForList(contains("checkout_id=? and customer_id=?"),eq(id),eq(buyer));}
}
