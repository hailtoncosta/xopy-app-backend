package br.com.xopy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class XopyPaymentAccountTest {
    private PaymentController controller(Identity identity, MercadoPagoGateway mp) {
        return new PaymentController(mock(JdbcTemplate.class), identity, mp,
                mock(PlatformTransactionManager.class), mock(SellerMercadoPago.class));
    }
    @Test void requiresAdministratorBeforeConsultingAccount() {
        var identity = mock(Identity.class);
        var mp = mock(MercadoPagoGateway.class);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
                .when(identity).requireAdmin(null);
        assertThrows(ResponseStatusException.class, () -> controller(identity, mp).verifyXopyAccount(null));
        verifyNoInteractions(mp);
    }
    @Test void rejectsTokenForDifferentRecipient() throws Exception {
        var mp = mock(MercadoPagoGateway.class);
        when(mp.enabled()).thenReturn(true);
        when(mp.collector()).thenReturn("123");
        when(mp.call("GET", "/users/me", null)).thenReturn(new ObjectMapper().readTree("{\"id\":456}"));
        var error = assertThrows(ResponseStatusException.class,
                () -> controller(mock(Identity.class), mp).verifyXopyAccount(null));
        assertEquals(409, error.getStatusCode().value());
    }
    @Test void verifiesMatchingRecipientWithoutCharging() throws Exception {
        var mp = mock(MercadoPagoGateway.class);
        when(mp.enabled()).thenReturn(true);
        when(mp.collector()).thenReturn("123");
        when(mp.call("GET", "/users/me", null)).thenReturn(new ObjectMapper().readTree("{\"id\":123,\"nickname\":\"XOPY\"}"));
        var result = controller(mock(Identity.class), mp).verifyXopyAccount(null);
        assertEquals(true, result.get("verified"));
        assertEquals("xopy", result.get("recipient"));
        verify(mp).enabled(); verify(mp).collector(); verify(mp).sandbox();
        verify(mp).call("GET", "/users/me", null);
        verifyNoMoreInteractions(mp);
    }
}
