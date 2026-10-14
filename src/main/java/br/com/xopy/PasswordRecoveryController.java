package br.com.xopy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/auth")
public class PasswordRecoveryController {
  private final JdbcTemplate db;
  private final PasswordEncoder passwords;
  private final ObjectProvider<JavaMailSender> mail;
  private final String frontend, from;
  public PasswordRecoveryController(JdbcTemplate db, PasswordEncoder passwords, ObjectProvider<JavaMailSender> mail,
      @Value("${app.password-reset.frontend-url}") String frontend,
      @Value("${app.password-reset.from}") String from) {
    this.db=db; this.passwords=passwords; this.mail=mail; this.frontend=frontend.replaceAll("/+$", ""); this.from=from;
  }
  public record Forgot(@NotBlank @Email @Size(max=254) String email) {}
  public record Reset(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
      @NotBlank @Size(min=8,max=72) String newPassword) {}
  static String hash(String token) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
  @PostMapping("/forgot-password")
  @Transactional
  public Map<String,String> forgot(@Valid @RequestBody Forgot input) {
    JavaMailSender sender = mail.getIfAvailable();
    if(sender == null || from.isBlank()) throw new ResponseStatusException(SERVICE_UNAVAILABLE,
        "Recuperação indisponível. O administrador precisa configurar o envio de e-mail.");
    var rows=db.queryForList("SELECT id FROM app_users WHERE email=? AND deleted_at IS NULL FOR UPDATE", input.email().trim().toLowerCase(Locale.ROOT));
    if(!rows.isEmpty()) {
      Object uid=rows.get(0).get("id");
      boolean recent=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM password_reset_tokens WHERE user_id=? AND created_at>now()-interval '1 minute')", Boolean.class, uid));
      if(!recent) {
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("UPDATE password_reset_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL", uid);
        db.update("DELETE FROM password_reset_tokens WHERE expires_at<now()-interval '1 day'");
        db.update("INSERT INTO password_reset_tokens(token_hash,user_id,expires_at) VALUES(?,?,now()+interval '1 hour')", hash(token), uid);
        SimpleMailMessage message=new SimpleMailMessage(); message.setFrom(from); message.setTo(input.email().trim());
        message.setSubject("Xopy - Recuperação de senha");
        message.setText("Para criar uma nova senha, abra o link abaixo. Ele expira em 1 hora e só pode ser usado uma vez.\n\n"+frontend+"/reset-password#token="+token+"\n\nSe você não solicitou a recuperação, ignore este e-mail.");
        try { sender.send(message); }
        catch (org.springframework.mail.MailException e) { throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Não foi possível enviar o e-mail. Tente novamente mais tarde."); }
      }
    }
    return Map.of("message", "Se o e-mail estiver cadastrado, você receberá um link para redefinir sua senha.");
  }
  @PostMapping("/reset-password")
  @Transactional
  public Map<String,String> reset(@Valid @RequestBody Reset input) {
    if(input.newPassword().getBytes(StandardCharsets.UTF_8).length>72)
      throw new ResponseStatusException(BAD_REQUEST,"A senha deve ter no máximo 72 bytes");
    // Lock the account first, matching issuance order, to serialize concurrent resets.
    var rows=db.queryForList("SELECT u.id FROM app_users u JOIN password_reset_tokens t ON t.user_id=u.id WHERE t.token_hash=? AND u.deleted_at IS NULL FOR UPDATE OF u", hash(input.token()));
    if(rows.isEmpty()) throw new ResponseStatusException(BAD_REQUEST,"Link inválido ou expirado. Solicite um novo link.");
    Object uid=rows.get(0).get("id");
    int consumed=db.update("UPDATE password_reset_tokens SET used_at=now() WHERE token_hash=? AND used_at IS NULL AND expires_at>now()", hash(input.token()));
    if(consumed!=1) throw new ResponseStatusException(BAD_REQUEST,"Link inválido ou expirado. Solicite um novo link.");
    db.update("UPDATE app_users SET password_hash=?,token_version=token_version+1 WHERE id=?", passwords.encode(input.newPassword()), uid);
    db.update("UPDATE password_reset_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL", uid);
    return Map.of("message","Senha atualizada. Faça login com sua nova senha.");
  }
}
