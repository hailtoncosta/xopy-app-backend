package br.com.xopy;
import java.nio.file.*;import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
@RestController
@RequestMapping("/api/admin/media")
public class AdminMediaController {
 private final JdbcTemplate db;private final Identity identity;private final Path root;
 public AdminMediaController(JdbcTemplate db,Identity identity,@Value("${app.upload-dir}") String root){this.db=db;this.identity=identity;this.root=Path.of(root).toAbsolutePath().normalize();}
 @GetMapping public List<Map<String,Object>> list(@AuthenticationPrincipal Jwt jwt){identity.requireAdmin(jwt);return db.queryForList("SELECT id,bucket,object_key,content_type,size_bytes,created_at,coalesce(display_name,object_key) AS name,alt_text,description FROM stored_files WHERE bucket IN ('product-images','store-logos','avatars','videos','clips','clips-videos','portfolio-images','admin-media') ORDER BY created_at DESC,id");}
 public record Edit(String name,String alt,String description){}
 @PutMapping("/{id}") public Map<String,Object> edit(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Edit e){identity.requireAdmin(jwt);String name=Objects.toString(e.name(),"").trim(),alt=Objects.toString(e.alt(),""),description=Objects.toString(e.description(),"");if(name.isBlank()||name.length()>200||alt.length()>300||description.length()>2000)throw new ResponseStatusException(BAD_REQUEST,"Confira o nome e os limites dos campos");int changed=db.update("UPDATE stored_files SET display_name=?,alt_text=?,description=? WHERE id=? AND bucket <> 'service-provider-documents'",name,alt,description,id);if(changed!=1)throw new ResponseStatusException(NOT_FOUND,"Mídia não encontrada");return Map.of("saved",true);}
 @DeleteMapping("/{id}") public Map<String,Object> delete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id)throws Exception{identity.requireAdmin(jwt);var rows=db.queryForList("SELECT id FROM stored_files WHERE id=? AND bucket <> 'service-provider-documents'",id);if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND,"Mídia não encontrada");Files.deleteIfExists(root.resolve(id.toString()));db.update("DELETE FROM stored_files WHERE id=?",id);return Map.of("deleted",true);}
}
