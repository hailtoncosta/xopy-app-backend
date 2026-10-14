package br.com.xopy;

import java.nio.file.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private final Path root;
    private final JdbcTemplate db;
    private final Identity identity;

    public FileController(@Value("${app.upload-dir}") String root, JdbcTemplate db, Identity identity)
            throws Exception {
        this.root = Path.of(root).toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        this.db = db;
        this.identity = identity;
    }

    @PostMapping(value = "/{bucket}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable String bucket, @RequestParam String key,
            @RequestPart("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) throws Exception {
        UUID owner = identity.id(jwt);
        validate(bucket, key);
        byte[] bytes = file.getBytes();
        String type = media(bytes);
        if (bytes.length == 0 || bytes.length > 10 * 1024 * 1024)
            throw new ResponseStatusException(BAD_REQUEST, "Arquivo deve possuir até 10 MB");
        UUID id = UUID.randomUUID();
        Path dest = root.resolve(id.toString());
        Files.write(dest, bytes, StandardOpenOption.CREATE_NEW);
        try {
            db.update(
                    "insert into stored_files(id,owner_id,bucket,object_key,content_type,size_bytes) values(?,?,?,?,?,?)",
                    id, owner, bucket, key, type, bytes.length);
        } catch (Exception e) {
            Files.deleteIfExists(dest);
            throw e;
        }
        return Map.of("data", Map.of("path", key, "id", id));
    }

    @GetMapping("/public/{bucket}/**")
    public ResponseEntity<Resource> get(@PathVariable String bucket, HttpServletRequest request) {
        String prefix = "/api/files/public/" + bucket + "/";
        String path = request.getServletPath();
        String key = path.substring(prefix.length());
        validate(bucket, key);
        List<Map<String, Object>> files = db
                .queryForList("select id,content_type from stored_files where bucket=? and object_key=?", bucket, key);
        if (files.isEmpty())
            throw new ResponseStatusException(NOT_FOUND, "Arquivo não encontrado");
        Resource resource = new FileSystemResource(root.resolve(files.get(0).get("id").toString()));
        if (!resource.exists())
            throw new ResponseStatusException(NOT_FOUND, "Arquivo não encontrado");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(files.get(0).get("content_type").toString()))
                .header("X-Content-Type-Options", "nosniff").body(resource);
    }

    @DeleteMapping("/{bucket}")
    public Map<String, Object> remove(@PathVariable String bucket, @RequestBody List<String> keys,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        UUID uid = identity.id(jwt);
        if (keys.size() > 100)
            throw new ResponseStatusException(BAD_REQUEST, "Lote excede 100 arquivos");
        for (String key : keys) {
            validate(bucket, key);
            List<UUID> ids = db.query("select id from stored_files where bucket=? and object_key=? and owner_id=?",
                    (rs, n) -> (UUID) rs.getObject(1), bucket, key, uid);
            for (UUID id : ids) {
                db.update("delete from stored_files where id=? and owner_id=?", id, uid);
                Files.deleteIfExists(root.resolve(id.toString()));
            }
        }
        return Map.of("data", keys);
    }

    private void validate(String bucket, String key) {
        if (!Set.of("product-images", "store-logos", "avatars", "service-provider-documents", "videos", "clips",
                "clips-videos", "portfolio-images", "admin-media").contains(bucket) || key.isBlank() || key.length() > 500 || key.contains("..")
                || key.startsWith("/") || !key.matches("[A-Za-z0-9_./ -]+"))
            throw new ResponseStatusException(BAD_REQUEST, "Bucket ou caminho inválido");
        if (bucket.equals("service-provider-documents"))
            throw new ResponseStatusException(NOT_IMPLEMENTED,
                    "Documentos privados exigem downloads autenticados ainda não convertidos");
    }

    private String media(byte[] b) {
        if (b.length > 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255)
            return "image/jpeg";
        if (b.length > 8 && b[0] == (byte) 137 && b[1] == 80 && b[2] == 78 && b[3] == 71)
            return "image/png";
        if (b.length > 12 && new String(b, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                && new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))
            return "image/webp";
        if (b.length > 6 && new String(b, 0, 3, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF"))
            return "image/gif";
        if (b.length > 12 && new String(b, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp"))
            return "video/mp4";
        throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "Aceitos JPEG, PNG, WebP, GIF e MP4");
    }
}
