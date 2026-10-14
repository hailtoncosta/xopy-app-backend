package br.com.xopy;
import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class AdminMediaTest {
 @TempDir Path temp;
 @Test void protectsAdministrativeListing(){var db=mock(JdbcTemplate.class);var identity=mock(Identity.class);doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(identity).requireAdmin(null);assertThrows(ResponseStatusException.class,()->new AdminMediaController(db,identity,temp.toString()).list(null));verifyNoInteractions(db);}
 @Test void validatesMetadataBeforeWriting(){var db=mock(JdbcTemplate.class);var api=new AdminMediaController(db,mock(Identity.class),temp.toString());assertThrows(ResponseStatusException.class,()->api.edit(null,UUID.randomUUID(),new AdminMediaController.Edit(" ","","")));verifyNoInteractions(db);}
 @Test void removesRegisteredMediaFile()throws Exception{var db=mock(JdbcTemplate.class);UUID id=UUID.randomUUID();when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("id",id)));Files.writeString(temp.resolve(id.toString()),"conteúdo");new AdminMediaController(db,mock(Identity.class),temp.toString()).delete(null,id);assertFalse(Files.exists(temp.resolve(id.toString())));verify(db).update("DELETE FROM stored_files WHERE id=?",id);}
}
