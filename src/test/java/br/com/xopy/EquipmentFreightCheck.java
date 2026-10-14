package br.com.xopy;
import java.util.*;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
/** Offline regression for rental equipment registration with freight. */
public class EquipmentFreightCheck {
 public static void main(String[] args)throws Exception {
  ObjectMapper json=new ObjectMapper();UUID uid=UUID.randomUUID();
  List<Map<String,Object>> written=new ArrayList<>();
  JdbcTemplate db=new JdbcTemplate(){
   @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... params) {
    if(!sql.startsWith("INSERT INTO \"products\"") || !sql.contains("\"shipping_cost\""))throw new AssertionError(sql);
    try { written.add(json.readValue(params[0].toString(),Map.class)); } catch(Exception e){throw new AssertionError(e);}
    return List.of();
   }
  };
  Identity identity=new Identity(db){public UUID id(Jwt jwt){return uid;}public boolean admin(Jwt jwt){return false;}};
  var api=new DataController(db,json,identity);
  var jwt=Jwt.withTokenValue("test").header("alg","HS256").subject(uid.toString()).build();
  Map<String,Object> equipment=new HashMap<>(Map.of("title","Betoneira","description","[LOCAÇÃO] Teste","category","Máquinas","price",new BigDecimal("125.50"),"product_type","new","seller_type","individual","shipping_cost",new BigDecimal("50.00")));
  for(BigDecimal freight:List.of(new BigDecimal("50.00"),BigDecimal.ZERO)) {
   equipment.put("shipping_cost",freight);
   api.query("products",new DataController.Query("insert","*",List.of(),List.of(),null,null,equipment,null,false),jwt);
   var saved=written.get(written.size()-1);
   if(new BigDecimal(saved.get("shipping_cost").toString()).compareTo(freight)!=0 || !"pending".equals(saved.get("approval_status")) || !uid.toString().equals(saved.get("user_id")))throw new AssertionError(saved);
  }
  for(Map<String,Object> invalid:List.of(Map.<String,Object>of("shipping_cost",-1),Map.<String,Object>of("shipping_cost","50,00"),Map.<String,Object>of("unknown_column",1))) {
   var body=new HashMap<>(equipment);body.putAll(invalid);
   try {api.query("products",new DataController.Query("insert","*",List.of(),List.of(),null,null,body,null,false),jwt);throw new AssertionError("Invalid payload accepted");}catch(ResponseStatusException expected){}
  }
  if(written.size()!=2)throw new AssertionError("Invalid writes");
  System.out.println("PASS: cadastro com frete e frete zero; proprietário e aprovação preservados; frete negativo, texto e coluna desconhecida bloqueados.");
 }
}
