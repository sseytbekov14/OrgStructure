package cs_orgchart.config;

import cs_orgchart.security.AuthMode;
import cs_orgchart.security.PasswordUsers;
import cs_orgchart.security.RoleLists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The one filter chain. How people sign in is {@link AuthMode}: the password form (accounts of
 * AUTH_PASSWORD_USERS) or Microsoft Entra ID over SAML - never both. Every profile has the same rules;
 * local only differs in its one-off account when AUTH_PASSWORD_USERS is empty.
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    static final String LOCAL_ACCOUNT = "dev";

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, Environment env, AuthMode authMode,
                                           RoleLists roleLists) throws Exception {
        boolean isLocal = env.acceptsProfiles(Profiles.of("local"));

        http
            .csrf(csrf -> csrf.ignoringRequestMatchers("/actuator/**"))
            .headers(headers -> headers
                .contentTypeOptions(contentType -> {})
                .frameOptions(frame -> frame.deny())
                .xssProtection(xss -> {})
                .referrerPolicy(referrer ->
                    referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp ->
                    csp.policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;"))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/prometheus").hasRole("MONITORING_SYSTEM")
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.svg", "/error").permitAll()
                .requestMatchers("/api/admin/upload-excel").hasAnyRole(RoleLists.HR_EDITOR, RoleLists.SYSTEM_ADMIN)
                .requestMatchers("/api/admin/**").hasRole(RoleLists.SYSTEM_ADMIN)
                .anyRequest().authenticated()
            )
            .logout(logout -> {
                if (authMode.logout()) {
                    logout.logoutSuccessUrl(authMode.password() ? "/login?logout" : "/");
                } else {
                    logout.disable();
                }
            });

        if (authMode.password()) {
            http
                .authenticationProvider(passwordProvider(env, roleLists, isLocal))
                .formLogin(form -> form.permitAll());
        }

        if (isLocal) {
            // The pages do not send the CSRF token yet
            http.csrf(csrf -> csrf.disable());
        }

        return http.build();
    }

    /** The password form's accounts: AUTH_PASSWORD_USERS, or in local without it a one-off account. */
    private static AuthenticationProvider passwordProvider(Environment env, RoleLists roleLists, boolean isLocal) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        List<PasswordUsers.Account> accounts =
                new ArrayList<>(PasswordUsers.parse(env.getProperty(AuthMode.PASSWORD_USERS)));
        if (accounts.isEmpty() && isLocal) {
            byte[] random = new byte[12];
            new SecureRandom().nextBytes(random);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            accounts.add(new PasswordUsers.Account(LOCAL_ACCOUNT, encoder.encode(password)));
            log.warn("local profile without AUTH_PASSWORD_USERS: sign in as '{}' with the one-off password {}",
                    LOCAL_ACCOUNT, password);
        }
        List<UserDetails> users = accounts.stream()
                .map(account -> User.withUsername(account.username())
                        .password(account.hash())
                        .authorities(roleLists.authoritiesOf(account.username()))
                        .build())
                .toList();
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(new InMemoryUserDetailsManager(users));
        provider.setPasswordEncoder(encoder);
        return provider;
    }
}
