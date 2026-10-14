package br.com.xopy;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminUsersTest {
    JdbcTemplate db; Identity identity; AdminUsersController api;
    UUID actor = UUID.randomUUID(), target = UUID.randomUUID();
    @BeforeEach void setup() {
        db = mock(JdbcTemplate.class); identity = mock(Identity.class);
        api = new AdminUsersController(db, identity);
        when(identity.id(null)).thenReturn(actor);
    }
    @Test void requiresAdmin() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(identity).requireAdmin(null);
        assertThrows(ResponseStatusException.class, () -> api.delete(null, new AdminUsersController.Selection(List.of(target))));
        verifyNoInteractions(db);
    }
    @Test void refusesOwnAccountEvenInBatch() {
        assertThrows(ResponseStatusException.class, () -> api.delete(null, new AdminUsersController.Selection(List.of(target, actor))));
        verifyNoInteractions(db);
    }
    @Test void validatesAllTargetsBeforeAnyMutation() {
        assertThrows(ResponseStatusException.class, () -> api.delete(null, new AdminUsersController.Selection(List.of(target))));
        verify(db, never()).update(anyString(), any(Object[].class));
    }
    @Test void revokesAccountAndRemovesProfileWithoutDeletingFinancialReferences() {
        when(db.queryForList(anyString(), eq(target))).thenReturn(List.of(Map.of("id", target)));
        assertEquals(1, api.delete(null, new AdminUsersController.Selection(List.of(target, target))).get("deleted"));
        verify(db).update(contains("token_version=token_version+1"), eq("deleted-" + target + "@invalid.local"), anyString(), eq(target));
        verify(db).update("DELETE FROM user_profiles WHERE id=?", target);
        verify(db, never()).update(contains("DELETE FROM app_users"), any(Object[].class));
        verify(db, never()).update(contains("DELETE FROM orders"), any(Object[].class));
    }
}
