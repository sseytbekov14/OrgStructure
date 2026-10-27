package cs_orgchart.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * What a request without a sign-in gets. /api/** (the pages' scripts and the SSE stream /api/org/stream) answers
 * 401 with a JSON message: a redirect to Microsoft would be useless to a script, and js/common.js reloads the
 * page instead. A page goes to the sign-in (Microsoft with SSO, the form otherwise) and is opened after it.
 */
public class SignInEntryPoint implements AuthenticationEntryPoint {

    public static final String CODE = "SESSION_EXPIRED";
    public static final String MESSAGE = "Session expired, reload the page";
    static final String BODY = "{\"code\":\"" + CODE + "\",\"message\":\"" + MESSAGE + "\"}";

    private final AuthenticationEntryPoint pages;

    public SignInEntryPoint(String signInUrl) {
        this.pages = new LoginUrlAuthenticationEntryPoint(signInUrl);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException, ServletException {
        if (!isApi(request)) {
            pages.commence(request, response, exception);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }

    public static boolean isApi(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/");
    }
}
