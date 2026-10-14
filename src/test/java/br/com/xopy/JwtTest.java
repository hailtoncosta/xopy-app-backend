package br.com.xopy;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;
class JwtTest {
 @Test void signsValidatesAndRejectsOtherKey(){
  SecurityConfig config=new SecurityConfig();var key=config.jwtKey("abcdefghijklmnopqrstuvwxyz0123456789");
  var claims=JwtClaimsSet.builder().issuer("xopy-backend").subject("user").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
  String token=config.encoder(key).encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
  assertEquals("user",config.decoder(key,"xopy-backend").decode(token).getSubject());
  assertThrows(JwtException.class,()->config.decoder(config.jwtKey("different-key-abcdefghijklmnopqrstuvwxyz"),"xopy-backend").decode(token));
  assertThrows(JwtException.class,()->config.decoder(key,"wrong-issuer").decode(token));
 }
 @Test void rejectsShortSecret(){assertThrows(IllegalStateException.class,()->new SecurityConfig().jwtKey("short"));}
}
