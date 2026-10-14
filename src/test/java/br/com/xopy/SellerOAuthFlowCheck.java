package br.com.xopy;

import java.util.*;
import java.time.Instant;
import java.sql.Timestamp;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Real AES-GCM and OAuth service behavior with offline database/gateway doubles. */
public class SellerOAuthFlowCheck {
 static final UUID SELLER=PaymentFlowCheck.SELLER;
 static class DB extends JdbcTemplate {
  Map<String,Object> state,account;boolean used;int rotations;
  @Override public List<Map<String,Object>> queryForList(String sql,Object... args){
   if(sql.contains("from seller_mp_oauth_states")){if(state==null||used||!state.get("state_hash").equals(args[0]))return List.of();return List.of(state);}
   if(sql.contains("from seller_mp_accounts"))return account==null?List.of():List.of(account);
   if(sql.contains("from app_users"))return List.of(Map.of("id",SELLER,"token_version",0));
   if(sql.contains("from payment_sessions"))return List.of();
   throw new AssertionError(sql);
  }
  @Override public Map<String,Object> queryForMap(String sql,Object... args){return Map.of("id",SELLER,"token_version",0);}
  @Override public int update(String sql){return update(sql,new Object[0]);}
  @Override public int update(String sql,Object... args){
   if(sql.startsWith("delete from seller_mp_oauth_states"))return 0;
   if(sql.startsWith("insert into seller_mp_oauth_states")){used=false;state=new HashMap<>(Map.of("state_hash",args[0],"seller_id",args[1],"token_version",args[2],"current_version",args[2],"verifier_enc",args[3]));}
   else if(sql.startsWith("update seller_mp_oauth_states"))used=true;
   else if(sql.startsWith("insert into seller_mp_accounts")){account=new HashMap<>(Map.of("seller_id",args[0],"collector_id",args[1],"access_token_enc",args[2],"refresh_token_enc",args[3],"expires_at",args[4],"sandbox",args[5],"receiving_enabled",true));}
   else if(sql.contains("receiving_enabled=false"))account.put("receiving_enabled",false);
   else if(sql.startsWith("update seller_mp_accounts")){rotations++;account.put("access_token_enc",args[0]);account.put("refresh_token_enc",args[1]);account.put("expires_at",args[2]);}
   else throw new AssertionError(sql);
   return 1;
  }
 }
 static class Gateway extends PaymentFlowCheck.Gateway {
  int exchanges;Map<String,Object> received;
  @Override public JsonNode oauth(Map<String,Object> body){exchanges++;received=body;boolean refresh="refresh_token".equals(body.get("grant_type"));return PaymentFlowCheck.JSON.valueToTree(Map.of("user_id",123,"access_token",refresh?"new-token":"seller-token","refresh_token",refresh?"new-refresh":"refresh-1","expires_in",3600,"live_mode",false,"scope","offline_access payments write"));}
 }
 public static void main(String[] args){
  var cipher=new PaymentTokenCipher(Base64.getEncoder().encodeToString(new byte[32]));String encoded=cipher.encrypt("secret-token",SELLER+":access");PaymentFlowCheck.check(!encoded.contains("secret-token"),"ciphertext at rest");PaymentFlowCheck.check(cipher.decrypt(encoded,SELLER+":access").equals("secret-token"),"AES roundtrip");PaymentFlowCheck.reject(()->cipher.decrypt(encoded,UUID.randomUUID()+":access"),"AAD prevents credential swapping");PaymentFlowCheck.reject(()->cipher.decrypt(encoded.substring(0,encoded.length()-4)+"AAAA",SELLER+":access"),"GCM tamper");
  DB db=new DB();Gateway mp=new Gateway();var service=new SellerMercadoPago(db,mp,cipher,PaymentFlowCheck.manager());
  var auth=service.authorize(SELLER,0);String state=auth.get("state");PaymentFlowCheck.check(auth.get("authorizationUrl").contains("code_challenge_method=S256"),"PKCE");PaymentFlowCheck.check(!db.state.get("state_hash").equals(state),"state hashed in DB");
  PaymentFlowCheck.reject(()->service.callback(state,"wrong-browser","code"),"browser binding");PaymentFlowCheck.check(mp.exchanges==0&&!db.used,"invalid browser never exchanges");
  service.callback(state,state,"code");PaymentFlowCheck.check(mp.exchanges==1&&db.used&&db.account.get("collector_id").equals("123"),"seller account connected");PaymentFlowCheck.check(mp.received.containsKey("code_verifier"),"PKCE verifier exchange");PaymentFlowCheck.check(!db.account.get("access_token_enc").equals("seller-token"),"access encrypted");
  PaymentFlowCheck.reject(()->service.callback(state,state,"code"),"single-use OAuth state");PaymentFlowCheck.check(mp.exchanges==1,"no replay exchange");
  db.account.put("expires_at",Timestamp.from(Instant.now().minusSeconds(5)));var creds=service.credentials(SELLER,true);PaymentFlowCheck.check(creds.token().equals("new-token")&&db.rotations==1,"access refreshed");PaymentFlowCheck.check(cipher.decrypt(db.account.get("refresh_token_enc").toString(),SELLER+":refresh").equals("new-refresh"),"refresh token rotation persisted");service.credentials(SELLER,true);PaymentFlowCheck.check(db.rotations==1,"does not repeat renewal");
  service.pause(SELLER);PaymentFlowCheck.reject(()->service.credentials(SELLER,true),"pause new receipts");PaymentFlowCheck.check(service.credentials(SELLER,false).token().equals("new-token"),"can reconcile existing receipts after pause");
  System.out.println("Seller OAuth checks passed: encryption/AAD/tampering, hashed one-use state, browser binding, PKCE, account linking, refresh rotation and pause/reconciliation.");
 }
}
