package br.com.xopy;
import java.nio.file.*;import java.util.*;import java.util.zip.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class BackupTest {
 @TempDir Path temp;
 @Test void createsDownloadsAndDeletesRealMediaArchive()throws Exception{
  Path uploads=temp.resolve("uploads");Files.createDirectories(uploads);Files.writeString(uploads.resolve("sample.txt"),"dados reais");
  var api=new BackupController(mock(JdbcTemplate.class),mock(Identity.class),new ObjectMapper(),temp.resolve("backups").toString(),uploads.toString());
  var result=api.create(null,new BackupController.Creation("media"));UUID id=UUID.fromString(result.get("id").toString());assertEquals(1,api.list(null).size());assertEquals(64,result.get("checksum").toString().length());
  try(var zip=new ZipFile(temp.resolve("backups").resolve(id+".zip").toFile())){assertEquals("dados reais",new String(zip.getInputStream(zip.getEntry("uploads/sample.txt")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));assertNotNull(zip.getEntry("manifest.json"));}
  assertNotNull(api.download(null,id).getBody());api.delete(null,id);assertTrue(api.list(null).isEmpty());
 }
 @Test void requiresAdministratorBeforeWriting()throws Exception{
  var identity=mock(Identity.class);doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(identity).requireAdmin(null);
  var db=mock(JdbcTemplate.class);var api=new BackupController(db,identity,new ObjectMapper(),temp.resolve("backups").toString(),temp.resolve("uploads").toString());assertThrows(ResponseStatusException.class,()->api.create(null,new BackupController.Creation("media")));verifyNoInteractions(db);
 }
}
