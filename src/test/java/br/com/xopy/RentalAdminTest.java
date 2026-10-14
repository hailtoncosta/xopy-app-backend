package br.com.xopy;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RentalAdminTest {
    JdbcTemplate db; Identity identity; RentalAdminController api;
    UUID id = UUID.randomUUID();
    @BeforeEach void setup() {
        db = mock(JdbcTemplate.class); identity = mock(Identity.class);
        api = new RentalAdminController(db, identity, new ObjectMapper());
    }
    @Test void rejectsNonAdminBeforeAccessingData() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(identity).requireAdmin(null);
        assertThrows(ResponseStatusException.class, () -> api.list(null, "all", "", 0));
        assertThrows(ResponseStatusException.class, () -> api.review(null, id, new RentalAdminController.Decision("approved", "")));
        assertThrows(ResponseStatusException.class, () -> api.delete(null, id));
        verifyNoInteractions(db);
    }
    @Test void deletesOnlyRejectedEquipmentAndItsPublication() {
        when(db.queryForList(anyString(), eq(id))).thenReturn(List.of(Map.of("approval_status", "rejected")));
        assertEquals("deleted", api.delete(null, id).get("status"));
        verify(db).update("DELETE FROM dynamic_products WHERE source_product_id=?", id);
        verify(db).update("DELETE FROM products WHERE id=? AND approval_status='rejected'", id);
    }
    @Test void refusesDeletionOfPendingAndApprovedEquipment() {
        for (String status : List.of("pending", "approved")) {
            when(db.queryForList(anyString(), eq(id))).thenReturn(List.of(Map.of("approval_status", status)));
            assertThrows(ResponseStatusException.class, () -> api.delete(null, id));
        }
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void refusesDeletionOfUnknownEquipment() {
        assertThrows(ResponseStatusException.class, () -> api.delete(null, id));
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void rejectionRequiresReason() {
        assertThrows(ResponseStatusException.class, () -> api.review(null, id, new RentalAdminController.Decision("rejected", "  ")));
        verifyNoInteractions(db);
    }
    @Test void refusesNonRentalOrMissingProduct() {
        assertThrows(ResponseStatusException.class, () -> api.review(null, id, new RentalAdminController.Decision("approved", "")));
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void approvalPublishesRentalWithoutChangingCategory() {
        when(db.queryForList(anyString(), eq(id))).thenReturn(List.of(Map.of("id", id)));
        api.review(null, id, new RentalAdminController.Decision("approved", "Verificado"));
        verify(db).update(contains("UPDATE products SET approval_status"), eq("approved"), eq("Verificado"), eq("active"), eq(id));
        verify(db).update(contains("NOT EXISTS"), eq(id));
        verify(db).update(contains("product_group='rental'"), eq(id));
    }
    @Test void rejectionDisablesPublication() {
        when(db.queryForList(anyString(), eq(id))).thenReturn(List.of(Map.of("id", id)));
        api.review(null, id, new RentalAdminController.Decision("rejected", "Foto inválida"));
        verify(db).update(contains("UPDATE products SET approval_status"), eq("rejected"), eq("Foto inválida"), eq("inactive"), eq(id));
        verify(db).update(contains("SET is_active=false"), eq(id));
        verify(db, never()).update(contains("INSERT INTO dynamic_products"), eq(id));
    }
}
