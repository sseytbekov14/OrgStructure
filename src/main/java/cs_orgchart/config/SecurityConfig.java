package cs_orgchart.config;

import cs_orgchart.security.AuthMode;
import cs_orgchart.security.PasswordUsers;
import cs_orgchart.security.RoleLists;
import cs_orgchart.security.SignInEntryPoint;
import cs_orgchart.security.SsoSignIn;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The one filter chain. How people get in is {@link AuthMode}: Microsoft Entra ID over SAML, or with SSO off
 * the open site (no sign-in, as before SSO) - or, only when asked for, the password form (accounts of
 * AUTH_PASSWORD_USERS). Every profile has the same rules; local only differs in its one-off account when the
 * password form is on without AUTH_PASSWORD_USERS.
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    static final String LOCAL_ACCOUNT = "dev";

    private static final String CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;"
            + " object-src 'none'; base-uri 'self'; frame-ancestors 'none'; script-src 'self'";
    /** The site's own pages: scripts from its own files only, so an injected script never runs */
    static final String SCRIPTS_SELF_ONLY = CSP + ";";
    /** The pages Spring writes itself, with an inline script: the password form, the SAML POST binding */
    static final String SCRIPTS_INLINE = CSP + " 'unsafe-inline';";
    private static final RequestMatcher SPRING_PAGES = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher("/login"),
            PathPatternRequestMatcher.withDefaults().matcher(SamlConfig.SIGN_ON_PATH));

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, Environment env, AuthMode authMode,
                                           RoleLists roleLists, ObjectProvider<SsoSignIn> ssoSignIn) throws Exception {
        boolean isLocal = env.acceptsProfiles(Profiles.of("local"));
        RequestCache requestCache = requestCache();

        http
            .csrf(csrf -> csrf
                // The token lives in the XSRF-TOKEN cookie (readable by the pages' scripts); every write sends it
                // back as X-XSRF-TOKEN (js/common.js apiFetch) or, for the password form, as the _csrf field
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(eagerCsrfToken()))
            .headers(headers -> headers
                .contentTypeOptions(contentType -> {})
                .frameOptions(frame -> frame.deny())
                .xssProtection(xss -> {})
                .referrerPolicy(referrer ->
                    referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .addHeaderWriter(contentSecurityPolicy(SCRIPTS_SELF_ONLY, new NegatedRequestMatcher(SPRING_PAGES)))
                .addHeaderWriter(contentSecurityPolicy(SCRIPTS_INLINE, SPRING_PAGES))
            )
            .authorizeHttpRequests(auth -> {
                auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.svg", "/error").permitAll()
                .requestMatchers(SsoSignIn.ACCESS_NOT_GRANTED_PATH, SsoSignIn.SIGN_IN_FAILED_PATH,
                    SsoSignIn.SIGNED_OUT_PATH).permitAll()
                .requestMatchers("/api/admin/**").hasRole(RoleLists.SYSTEM_ADMIN);
                if (authMode.open()) {
                    // Open site (SSO off, no password form): everyone, as before SSO
                    auth.anyRequest().permitAll();
                } else {
                    auth.anyRequest().authenticated();
                }
            })
            .logout(logout -> {
                if (authMode.logout()) {
                    // With SSO only OrgChart's session ends; the Microsoft one stays (no single logout)
                    logout.logoutSuccessUrl(authMode.sso() ? SsoSignIn.SIGNED_OUT_PATH : "/login?logout");
                } else {
                    logout.disable();
                }
            })
            .requestCache(cache -> cache.requestCache(requestCache))
            // Registered as the first default (not as THE entry point), so it wins over the ones of the sign-in
            // configurers and Spring still generates the /login page of the password form
            .exceptionHandling(exceptions -> exceptions
                .defaultAuthenticationEntryPointFor(
                    new SignInEntryPoint(authMode.sso() ? SamlConfig.SIGN_ON_PATH : "/login"),
                    AnyRequestMatcher.INSTANCE));

        if (authMode.sso()) {
            SsoSignIn sso = ssoSignIn.getObject();
            http
                // The one request without OrgChart's token: Microsoft's POST of the answer to the Assertion
                // Consumer Service (it is signed and answers a request this session made). Nothing else.
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, SamlConfig.ACS_PATH)))
                .saml2Login(saml -> saml
                    // No page listing identity providers: the Sign on URL goes straight to Microsoft
                    .loginPage(SamlConfig.SIGN_ON_PATH)
                    .authenticationManager(new ProviderManager(sso.provider()))
                    .successHandler(sso.successHandler(requestCache))
                    .failureHandler(sso.failureHandler()))
                // The service provider metadata for the Entra application: /saml2/metadata/entra
                .saml2Metadata(Customizer.withDefaults());
        } else if (authMode.password()) {
            http
                .authenticationProvider(passwordProvider(env, roleLists, isLocal))
                .formLogin(form -> form.permitAll());
        } else {
            log.warn("AUTH_SSO_ENABLED=false: OrgChart is open to everyone who reaches the site, without sign-in");
        }

        return http.build();
    }

    /**
     * The token is made on every request, so the XSRF-TOKEN cookie is there before the first write of a page
     * (Spring otherwise makes it only when a page asks for it, and these pages are static HTML). The plain
     * handler: the cookie value itself is the header value.
     */
    private static CsrfTokenRequestAttributeHandler eagerCsrfToken() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    private static HeaderWriter contentSecurityPolicy(String policy, RequestMatcher requests) {
        return new DelegatingRequestMatcherHeaderWriter(requests, new ContentSecurityPolicyHeaderWriter(policy));
    }

    /**
     * The page asked for without a sign-in, opened after it. Pages only: an /api/** call (a script's request)
     * is never the place to come back to.
     */
    private static RequestCache requestCache() {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(new AndRequestMatcher(
                path.matcher(HttpMethod.GET, "/**"),
                new NegatedRequestMatcher(path.matcher("/api/**")),
                new NegatedRequestMatcher(path.matcher("/error")),
                new NegatedRequestMatcher(path.matcher("/favicon.*")),
                new NegatedRequestMatcher(path.matcher("/.well-known/**"))));
        return cache;
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
