package cs_orgchart.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final CacheControl NO_CACHE = CacheControl.noStore().mustRevalidate();

    @org.springframework.beans.factory.annotation.Value("${app.data.photos-path}")
    private String photosPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/favicon.svg")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(NO_CACHE);

        registry.addResourceHandler("/index.html", "/function.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(NO_CACHE);

        String path = photosPath.replace("\\", "/");
        if (!path.endsWith("/")) {
            path += "/";
        }
        String location = path.startsWith("file:") ? path : "file:///" + path.replaceFirst("^/+", "");
        
        registry.addResourceHandler("/photos/**")
                .addResourceLocations(location)
                .setCacheControl(NO_CACHE);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                applyNoCacheHeaders(response);
                return true;
            }
        }).addPathPatterns("/", "/index.html", "/function.html", "/favicon.svg");
    }

    private static void applyNoCacheHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
    }
}
