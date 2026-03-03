package com.example.BACKEND.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/debug")
public class SqlDebugController {

    private final JdbcTemplate jdbcTemplate;

    public SqlDebugController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/run")
    public Object run(@RequestBody Map<String, String> body) {
        return jdbcTemplate.queryForList(body.get("sql"));
    }
}
