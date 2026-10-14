package br.com.xopy;
import java.math.BigDecimal;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RentalCheckoutTest {
 JdbcTemplate db; Identity identity; RentalCheckoutController api;
 UUID buyer=UUID.randomUUID(),seller=UUID.randomUUID(),id=UUID.randomUUID(),equipment=UUID.randomUUID();
 @BeforeEach void setup() { db=mock(JdbcTemplate.class);identity=mock(Identity.class);when(identity.id(null)).thenReturn(buyer);api=new RentalCheckoutController(db,identity,new ObjectMapper(),"admin@xopy.com"); }
 Map<String,Object> rental(String status) {return new HashMap<>(Map.of("contract_document_id",UUID.randomUUID(),"status",status,"company_id",seller,"equipment_id",equipment,"days",3,"equipment_name","Betoneira","delivery_address","Rua Teste, 123"));}
 @Test void onlyApprovedRequestsCanStartPayment() throws Exception {
  for(String status:List.of("pending","rejected")) {
   when(db.queryForList(anyString(),eq(id),eq(buyer))).thenReturn(List.of(rental(status)));
   assertThrows(ResponseStatusException.class,()->api.checkout(null,id));
  }
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void anotherCustomerCannotAccessCheckout() {
  assertThrows(ResponseStatusException.class,()->api.checkout(null,id));
  verify(db).queryForList("SELECT * FROM rental_requests WHERE id=? AND customer_id=? FOR UPDATE",id,buyer);
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void repeatedClickReusesCheckout() throws Exception {
  var row=rental("approved");UUID checkout=UUID.randomUUID();row.put("checkout_id",checkout);
  when(db.queryForList(anyString(),eq(id),eq(buyer))).thenReturn(List.of(row));
  assertEquals(checkout,api.checkout(null,id).get("checkoutId"));
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void amountIsDailyPriceMultipliedByDaysWithoutSellingStock() throws Exception {
  when(db.queryForList(anyString(),eq(id),eq(buyer))).thenReturn(List.of(rental("approved")));
  when(db.queryForList(anyString(),eq(equipment),eq(seller))).thenReturn(List.of(Map.of("price","25.50")));
  assertNotNull(api.checkout(null,id).get("checkoutId"));
  var calls=mockingDetails(db).getInvocations().stream().filter(c->c.getMethod().getName().equals("update")).toList();
  var order=calls.stream().filter(c->c.getArgument(0).toString().startsWith("INSERT INTO orders")).findFirst().orElseThrow();
  Object[] args=order.getArguments();
  assertTrue(Arrays.asList(args).contains(new BigDecimal("76.50")));
  assertFalse(calls.stream().anyMatch(c->c.getArgument(0).toString().contains("UPDATE products")));
 }
 @Test void validatesCurrencyValues() {
  assertEquals(new BigDecimal("1234.56"),RentalCheckoutController.parsePrice("R$ 1.234,56"));
  for(String price:List.of("0","-5","abc"))assertThrows(ResponseStatusException.class,()->RentalCheckoutController.parsePrice(price));
 }
 @Test void approvedRequestWithoutContractCannotStartPayment() {
  var row=rental("approved");row.remove("contract_document_id");
  when(db.queryForList(anyString(),eq(id),eq(buyer))).thenReturn(List.of(row));
  assertThrows(ResponseStatusException.class,()->api.checkout(null,id));
  verify(db,never()).update(anyString(),any(Object[].class));
 }

 @Test void freightIsAddedOnceToRentalTotal() throws Exception {
  var row=rental("approved");row.put("shipping_cost",new BigDecimal("25.00"));
  when(db.queryForList(anyString(),eq(id),eq(buyer))).thenReturn(List.of(row));
  when(db.queryForList(anyString(),eq(equipment),eq(seller))).thenReturn(List.of(Map.of("price","25.50")));
  api.checkout(null,id);
  var order=mockingDetails(db).getInvocations().stream().filter(c->c.getMethod().getName().equals("update")&&c.getArgument(0).toString().startsWith("INSERT INTO orders")).findFirst().orElseThrow();
  assertTrue(Arrays.asList(order.getArguments()).contains(new BigDecimal("101.50")));
  assertTrue(Arrays.asList(order.getArguments()).contains(new BigDecimal("25.00")));
 }

}
