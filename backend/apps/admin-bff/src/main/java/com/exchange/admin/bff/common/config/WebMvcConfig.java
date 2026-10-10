package com.exchange.admin.bff.common.config;

import com.exchange.admin.bff.common.security.AdminAuthInterceptor;
import com.exchange.admin.bff.common.security.CurrentAdminArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor auth;
    private final CurrentAdminArgumentResolver currentAdmin;

    public WebMvcConfig(AdminAuthInterceptor auth, CurrentAdminArgumentResolver currentAdmin) {
        this.auth = auth;
        this.currentAdmin = currentAdmin;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/admin/**")
                // Login mints the session, so it cannot need one. Logout must succeed on a
                // dead cookie too. Everything else under /admin/auth runs on the PENDING
                // session the interceptor lets through by prefix.
                .excludePathPatterns("/admin/auth/login", "/admin/auth/logout");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentAdmin);
    }
}
