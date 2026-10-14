package br.com.xopy;

import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapAdmin implements ApplicationRunner {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final String email, password;

    public BootstrapAdmin(JdbcTemplate db, PasswordEncoder passwords,
            @Value("${app.bootstrap-admin-email}") String email,
            @Value("${app.bootstrap-admin-password}") String password) {
        this.db = db;
        this.passwords = passwords;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank())
            return;
        if (password.length() < 12 || password.length() > 72)
            throw new IllegalStateException("ADMIN_PASSWORD deve possuir de 12 a 72 caracteres");
        String normalized = email.toLowerCase(Locale.ROOT).trim();
        if (Boolean.TRUE.equals(
                db.queryForObject("select exists(select 1 from app_users where email=?)", Boolean.class, normalized)))
            return;
        UUID id = UUID.randomUUID();
        db.update("insert into app_users(id,email,password_hash,role) values(?,?,?,'ADMIN')", id, normalized,
                passwords.encode(password));
        db.update("insert into user_profiles(id,email,full_name,user_type) values(?,?,?,'customer')", id, normalized,
                "Administrador");
    }
}
