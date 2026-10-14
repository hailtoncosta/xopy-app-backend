package br.com.xopy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.*;
import java.security.MessageDigest;
import java.io.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
@RestController
@RequestMapping("/api/admin/backups")
public class BackupController {
 private final JdbcTemplate db;private final Identity identity;private final ObjectMapper json;private final Path root,uploads;private final ReentrantLock lock=new ReentrantLock();
 public BackupController(JdbcTemplate db,Identity identity,ObjectMapper json,@Value("${app.backup-dir:./backups}") String root,@Value("${app.upload-dir}") String uploads)throws IOException {
  this.db=db;this.identity=identity;this.json=json;this.root=Path.of(root).toAbsolutePath().normalize();this.uploads=Path.of(uploads).toAbsolutePath().normalize();
  if(this.root.startsWith(this.uploads)||this.uploads.startsWith(this.root))throw new IllegalArgumentException("Backups e uploads devem estar em diretórios separados");Files.createDirectories(this.root);
 }
 private Path archive(UUID id){return root.resolve(id+".zip");}private Path meta(UUID id){return root.resolve(id+".json");}
 @GetMapping public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt)throws IOException {
  identity.requireAdmin(jwt);var result=new ArrayList<Map<String,Object>>();
  try(var stream=Files.list(root)){for(Path p:stream.filter(p->p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).toList()){var m=json.readValue(Files.readString(p),Map.class);UUID id=UUID.fromString(m.get("id").toString());if(Files.isRegularFile(archive(id)))result.add(m);}}
  result.sort(Comparator.comparing((Map<String,Object> m)->m.get("createdAt").toString()).reversed());return result;
 }
 public record Creation(String type){}
 @PostMapping @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Map<String,Object> create(@AuthenticationPrincipal Jwt jwt,@RequestBody Creation input)throws Exception {
  identity.requireAdmin(jwt);String type=input.type();if(!Set.of("full","database","media","config").contains(Objects.toString(type,"")))throw new ResponseStatusException(BAD_REQUEST,"Tipo inválido");
  if(!lock.tryLock())throw new ResponseStatusException(CONFLICT,"Já existe um backup em criação");
  UUID id=UUID.randomUUID();Path temp=root.resolve(id+".part"),target=archive(id);
  try{
   var manifest=new LinkedHashMap<String,Object>();manifest.put("format","xopy-data-backup-v1");manifest.put("type",type);manifest.put("createdAt",Instant.now().toString());manifest.put("databaseFormat","PostgreSQL row JSON; data export, not pg_dump");
   try(var zip=new ZipOutputStream(Files.newOutputStream(temp,StandardOpenOption.CREATE_NEW))){
    if(type.equals("full")||type.equals("database")||type.equals("config")){
     List<String> tables=db.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class);var counts=new LinkedHashMap<String,Long>();
     var columns=db.queryForList("SELECT table_name,column_name,data_type,udt_name,column_default,is_nullable,is_identity FROM information_schema.columns WHERE table_schema='public' ORDER BY table_name,ordinal_position");
     zip.putNextEntry(new ZipEntry("database/schema-columns.json"));zip.write(json.writeValueAsBytes(columns));zip.closeEntry();
     var constraints=db.queryForList("SELECT c.relname AS table_name,con.conname AS name,pg_get_constraintdef(con.oid) AS definition FROM pg_constraint con JOIN pg_class c ON c.oid=con.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'");
     zip.putNextEntry(new ZipEntry("database/constraints.json"));zip.write(json.writeValueAsBytes(constraints));zip.closeEntry();
     for(String table:tables){if(type.equals("config") && !(table.contains("settings")||table.startsWith("dynamic_")))continue;
      String safe=table.replace("\"","\"\"");counts.put(table,db.queryForObject("SELECT count(*) FROM public.\""+safe+"\"",Long.class));
      zip.putNextEntry(new ZipEntry("database/"+table.replaceAll("[^a-zA-Z0-9_-]","_")+".json"));
      JsonGenerator generator=json.getFactory().createGenerator(zip);generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);generator.writeStartArray();
      db.query("SELECT to_jsonb(t)::text FROM public.\""+safe+"\" t",ps->ps.setFetchSize(500),(org.springframework.jdbc.core.RowCallbackHandler)rs->{try{generator.writeRawValue(rs.getString(1));}catch(IOException e){throw new UncheckedIOException(e);}});
      generator.writeEndArray();generator.close();zip.closeEntry();
     }manifest.put("rowsByTable",counts);
    }
    if(type.equals("full")||type.equals("media")){
     long files=0;if(Files.exists(uploads)){try(var walk=Files.walk(uploads)){for(Path p:walk.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()){zip.putNextEntry(new ZipEntry("uploads/"+uploads.relativize(p).toString().replace('\\','/')));Files.copy(p,zip);zip.closeEntry();files++;}}}manifest.put("uploadedFiles",files);
    }
    zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(json.writeValueAsBytes(manifest));zip.closeEntry();
   }
   try(var check=new ZipFile(temp.toFile())){var entries=check.entries();while(entries.hasMoreElements()){try(var in=check.getInputStream(entries.nextElement())){in.transferTo(OutputStream.nullOutputStream());}}}
   var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(temp)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
   Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
   var metadata=new LinkedHashMap<String,Object>();metadata.put("id",id.toString());metadata.put("name","xopy-"+type+"-"+id+".zip");metadata.put("type",type);metadata.put("createdAt",manifest.get("createdAt"));metadata.put("size",Files.size(target));metadata.put("checksum",HexFormat.of().formatHex(digest.digest()));
   Path metadataTemp=root.resolve(id+".metadata.part");Files.writeString(metadataTemp,json.writeValueAsString(metadata),StandardOpenOption.CREATE_NEW);Files.move(metadataTemp,meta(id),StandardCopyOption.ATOMIC_MOVE);return metadata;
  }catch(Exception e){Files.deleteIfExists(temp);Files.deleteIfExists(target);Files.deleteIfExists(meta(id));Files.deleteIfExists(root.resolve(id+".metadata.part"));throw e;}finally{lock.unlock();}
 }
 @GetMapping("/{id}/download") public ResponseEntity<Resource> download(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id)throws IOException{
  identity.requireAdmin(jwt);if(!Files.isRegularFile(archive(id))||!Files.isRegularFile(meta(id)))throw new ResponseStatusException(NOT_FOUND,"Backup não encontrado");var m=json.readValue(Files.readString(meta(id)),Map.class);
  return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).contentLength(Files.size(archive(id))).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(m.get("name").toString()).build().toString()).header(HttpHeaders.CACHE_CONTROL,"no-store").body(new FileSystemResource(archive(id)));
 }
 @DeleteMapping("/{id}") public Map<String,Object> delete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id)throws IOException{
  identity.requireAdmin(jwt);if(!lock.tryLock())throw new ResponseStatusException(CONFLICT,"Aguarde o backup em criação");try{if(!Files.exists(archive(id)))throw new ResponseStatusException(NOT_FOUND,"Backup não encontrado");Files.delete(archive(id));Files.deleteIfExists(meta(id));return Map.of("deleted",true);}finally{lock.unlock();}
 }
}
