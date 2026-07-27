package com.nexcompute.management.config;

import com.nexcompute.management.audit.AuditInterceptor;
import com.nexcompute.management.security.PermissionAuthorizationInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：CORS、权限拦截器、审计拦截器
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final NexcomputeProperties properties;
    private final PermissionAuthorizationInterceptor permissionInterceptor;
    private final AuditInterceptor auditInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = properties.getCors().getAllowedOrigins().toArray(new String[0]);
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/agent/**", "/ping", "/actuator/**", "/error");
        // 审计拦截器：兜底非 GET 写操作（@Audited 让路去重）。排除鉴权/受控端/actuator 等非业务路径。
        registry.addInterceptor(auditInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/agent/**", "/ping", "/actuator/**", "/error");
    }
}
