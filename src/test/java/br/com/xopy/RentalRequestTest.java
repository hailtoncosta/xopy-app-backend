package br.com.xopy;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RentalRequestTest {
 @TempDir Path dir;
 JdbcTemplate db; Identity identity; RentalRequestController api;
 UUID uid=UUID.randomUUID(), other=UUID.randomUUID(), id=UUID.randomUUID();
 @BeforeEach void setup() throws Exception {
  db=mock(JdbcTemplate.class); identity=mock(Identity.class); when(identity.id(null)).thenReturn(uid);
  api=new RentalRequestController(db,identity,dir.toString());
 }
 @Test void rejectsExecutableAndEmptyDocuments() {
  assertThrows(ResponseStatusException.class,()->RentalRequestController.documentType(new byte[0]));
  assertThrows(ResponseStatusException.class,()->RentalRequestController.documentType("MZfile".getBytes()));
  assertEquals("application/pdf",RentalRequestController.documentType("%PDF-1.7".getBytes()));
 }
 @Test void customerCannotApproveOwnRequestToAnotherCompany() {
  when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("customer_id",uid,"company_id",other,"status","pending")));
  assertThrows(ResponseStatusException.class,()->api.review(null,id,Map.of("status","approved")));
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void unrelatedCompanyCannotDownloadDocuments() {
  when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("customer_id",other,"company_id",other,"status","pending")));
  assertThrows(ResponseStatusException.class,()->api.download(null,id,UUID.randomUUID()));
 }
 @Test void onlyPendingRequestsCanBeApproved() {
  when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("customer_id",other,"company_id",uid,"status","approved")));
  assertThrows(ResponseStatusException.class,()->api.review(null,id,Map.of("status","approved")));
 }
 @Test void companyCanApprovePendingRequest() throws Exception {
  UUID doc=UUID.randomUUID(); java.nio.file.Files.write(dir.resolve("rental-private").resolve(doc.toString()),"%PDF-1.7".getBytes());
  when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("customer_id",other,"company_id",uid,"status","pending","contract_document_id",doc)));
  when(db.update(anyString(),eq("approved"),eq(uid),eq(id))).thenReturn(1);
  assertEquals("approved",api.review(null,id,Map.of("status","approved")).get("status"));
 }
 @Test void approvalRequiresCompanyContract() {
  when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("customer_id",other,"company_id",uid,"status","pending")));
  assertThrows(ResponseStatusException.class,()->api.review(null,id,Map.of("status","approved")));
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void customerCannotUploadCompanyContract() {
  when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("customer_id",uid,"company_id",other,"status","pending")));
  var file=new org.springframework.mock.web.MockMultipartFile("contract","contrato.pdf","application/pdf","%PDF-1.7".getBytes());
  assertThrows(ResponseStatusException.class,()->api.uploadContract(null,id,file));
 }
 @Test void companyUploadsPdfAndCannotReplaceAfterApproval() throws Exception {
  when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("customer_id",other,"company_id",uid,"status","pending")));
  var file=new org.springframework.mock.web.MockMultipartFile("contract","contrato.pdf","application/pdf","%PDF-1.7".getBytes());
  var result=api.uploadContract(null,id,file);
  assertTrue(java.nio.file.Files.exists(dir.resolve("rental-private").resolve(result.get("id").toString())));
  when(db.queryForList(anyString(),eq(id))).thenReturn(List.of(Map.of("customer_id",other,"company_id",uid,"status","approved")));
  assertThrows(ResponseStatusException.class,()->api.uploadContract(null,id,file));
 }

}
