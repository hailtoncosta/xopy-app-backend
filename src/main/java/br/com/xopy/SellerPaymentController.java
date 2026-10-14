package br.com.xopy;

import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class SellerPaymentController {
    private final SellerMercadoPago sellers;
    private final Identity identity;
    private final MercadoPagoGateway mp;

    public SellerPaymentController(SellerMercadoPago sellers, Identity identity, MercadoPagoGateway mp) {
        this.sellers = sellers;
        this.identity = identity;
        this.mp = mp;
    }

    private static String cookie(String state, long age) {
        return ResponseCookie.from("__Host-xopy-mp-oauth", state).httpOnly(true).secure(true).path("/").sameSite("Lax")
                .maxAge(age).build().toString();
    }

    @GetMapping("/seller/account")
    public Map<String, Object> account(@AuthenticationPrincipal Jwt jwt) {
        identity.id(jwt);
        return Map.of("configured", mp.enabled(), "connected", false, "receivingEnabled", mp.enabled(), "recipient",
                "xopy", "sandbox", mp.sandbox());
    }

    @PostMapping("/seller/connect")
    public ResponseEntity<Map<String, String>> connect(@AuthenticationPrincipal Jwt jwt) {
        identity.id(jwt);
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT,
                "Os pagamentos são recebidos na conta do Xopy. Não é necessário conectar uma conta de vendedor.");
    }

    @PostMapping("/seller/pause")
    public Map<String, Object> pause(@AuthenticationPrincipal Jwt jwt) {
        sellers.pause(identity.id(jwt));
        return Map.of("paused", true);
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @CookieValue(name = "__Host-xopy-mp-oauth", required = false) String cookie) {
        String result = "error";
        try {
            sellers.callback(state, cookie, code);
            result = "connected";
        } catch (RuntimeException ignored) {
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.SET_COOKIE, cookie("", 0))
                .header("Referrer-Policy", "no-referrer")
                .location(URI.create(mp.frontend() + "/pagamentos?connection=" + result)).build();
    }
}
