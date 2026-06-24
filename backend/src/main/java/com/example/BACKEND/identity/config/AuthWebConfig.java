package com.example.BACKEND.identity.config;

import com.example.BACKEND.identity.auth.AuthInterceptor;
import com.example.BACKEND.identity.auth.ForbiddenException;
import com.example.BACKEND.identity.auth.UnauthorizedException;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public AuthWebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }

    @RestControllerAdvice
    static class AuthExceptionHandler {
        @ExceptionHandler(UnauthorizedException.class)
        public ResponseEntity<Map<String, String>> unauthorized(UnauthorizedException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
        }

        @ExceptionHandler(ForbiddenException.class)
        public ResponseEntity<Map<String, String>> forbidden(ForbiddenException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
        }
    }
}
