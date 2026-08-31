package cs_orgchart.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private Environment env;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        boolean isLocal = Arrays.asList(env.getActiveProfiles()).contains("local");

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
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .maximumSessions(1)
            );

        if (isLocal) {
            // Отключаем авторизацию для локального профиля по просьбе пользователя
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        } else {
            // Настройки авторизации для боевого (PROD) профиля
            http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/prometheus").hasRole("MONITORING_SYSTEM")
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/photos/**").permitAll()
                .requestMatchers("/api/admin/upload-excel").hasAnyRole("HR_EDITOR", "SYSTEM_ADMIN")
                .requestMatchers("/api/admin/**").hasRole("SYSTEM_ADMIN")
                .requestMatchers("/api/structure/**", "/org-chart/**", "/org-chart").hasAnyRole("USER", "HR_EDITOR", "SYSTEM_ADMIN")
                .anyRequest().authenticated()
            );

            // Prod/Stage profile: Corporate SSO (Azure AD / IdP)
            http.oauth2Login(oauth2 -> oauth2
                .defaultSuccessUrl("/org-chart", true)
            );
        }

        return http.build();
    }

    @Bean
    @Profile("local")
    public InMemoryUserDetailsManager userDetailsService() {
        UserDetails user = User.withUsername("user")
            .password("{noop}password")
            .roles("USER")
            .build();
        UserDetails admin = User.withUsername("admin")
            .password("{noop}password")
            .roles("SYSTEM_ADMIN", "USER", "HR_EDITOR")
            .build();
        return new InMemoryUserDetailsManager(user, admin);
    }
}
