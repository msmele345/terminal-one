package com.terminalone.user;

import com.terminalone.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the single V1 account from {@code app.seed-user.*} (env-sourced in prod)
 * on startup if it does not already exist. Idempotent: never overwrites an
 * existing user, so rotating the env password does not silently reset it.
 */
@Component
public class UserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public UserSeeder(AppUserRepository users, PasswordEncoder passwordEncoder, AppProperties props) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    @Override
    public void run(String... args) {
        String username = props.seedUser().username();
        if (users.existsByUsername(username)) {
            log.info("Seed user '{}' already present; skipping seed.", username);
            return;
        }
        String hash = passwordEncoder.encode(props.seedUser().password());
        users.save(new AppUser(username, hash));
        log.info("Seeded single-account user '{}'.", username);
    }
}
