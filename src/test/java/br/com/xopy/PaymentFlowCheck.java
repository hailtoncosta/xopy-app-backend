package br.com.xopy;

import java.util.*;
import java.math.BigDecimal;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Offline regression; no real gateway requests or funds. */
public class PaymentFlowCheck {
 static final UUID BUYER=UUID.randomUUID(),REQUEST=UUID.randomUUID(),SELLER=UUID.randomUUID(),SELLER2=UUID.randomUUID();
 static final ObjectMapper JSON=new ObjectMapper();
 static final Jwt JWT=Jwt.withTokenValue("test").header("alg","HS256").subject(BUYER.toString()).build();
 static PlatformTransactionManager manager(){return new PlatformTransactionManager(){public TransactionStatus getTransaction(TransactionDefinition d){return new SimpleTransactionStatus();}public void commit(TransactionStatus s){}public void rollback(TransactionStatus s){}};}
 static class DB extends JdbcTemplate {
  Map<UUID,Map<String,Object>> sessions=new HashMap<>();Map<UUID,String> orderStatus=new HashMap<>(Map.of(SELLER,"pending",SELLER2,"pending"));
  int notifications,paidWrites;boolean legacy;
  Map<String,Map<String,Object>> events=new HashMap<>();
  Map<String,Object> session(UUID seller){return sessions.values().stream().filter(s->seller.equals(s.get("seller_id"))).findFirst().orElseThrow();}
  @Override public List<Map<String,Object>> queryForList(String sql,Object... args){
   if(sql.contains("from checkout_requests"))return List.of(Map.of("request_id",REQUEST));
   if(sql.contains("from payment_sessions")){
    if(sql.contains("seller_id is null"))return legacy?List.of(Map.of("id",UUID.randomUUID())):List.of();
    if(sql.contains("where id=?"))return sessions.containsKey(args[0])?List.of(sessions.get(args[0])):List.of();
    return sessions.values().stream().filter(s->args.length<3||s.get("seller_id").equals(args[2])).toList();
   }
   if(sql.contains("from orders")){var sellers=args.length==3?List.of((UUID)args[2]):List.of(SELLER,SELLER2);return sellers.stream().filter(orderStatus::containsKey).map(id->Map.<String,Object>of("id",id,"seller_id",id,"status",orderStatus.get(id),"total_amount",new BigDecimal(id.equals(SELLER)?"50.00":"30.00"))).toList();}
   if(sql.contains("from payment_events"))return events.values().stream().filter(e->e.get("session_id").equals(args[0])&&Set.of("pending","in_process","authorized").contains(e.get("status"))).toList();
   if(sql.contains("from service_contracts"))return List.of();
   if(sql.contains("from rental_requests"))return List.of();
   if(sql.contains("from user_profiles"))return List.of(Map.of("full_name","Vendedor teste"));
   throw new AssertionError("Unexpected query: "+sql);
  }
  @Override public Map<String,Object> queryForMap(String sql,Object... args){return sessions.get(args[0]);}
  @Override public int update(String sql,Object... args){
   if(sql.stripLeading().startsWith("WITH desired"))return 0; // This fixture has no service contracts.
   if(sql.startsWith("insert into payment_sessions")){var s=new HashMap<String,Object>();s.put("id",args[0]);s.put("buyer_id",args[1]);s.put("request_id",args[2]);s.put("seller_id",args[3]);s.put("amount",args[4]);s.put("collector_id",args[5]);s.put("sandbox",args[6]);s.put("recipient","xopy");s.put("status","creating");sessions.put((UUID)args[0],s);}
   else if(sql.startsWith("update payment_sessions")){
    var s=sessions.get(args[args.length-1]);
    if(sql.contains("set preference_id=")){s.put("preference_id",args[0]);s.put("checkout_url",args[1]);if(s.get("status").equals("creating"))s.put("status","ready");}
    else if(sql.contains("approved_payment_id=?")){s.put("status","approved");s.put("approved_payment_id",args[0]);}
    else if(sql.contains("status=?"))s.put("status",args[0]);
    else if(sql.contains("set status='duplicate_review'"))s.put("status","duplicate_review");
    else if(sql.contains("set status='review_required'")){if(s.get("status").equals("creating")||!sql.contains("and status='creating'"))s.put("status","review_required");}
    else if(sql.contains("set status='pending'"))s.put("status","pending");
    else if(sql.contains("set status='ready'"))s.put("status","ready");
   }
   else if(sql.startsWith("insert into payment_events"))events.put((String)args[0],Map.of("payment_id",args[0],"session_id",args[1],"status",args[2]));
   else if(sql.startsWith("update orders")){check(sql.contains("and seller_id=?"),"seller filter required for order mutation");UUID seller=(UUID)args[4];orderStatus.put(seller,(String)args[0]);if(args[0].equals("paid"))paidWrites++;}
   else if(sql.startsWith("insert into notifications")){if(sql.contains("select distinct"))check(sql.contains("and seller_id=?"),"seller notification scope");notifications++;}
   else throw new AssertionError("Unexpected write: "+sql);
   return 1;
  }
 }
 static class Gateway extends MercadoPagoGateway {
  int preferences;boolean fail,wrongAccount;String lastToken;Map<String,Object> lastBody;JsonNode current;
  Gateway(){super(JSON,"xopy-token","secret","123","https://xopy.example","https://api.xopy.example/api/payments/webhook",true,"","","");}
  @Override public JsonNode call(String method,String path,Object body){return call("xopy-token",method,path,body);}
  @Override public JsonNode call(String token,String method,String path,Object body){
   lastToken=token;String collector=token.equals("seller2-token")?"222":"123";
   if(path.equals("/users/me"))return JSON.valueToTree(Map.of("id",wrongAccount?"999":collector));
   if(path.equals("/checkout/preferences")){preferences++;lastBody=(Map<String,Object>)body;if(fail)throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"timeout");return JSON.valueToTree(Map.of("id","pref-"+preferences,"collector_id",collector,"sandbox_init_point","https://sandbox.mercadopago.com.br/checkout/v1/redirect?pref_id=pref-"+preferences));}
   if(path.startsWith("/v1/payments/search"))return JSON.valueToTree(Map.of("results",current==null?List.of():List.of(Map.of("id",456))));
   if(path.equals("/v1/payments/456"))return current;
   throw new AssertionError(path);
  }
 }
 static class Sellers extends SellerMercadoPago {
  boolean connected=true,paused;
  Sellers(DB db,Gateway mp){super(db,mp,new PaymentTokenCipher(Base64.getEncoder().encodeToString(new byte[32])),manager());}
  @Override public boolean enabled(){return true;}
  @Override public Credentials credentials(UUID seller,boolean newPayment){if(!connected||newPayment&&paused)throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"seller disconnected");return new Credentials(seller.equals(SELLER2)?"seller2-token":"seller-token",seller.equals(SELLER2)?"222":"123",true);}
  @Override public Map<String,Object> status(UUID seller){return Map.of("receivingEnabled",connected&&!paused,"sandbox",true);}
 }
 static PaymentController api(DB db,Gateway mp,Sellers sellers){Identity who=new Identity(db){@Override public Map<String,Object> require(Jwt jwt){if(jwt==null)throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"login");return Map.of("id",BUYER,"email","buyer@example.com","token_version",0);}};return new PaymentController(db,who,mp,manager(),sellers);}
 static JsonNode payment(DB db,UUID seller,String status,String id){var session=db.session(seller);return JSON.valueToTree(Map.of("id",id,"external_reference",session.get("id").toString(),"collector_id",session.get("collector_id"),"currency_id","BRL","transaction_amount",session.get("amount"),"live_mode",false,"status",status,"payment_method_id","pix"));}
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
 static void reject(Runnable r,String label){try{r.run();throw new AssertionError(label);}catch(ResponseStatusException expected){}}
 public static void main(String[] args)throws Exception{
  DB db=new DB();Gateway mp=new Gateway();Sellers sellers=new Sellers(db,mp);var api=api(db,mp,sellers);
  reject(()->api.start(REQUEST,SELLER,null),"authentication");
  sellers.connected=false; // No seller connection is required for new Xopy payments.
  api.start(REQUEST,SELLER,JWT);check(mp.lastToken.equals("xopy-token"),"seller token");check(new BigDecimal(mp.lastBody.get("items").toString().contains("50.00")?"50":"0").intValue()==50,"seller subtotal");check(!mp.lastBody.containsKey("marketplace_fee"),"no platform commission");
  api.start(REQUEST,SELLER,JWT);check(mp.preferences==1,"reuse seller preference");api.start(REQUEST,SELLER2,JWT);check(mp.preferences==2&&mp.lastToken.equals("xopy-token"),"distinct second seller preference/token");
  api.reconcile(payment(db,SELLER,"pending","456"));check(db.orderStatus.get(SELLER).equals("pending"),"pending not paid");
  var bad=(com.fasterxml.jackson.databind.node.ObjectNode)payment(db,SELLER,"approved","456");bad.put("transaction_amount",80);reject(()->api.reconcile(bad),"cart total cannot pay single seller");
  var account=(com.fasterxml.jackson.databind.node.ObjectNode)payment(db,SELLER,"approved","456");account.put("collector_id",222);reject(()->api.reconcile(account),"another seller's recipient");
  var live=(com.fasterxml.jackson.databind.node.ObjectNode)payment(db,SELLER,"approved","456");live.put("live_mode",true);check(!PaymentController.matchesPayment(live,db.session(SELLER)),"environment");
  api.reconcile(payment(db,SELLER,"rejected","456"));check(db.session(SELLER).get("status").equals("ready"),"retry rejected");
  api.reconcile(payment(db,SELLER,"approved","456"));api.reconcile(payment(db,SELLER,"approved","456"));api.reconcile(payment(db,SELLER,"rejected","456"));check(db.paidWrites==1&&db.notifications==2&&db.orderStatus.get(SELLER).equals("paid"),"idempotency");check(db.orderStatus.get(SELLER2).equals("pending"),"does not pay other seller");
  api.reconcile(payment(db,SELLER2,"approved","777"));check(db.paidWrites==2&&db.orderStatus.get(SELLER2).equals("paid"),"second approval independent");
  api.reconcile(payment(db,SELLER,"approved","789"));check(db.session(SELLER).get("status").equals("duplicate_review"),"duplicate flagged");
  api.reconcile(payment(db,SELLER,"refunded","456"));api.reconcile(payment(db,SELLER,"approved","456"));check(db.orderStatus.get(SELLER).equals("refunded")&&db.orderStatus.get(SELLER2).equals("paid"),"refund isolated and nonregressing");
  String manifest="id:456;request-id:req-1;ts:123;";Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));String signature="ts=123,v1="+HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
  check(MercadoPagoGateway.validSignature("secret","456","req-1",signature),"valid webhook");check(!MercadoPagoGateway.validSignature("secret","457","req-1",signature),"tampered webhook");reject(()->api.webhook("456",null,"req-1","bad",null),"invalid webhook");
  reject(()->MercadoPagoGateway.safeCheckoutUrl("https://mercadopago.com.br.evil.example/payment"),"redirect host");
  DB uncertain=new DB();Gateway broken=new Gateway();broken.fail=true;var retry=api(uncertain,broken,new Sellers(uncertain,broken));reject(()->retry.start(REQUEST,SELLER,JWT),"timeout");retry.start(REQUEST,SELLER,JWT);check(broken.preferences==1&&uncertain.session(SELLER).get("status").equals("review_required"),"no duplicate after timeout");
  DB wrong=new DB();Gateway other=new Gateway();other.wrongAccount=true;reject(()->api(wrong,other,new Sellers(wrong,other)).start(REQUEST,SELLER,JWT),"wrong recipient token");check(other.preferences==0,"no charge wrong account");
  DB legacy=new DB();legacy.legacy=true;reject(()->api(legacy,new Gateway(),new Sellers(legacy,new Gateway())).start(REQUEST,SELLER,JWT),"legacy central checkout blocked");
  check(db.session(SELLER).get("collector_id").equals(db.session(SELLER2).get("collector_id")),"same Xopy collector for both sellers");
  DB historical=new DB();Gateway hg=new Gateway();var ha=api(historical,hg,new Sellers(historical,hg));ha.start(REQUEST,SELLER,JWT);historical.session(SELLER).put("recipient","seller");reject(()->ha.start(REQUEST,SELLER,JWT),"historical session cannot launch a new charge");
  System.out.println("Xopy payment checks passed: two sellers with one Xopy recipient/token, no OAuth required, scoped totals/approvals/refunds, historical block, idempotency, HMAC, timeout and account ownership.");
 }
}
