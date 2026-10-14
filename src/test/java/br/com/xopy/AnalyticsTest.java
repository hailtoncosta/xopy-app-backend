package br.com.xopy;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AnalyticsTest {
 @Test void rejectsInvalidVisitsWithoutWriting(){var db=mock(JdbcTemplate.class);var api=new AnalyticsController(db,mock(Identity.class));assertThrows(ResponseStatusException.class,()->api.visit(new AnalyticsController.Visit(UUID.randomUUID(),"/produto?token=x","Direto","Desktop"),null));verifyNoInteractions(db);}
 @Test void administrativePagesAreNotCounted(){var db=mock(JdbcTemplate.class);var api=new AnalyticsController(db,mock(Identity.class));assertEquals(false,api.visit(new AnalyticsController.Visit(UUID.randomUUID(),"/admin/analytics","Direto","Desktop"),null).get("recorded"));verifyNoInteractions(db);}
 @Test void analyticsRequiresAdmin(){var db=mock(JdbcTemplate.class);var identity=mock(Identity.class);doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(identity).requireAdmin(null);assertThrows(ResponseStatusException.class,()->new AnalyticsController(db,identity).get(null,7));verifyNoInteractions(db);}
 @Test void emptyDatabaseReturnsSevenDaysAndZeroMetrics(){var db=mock(JdbcTemplate.class);when(db.queryForMap(anyString(),any(Object[].class))).thenAnswer(invocation->new LinkedHashMap<>(Map.of("revenue",0,"orders",0,"averageOrderValue",0,"pageViews",0,"sessions",0)));when(db.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);var result=new AnalyticsController(db,mock(Identity.class)).get(null,7);assertEquals(7,((List<?>)result.get("daily")).size());assertEquals(0.0,((Map<?,?>)result.get("current")).get("conversionRate"));}
}
