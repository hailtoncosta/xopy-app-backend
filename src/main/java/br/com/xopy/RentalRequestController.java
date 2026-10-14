package br.com.xopy;

import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/rental-requests")
public class RentalRequestController {
  private final JdbcTemplate db;
  private final Identity identity;
  private final Path root;

  public RentalRequestController(JdbcTemplate db, Identity identity, @Value("${app.upload-dir}") String dir)
      throws Exception {
    this.db = db;
    this.identity = identity;
    root = Path.of(dir).toAbsolutePath().resolve("rental-private");
    Files.createDirectories(root);
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public Map<String, Object> create(@AuthenticationPrincipal Jwt jwt, @RequestParam UUID equipmentId,
      @RequestParam int days, @RequestParam(required = false) String cnpj, @RequestParam(required = false) String taxId,
      @RequestParam(required = false) String personType, @RequestParam String deliveryAddress,
      @RequestPart(required = false) MultipartFile contract, @RequestPart MultipartFile residence,
      @RequestPart MultipartFile partner, @RequestPart(required = false) MultipartFile cnpjDocument) throws Exception {
    UUID customer = identity.id(jwt);
    String registeredType = db.queryForList("SELECT details->>'type' AS type FROM profile_details WHERE user_id=?", customer)
        .stream().findFirst().map(row -> Objects.toString(row.get("type"), "individual")).orElse("individual");
    boolean business = "business".equals(registeredType);
    if (personType != null && !personType.equals(registeredType))
      throw new ResponseStatusException(BAD_REQUEST, "Tipo de pessoa diferente do cadastro; atualize seus dados");
    cnpj = Objects.toString(taxId != null ? taxId : cnpj, "").replaceAll("\\D", "");
    if (business && (contract == null || cnpjDocument == null))
      throw new ResponseStatusException(BAD_REQUEST, "Anexe o contrato social e comprovante de CNPJ");
    if (days < 1 || days > 3650 || !cnpj.matches(business ? "[0-9]{14}" : "[0-9]{11}")
        || deliveryAddress.trim().length() < 10 || deliveryAddress.length() > 2000)
      throw new ResponseStatusException(BAD_REQUEST, "Informe CPF/CNPJ, endereço completo e período válido");
    List<Map<String, Object>> equipment = db.queryForList(
        "SELECT id,user_id,title AS name,shipping_cost FROM products WHERE id=? AND approval_status='approved' AND status='active' AND (product_type='rental' OR description LIKE '[LOCAÇÃO]%')",
        equipmentId);
    if (equipment.isEmpty())
      equipment = db.queryForList(
          "SELECT id,NULL::uuid AS user_id,name FROM dynamic_rentals WHERE id=? AND is_active=true AND available=true UNION ALL SELECT id,NULL::uuid AS user_id,name FROM dynamic_products WHERE id=? AND is_active=true AND product_group='rental' AND source_product_id IS NULL",
          equipmentId, equipmentId);
    if (equipment.isEmpty())
      throw new ResponseStatusException(NOT_FOUND, "Equipamento indisponível");
    Map<String, Object> item = equipment.get(0);
    UUID id = UUID.randomUUID();
    MultipartFile[] files = business ? new MultipartFile[] {contract, residence, partner, cnpjDocument} : new MultipartFile[] {residence, partner};
    String[] kinds = business ? new String[] {"Contrato social", "Comprovante de residência", "Documento do sócio", "CNPJ"} : new String[] {"Comprovante de residência", "Documento de identidade"};
    List<Path> written = new ArrayList<>();
    try {
      List<String> types = new ArrayList<>();
      for (MultipartFile file : files)
        types.add(documentType(file.getBytes()));
      db.update(
          "INSERT INTO rental_requests(id,customer_id,company_id,equipment_id,equipment_name,days,cnpj,delivery_address,shipping_cost,person_type) VALUES(?,?,?,?,?,?,?,?,?,?)",
          id, customer, item.get("user_id"), equipmentId, item.get("name"), days, cnpj.trim(), deliveryAddress.trim(),
          item.getOrDefault("shipping_cost", 0), registeredType);
      for (int i = 0; i < files.length; i++) {
        UUID doc = UUID.randomUUID();
        Path path = root.resolve(doc.toString());
        Files.write(path, files[i].getBytes(), StandardOpenOption.CREATE_NEW);
        written.add(path);
        String name = Objects.toString(files[i].getOriginalFilename(), "documento").replaceAll("[\\r\\n\\\\/]", "_");
        if (name.length() > 200)
          name = name.substring(name.length() - 200);
        db.update("INSERT INTO rental_request_documents(id,request_id,kind,filename,content_type) VALUES(?,?,?,?,?)",
            doc, id, kinds[i], name, types.get(i));
      }
      return Map.of("id", id, "status", "pending");
    } catch (Exception e) {
      for (Path path : written)
        Files.deleteIfExists(path);
      throw e;
    }
  }

  static String documentType(byte[] b) {
    if (b.length == 0 || b.length > 10 * 1024 * 1024)
      throw new ResponseStatusException(BAD_REQUEST, "Cada documento deve possuir até 10 MB");
    if (b.length >= 5 && new String(b, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
      return "application/pdf";
    if (b.length >= 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255)
      return "image/jpeg";
    if (b.length >= 8 && Arrays.equals(Arrays.copyOf(b, 8), new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 }))
      return "image/png";
    throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "Envie PDF, JPEG ou PNG");
  }

  @GetMapping
  public Map<String, Object> list(@AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "received") String view, @RequestParam(defaultValue = "0") int page) {
    UUID uid = identity.id(jwt);
    boolean admin = identity.admin(jwt);
    if (!Set.of("received", "sent").contains(view) || page < 0)
      throw new ResponseStatusException(BAD_REQUEST, "Consulta inválida");
    String scope = view.equals("sent") ? "r.customer_id=?"
        : admin ? "(r.company_id=? OR r.company_id IS NULL)" : "r.company_id=?";
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT r.*,o.status AS payment_status,o.total_amount AS payment_total,p.full_name AS customer_name,u.email AS customer_email FROM rental_requests r LEFT JOIN orders o ON o.buyer_id=r.customer_id AND o.metadata->>'checkout_request_id'=r.checkout_id::text AND o.order_type='rental' JOIN app_users u ON u.id=r.customer_id LEFT JOIN user_profiles p ON p.id=r.customer_id WHERE "
            + scope + " ORDER BY r.created_at DESC,r.id LIMIT 20 OFFSET ?",
        uid, page * 20L);
    for (Map<String, Object> row : rows) {
      var documents = new ArrayList<>(db.queryForList(
          "SELECT id,kind,filename FROM rental_request_documents WHERE request_id=? ORDER BY kind", row.get("id")));
      if (view.equals("sent") && !"approved".equals(row.get("status"))) {
        documents.removeIf(doc -> "Contrato de locação".equals(doc.get("kind")));
        row.put("contract_document_id", null);
      }
      row.put("documents", documents);
    }
    return Map.of("data", rows);
  }

  @PostMapping(value = "/{id}/contract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional(rollbackFor = Exception.class)
  public Map<String, Object> uploadContract(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestPart MultipartFile contract) throws Exception {
    var rental = authorized(id, identity.id(jwt), identity.admin(jwt), false, true);
    boolean legacyApproved = "approved".equals(rental.get("status")) && rental.get("contract_document_id") == null
        && rental.get("checkout_id") == null;
    if (!"pending".equals(rental.get("status")) && !legacyApproved)
      throw new ResponseStatusException(CONFLICT, "O contrato só pode ser anexado antes da aprovação");
    byte[] bytes = contract.getBytes();
    String type = documentType(bytes);
    if (!"application/pdf".equals(type))
      throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "Envie o contrato de locação em PDF");
    UUID doc = UUID.randomUUID();
    Path path = root.resolve(doc.toString());
    String name = Objects.toString(contract.getOriginalFilename(), "contrato.pdf").replaceAll("[\\r\\n\\\\/]", "_");
    if (name.length() > 200)
      name = name.substring(name.length() - 200);
    try {
      Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
      db.update(
          "INSERT INTO rental_request_documents(id,request_id,kind,filename,content_type) VALUES(?,?,'Contrato de locação',?,?) ON CONFLICT(request_id,kind) DO UPDATE SET id=excluded.id,filename=excluded.filename,content_type=excluded.content_type",
          doc, id, name, type);
      db.update("UPDATE rental_requests SET contract_document_id=? WHERE id=?", doc, id);
      if (legacyApproved)
        db.update("INSERT INTO notifications(user_id,title,message,type) VALUES(?,?,?,?)", rental.get("customer_id"),
            "Contrato de locação disponível", "Baixe o contrato em Minhas solicitações e siga para o pagamento.",
            "rental_request_approved");
      return Map.of("id", doc, "filename", name);
    } catch (Exception e) {
      Files.deleteIfExists(path);
      throw e;
    }
  }

  @PostMapping("/{id}/review")
  @Transactional
  public Map<String, Object> review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @RequestBody Map<String, String> body) {
    UUID uid = identity.id(jwt);
    String status = body.get("status");
    if (!Set.of("approved", "rejected").contains(Objects.toString(status, "")))
      throw new ResponseStatusException(BAD_REQUEST, "Decisão inválida");
    Map<String, Object> row = authorized(id, uid, identity.admin(jwt), false, true);
    if (!"pending".equals(row.get("status")))
      throw new ResponseStatusException(CONFLICT, "Solicitação já analisada");
    if ("approved".equals(status) && (row.get("contract_document_id") == null
        || !Files.isRegularFile(root.resolve(row.get("contract_document_id").toString()))))
      throw new ResponseStatusException(CONFLICT, "Anexe o contrato de locação antes de aprovar");
    if (db.update("UPDATE rental_requests SET status=?,reviewed_at=now(),reviewed_by=? WHERE id=? AND status='pending'",
        status, uid, id) != 1)
      throw new ResponseStatusException(CONFLICT, "Solicitação já analisada");
    if ("approved".equals(status))
      db.update("INSERT INTO notifications(user_id,title,message,type) VALUES(?,?,?,?)", row.get("customer_id"),
          "Contrato de locação disponível",
          "Sua solicitação foi aprovada. Baixe o contrato em Minhas solicitações e siga para o pagamento.",
          "rental_request_approved");
    return Map.of("status", status);
  }

  private Map<String, Object> authorized(UUID id, UUID uid, boolean admin, boolean customerAllowed) {
    return authorized(id, uid, admin, customerAllowed, false);
  }

  private Map<String, Object> authorized(UUID id, UUID uid, boolean admin, boolean customerAllowed, boolean lock) {
    List<Map<String, Object>> rows = db
        .queryForList("SELECT * FROM rental_requests WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty())
      throw new ResponseStatusException(NOT_FOUND, "Solicitação não encontrada");
    Map<String, Object> r = rows.get(0);
    boolean company = uid.equals(r.get("company_id")) || (admin && r.get("company_id") == null);
    if (!company && !(customerAllowed && (admin || uid.equals(r.get("customer_id")))))
      throw new ResponseStatusException(FORBIDDEN, "Sem acesso à solicitação");
    return r;
  }

  @GetMapping("/{id}/documents/{doc}")
  public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
      @PathVariable UUID doc) {
    Map<String, Object> rental = authorized(id, identity.id(jwt), identity.admin(jwt), true);
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT filename,content_type,kind FROM rental_request_documents WHERE request_id=? AND id=?", id, doc);
    if (rows.isEmpty() || !Files.exists(root.resolve(doc.toString())))
      throw new ResponseStatusException(NOT_FOUND, "Documento não encontrado");
    if ("Contrato de locação".equals(rows.get(0).get("kind")) && !"approved".equals(rental.get("status"))
        && !identity.id(jwt).equals(rental.get("company_id"))
        && !(identity.admin(jwt) && rental.get("company_id") == null))
      throw new ResponseStatusException(FORBIDDEN, "O contrato será disponibilizado após a aprovação");
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(rows.get(0).get("content_type").toString()))
        .header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
        .header("Content-Disposition",
            ContentDisposition.attachment()
                .filename(rows.get(0).get("filename").toString(), java.nio.charset.StandardCharsets.UTF_8).build()
                .toString())
        .body(new FileSystemResource(root.resolve(doc.toString())));
  }
}
