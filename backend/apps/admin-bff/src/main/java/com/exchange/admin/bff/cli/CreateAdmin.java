package com.exchange.admin.bff.cli;

import com.exchange.admin.bff.AdminBffApplication;
import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The first admin, and any later one made from a terminal rather than by a SUPERADMIN.
 *
 * <pre>docker compose --profile full run --rm admin-bff create-admin --email ops@example.com --role SUPERADMIN</pre>
 *
 * or, on bare metal, {@code java -jar admin-bff.jar create-admin ...}: a command built into the
 * app's own jar, run wherever the app runs.
 *
 * The password is typed, never passed: an argument is in the shell history, a migration is in
 * git forever. Boots the app without its web server against the same database and
 * {@code .env} the app reads, so it works wherever the app does. Prints nothing on success:
 * the admin enrols their authenticator at first login. docs/reference/admin.md.
 */
public final class CreateAdmin {

    private CreateAdmin() {
    }

    public static void run(String[] args) throws IOException {
        Map<String, String> options = parse(args);
        String email = options.get("--email");
        AdminRole role = AdminRole.valueOf(options.getOrDefault("--role", AdminRole.OPERATOR.name()));
        if (email == null || !email.contains("@")) {
            System.err.println("usage: create-admin --email ops@example.com [--role VIEWER|OPERATOR|SUPERADMIN]");
            System.exit(2);
        }
        String password = readPassword();
        if (password.length() < 12) {
            System.err.println("the password must be at least 12 characters");
            System.exit(2);
        }

        SpringApplication app = new SpringApplication(AdminBffApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setLogStartupInfo(false);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(Map.of("logging.level.root", "WARN"));
        try (ConfigurableApplicationContext context = app.run()) {
            AdminUserRepository admins = context.getBean(AdminUserRepository.class);
            if (admins.findByEmail(email).isPresent()) {
                System.err.println("an admin with that email exists");
                System.exit(1);
            }
            String hash = context.getBean(PasswordEncoder.class).encode(password);
            admins.save(new AdminUser(email, hash, role));
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        return options;
    }

    /** A real console hides what is typed. Under Gradle there is none -- stdin is a pipe from
     *  the daemon -- so the line is read as typed, and the prompt says so. Either way the
     *  prompt goes to stderr, which Gradle passes through where stdout is buffered. */
    private static String readPassword() throws IOException {
        Console console = System.console();
        if (console != null) {
            char[] typed = console.readPassword("Password: ");
            return typed == null ? "" : new String(typed);
        }
        System.err.print("Password (no terminal, so shown as you type): ");
        System.err.flush();
        String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        return line == null ? "" : line.strip();
    }
}
