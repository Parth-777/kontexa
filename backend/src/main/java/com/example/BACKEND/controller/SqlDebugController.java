package com.example.BACKEND.controller;

import com.example.BACKEND.identity.auth.AuthorizationService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Local-only SQL debug. Not registered outside the {@code local} profile.
 * When active, requires OWNER/ADMIN.
 */
@Profile("local")
@RestController
@RequestMapping("/api/debug")
public class SqlDebugController {

    private final JdbcTemplate jdbcTemplate;
    private final AuthorizationService authorization;

    public SqlDebugController(JdbcTemplate jdbcTemplate, AuthorizationService authorization) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorization = authorization;
    }

    @PostMapping("/run")
    public Object run(@RequestBody Map<String, String> body) {
        authorization.requireAdmin();
        return jdbcTemplate.queryForList(body.get("sql"));
    }
}
