package br.com.xopy;
import org.junit.jupiter.api.Test;
class PaymentRegressionTest {
 @Test void existingPaymentFlowRemainsSafe()throws Exception{PaymentFlowCheck.main(new String[0]);}

 @Test void serviceCheckoutUsesSameGatewayAndServiceReturn()throws Exception{
  var db=new PaymentFlowCheck.DB(){
   @Override public java.util.List<java.util.Map<String,Object>> queryForList(String sql,Object... args){if(sql.contains("from service_contracts"))return java.util.List.of(java.util.Map.of("id",java.util.UUID.randomUUID()));return super.queryForList(sql,args);}
  };
  var gateway=new PaymentFlowCheck.Gateway();var api=PaymentFlowCheck.api(db,gateway,new PaymentFlowCheck.Sellers(db,gateway));
  api.start(PaymentFlowCheck.REQUEST,PaymentFlowCheck.SELLER,PaymentFlowCheck.JWT);
  org.junit.jupiter.api.Assertions.assertTrue(gateway.lastBody.get("back_urls").toString().contains("?service=1"));
  org.junit.jupiter.api.Assertions.assertTrue(gateway.lastBody.get("items").toString().contains("Contratação de serviço Xopy"));
  api.start(PaymentFlowCheck.REQUEST,PaymentFlowCheck.SELLER,PaymentFlowCheck.JWT);
  org.junit.jupiter.api.Assertions.assertEquals(1,gateway.preferences);
  api.reconcile(PaymentFlowCheck.payment(db,PaymentFlowCheck.SELLER,"approved","456"));
  api.reconcile(PaymentFlowCheck.payment(db,PaymentFlowCheck.SELLER,"approved","456"));
  org.junit.jupiter.api.Assertions.assertEquals(1,db.paidWrites);
 }

 @Test void blockedServiceCannotStartGatewayPayment(){
  for(String state:java.util.List.of("disputed","cancelled")){
   java.util.UUID conversation=java.util.UUID.randomUUID();
   var db=new PaymentFlowCheck.DB(){
    @Override public java.util.List<java.util.Map<String,Object>> queryForList(String sql,Object... args){
     if(sql.contains("select conversation_id from service_contracts"))return java.util.List.of(java.util.Map.of("conversation_id",conversation));
     if(sql.contains("SELECT service_case_status"))return java.util.List.of(java.util.Map.of("service_case_status",state));
     return super.queryForList(sql,args);
    }
   };
   var gateway=new PaymentFlowCheck.Gateway();var api=PaymentFlowCheck.api(db,gateway,new PaymentFlowCheck.Sellers(db,gateway));
   org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,()->api.start(PaymentFlowCheck.REQUEST,PaymentFlowCheck.SELLER,PaymentFlowCheck.JWT));
   org.junit.jupiter.api.Assertions.assertEquals(0,gateway.preferences);
  }
 }
}
