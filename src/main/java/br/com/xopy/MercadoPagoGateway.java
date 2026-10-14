package br.com.xopy;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Component
public class MercadoPagoGateway {
    private final String token, secret, collector, frontend, webhook, clientId, clientSecret, redirect;
    private final boolean sandbox;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public MercadoPagoGateway(ObjectMapper json, @Value("${app.mp.access-token:}") String token,
            @Value("${app.mp.webhook-secret:}") String secret, @Value("${app.mp.collector-id:}") String collector,
            @Value("${app.mp.frontend-url:}") String frontend, @Value("${app.mp.webhook-url:}") String webhook,
            @Value("${app.mp.sandbox:true}") boolean sandbox, @Value("${app.mp.client-id:}") String clientId,
            @Value("${app.mp.client-secret:}") String clientSecret,
            @Value("${app.mp.oauth-redirect-url:}") String redirect) {
        this.json = json;
        this.token = token;
        this.secret = secret;
        this.collector = collector;
        this.frontend = frontend.replaceAll("/$", "");
        this.webhook = webhook;
        this.sandbox = sandbox;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirect = redirect;
    }

    public boolean enabled() {
        return !token.isBlank() && collector.matches("[0-9]+") && !secret.isBlank() && https(frontend)
                && https(webhook);
    }

    public List<String> missingConfiguration() {
        var missing=new ArrayList<String>();
        if(token.isBlank())missing.add("MP_ACCESS_TOKEN");
        if(!collector.matches("[0-9]+"))missing.add("MP_COLLECTOR_ID");
        if(secret.isBlank())missing.add("MP_WEBHOOK_SECRET");
        if(!https(frontend))missing.add("MP_FRONTEND_URL (HTTPS)");
        if(!https(webhook))missing.add("MP_WEBHOOK_URL (HTTPS)");
        return missing;
    }

    public boolean oauthEnabled() {
        return clientId.matches("[0-9]+") && !clientSecret.isBlank() && !secret.isBlank() && https(frontend)
                && https(webhook) && https(redirect);
    }

    private static boolean https(String url) {
        try {
            var u = URI.create(url);
            return "https".equals(u.getScheme()) && u.getHost() != null && u.getUserInfo() == null
                    && u.getFragment() == null && u.getQuery() == null;
        } catch (Exception e) {
            return false;
        }
    }

    public String frontend() {
        return frontend;
    }

    public String webhook() {
        return webhook;
    }

    public String collector() {
        return collector;
    }

    public boolean sandbox() {
        return sandbox;
    }

    public String clientId() {
        return clientId;
    }

    public String redirect() {
        return redirect;
    }

    public JsonNode oauth(Map<String, Object> fields) {
        var body = new HashMap<>(fields);
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        return send(null, "POST", "/oauth/token", body);
    }

    public JsonNode call(String method, String path, Object body) {
        if (token.isBlank())
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Credenciais da conta Xopy indisponíveis");
        return send(token, method, path, body);
    }

    public JsonNode call(String accessToken, String method, String path, Object body) {
        return send(accessToken, method, path, body);
    }

    private JsonNode send(String accessToken, String method, String path, Object body) {
        if (!enabled() && !oauthEnabled())
            throw new ResponseStatusException(SERVICE_UNAVAILABLE,
                    "Pagamento indisponível. O administrador precisa configurar a integração Mercado Pago.");
        try {
            var b = HttpRequest.newBuilder(URI.create("https://api.mercadopago.com" + path))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
            if (accessToken != null)
                b.header("Authorization", "Bearer " + accessToken);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() < 200 || r.statusCode() >= 300)
                throw new ResponseStatusException(BAD_GATEWAY,
                        "Mercado Pago indisponível ou configuração inválida. Tente consultar o pagamento novamente.");
            return json.readTree(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(BAD_GATEWAY, "Consulta ao Mercado Pago interrompida");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_GATEWAY,
                    "Não foi possível consultar o Mercado Pago. Nenhum pagamento foi confirmado.");
        }
    }

    public static boolean validSignature(String secret, String id, String requestId, String signature) {
        if (secret == null || secret.isBlank() || id == null || requestId == null || signature == null)
            return false;
        try {
            var parts = new HashMap<String, String>();
            for (String p : signature.split(",")) {
                var v = p.trim().split("=", 2);
                if (v.length == 2)
                    parts.put(v[0], v[1]);
            }
            String ts = parts.get("ts"), v1 = parts.get("v1");
            if (ts == null || !ts.matches("[0-9]+") || v1 == null || !v1.matches("[0-9a-fA-F]{64}"))
                return false;
            String manifest = "id:" + id.toLowerCase(Locale.ROOT) + ";request-id:" + requestId + ";ts:" + ts + ";";
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)),
                    HexFormat.of().parseHex(v1));
        } catch (Exception e) {
            return false;
        }
    }

    public void verify(String id, String requestId, String signature) {
        if (!validSignature(secret, id, requestId, signature))
            throw new ResponseStatusException(UNAUTHORIZED, "Assinatura de notificação inválida");
    }

    public static String safeCheckoutUrl(String value) {
        try {
            var u = URI.create(value);
            String h = u.getHost();
            if (!"https".equals(u.getScheme()) || h == null
                    || !(h.equals("mercadopago.com.br") || h.endsWith(".mercadopago.com.br"))
                    || u.getUserInfo() != null)
                throw new IllegalArgumentException();
            return value;
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_GATEWAY, "URL de pagamento inválida");
        }
    }
}
