package br.com.xopy;

import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Executable regression check; exercises controller behavior using an in-memory JDBC double. */
public class CheckoutFlowCheck {
 static final UUID BUYER=UUID.randomUUID(),SELLER=UUID.randomUUID(),PRODUCT=UUID.randomUUID(),CATALOG=UUID.randomUUID();
 static class Database extends JdbcTemplate {
  boolean paymentStarted;int stock=5,orders=0;String result,fingerprint;boolean approved=true;String status="pending";String metadata;
  @Override public List<Map<String,Object>> queryForList(String sql,Object... args){
   if(sql.contains("from payment_sessions"))return paymentStarted?List.of(Map.of("id",UUID.randomUUID())):List.of();
   if(sql.contains("from dynamic_products"))return args[0].equals(CATALOG)?List.of(Map.of("source_product_id",PRODUCT,"is_active",true)):List.of();
   if(sql.contains("from orders"))return List.of(Map.of("product_id",PRODUCT,"seller_id",SELLER,"status",status,"metadata",metadata));
   if(sql.contains("from products"))return List.of(Map.of("id",PRODUCT,"user_id",SELLER,"title","Produto teste","price",new BigDecimal("25.00"),"quantity",stock,"status","active","approval_status",approved?"approved":"pending"));
   return List.of();
  }
  @Override public Map<String,Object> queryForMap(String sql,Object... args){var map=new HashMap<String,Object>();map.put("fingerprint",fingerprint);map.put("result",result);return map;}
  @Override public int update(String sql,Object... args){
   if(sql.startsWith("insert into checkout_requests")&&fingerprint==null)fingerprint=(String)args[2];
   if(sql.startsWith("update checkout_requests"))result=(String)args[0];
   if(sql.startsWith("update products set quantity=quantity-"))stock-=(Integer)args[0];
   if(sql.startsWith("update products set quantity=quantity+"))stock+=(Integer)args[0];
   if(sql.startsWith("insert into orders")){orders++;metadata=(String)args[9];}
   if(sql.startsWith("update orders"))status="cancelled";
   return 1;
  }
 }
 static CheckoutController controller(Database db){return new CheckoutController(db,new Identity(db){@Override public UUID id(Jwt jwt){if(jwt==null)throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Faça login");return BUYER;}},new ObjectMapper());}
 static CheckoutController.Checkout input(int quantity,BigDecimal total,UUID request){return new CheckoutController.Checkout(List.of(new CheckoutController.Item(CATALOG,quantity)),"pickup",null,request,total);}
 static final Jwt JWT=Jwt.withTokenValue("test").header("alg","HS256").subject(BUYER.toString()).build();
 static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);}
 static void rejects(Runnable action,String name){try{action.run();throw new AssertionError(name);}catch(ResponseStatusException expected){}}
 public static void main(String[] args)throws Exception {
  Database db=new Database();var api=controller(db);
  var quote=api.quote(input(2,null,null),JWT);check(((BigDecimal)quote.get("total")).compareTo(new BigDecimal("50"))==0,"server price");check(db.stock==5,"quote does not reserve");
  rejects(()->api.quote(input(9,null,null),JWT),"stock validation");
  rejects(()->api.quote(input(1,null,null),null),"anonymous buyer");
  db.approved=false;rejects(()->api.quote(input(1,null,null),JWT),"unapproved product");db.approved=true;
  UUID request=UUID.randomUUID();var response=api.create(input(2,new BigDecimal("50"),request),JWT);check(db.stock==3&&db.orders==1,"reservation");
  api.create(input(2,new BigDecimal("50"),request),JWT);check(db.stock==3&&db.orders==1,"idempotent retry");
  UUID order=(UUID)((List<?>)response.get("orderIds")).get(0);db.paymentStarted=true;try{api.cancel(order,JWT);throw new AssertionError("cancel active payment");}catch(ResponseStatusException expected){check(db.stock==3,"active payment retains reservation");}db.paymentStarted=false;api.cancel(order,JWT);check(db.stock==5,"cancel releases stock");api.cancel(order,JWT);check(db.stock==5,"idempotent cancel");
  Database changed=new Database();try{controller(changed).create(input(1,new BigDecimal("1"),UUID.randomUUID()),JWT);throw new AssertionError("changed price");}catch(ResponseStatusException expected){check(changed.stock==5&&changed.orders==0,"no mutation on changed price");}
  System.out.println("Checkout checks passed: server totals, quote, stock, authentication, approval, reservation, retry, cancellation, changed price.");
 }
}
