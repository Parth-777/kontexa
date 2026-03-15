package com.example.BACKEND.user;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class UserAccessController {

    private final UserAccessService userAccessService;

    public UserAccessController(UserAccessService userAccessService) {
        this.userAccessService = userAccessService;
    }

    @PostMapping("/api/auth/user/login")
    public ResponseEntity<?> userLogin(@RequestBody Map<String, String> body) {
        String userId = body.get("userId");
        String password = body.get("password");
        if (userId == null || userId.isBlank() || password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId and password are required"));
        }

        return userAccessService.authenticate(userId.trim(), password.trim())
                .<ResponseEntity<?>>map(result -> ResponseEntity.ok(Map.of(
                        "accountType", "USER",
                        "userId", result.userId(),
                        "tenantId", result.tenantId(),
                        "position", result.position(),
                        "tenantSchema", result.tenantSchema()
                )))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid userId or password")));
    }

    @GetMapping("/api/tenant/users")
    public ResponseEntity<?> listUsers(@RequestParam String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        List<Map<String, Object>> users = userAccessService.listUsers(tenantId.trim());
        return ResponseEntity.ok(Map.of("users", users));
    }

    @PostMapping("/api/tenant/users")
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> body) {
        String tenantId = asString(body.get("tenantId"));
        String userId = asString(body.get("userId"));
        String password = asString(body.get("password"));
        String position = asString(body.get("position"));
        boolean active = body.get("active") == null || Boolean.parseBoolean(String.valueOf(body.get("active")));
        if (tenantId == null || userId == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId, userId, password are required"));
        }
        userAccessService.createUser(tenantId, userId, password, position == null ? "Viewer" : position, active);
        return ResponseEntity.ok(Map.of("message", "User created"));
    }

    @PutMapping("/api/tenant/users/{userId}/role")
    public ResponseEntity<?> updateRole(@PathVariable String userId, @RequestBody Map<String, String> body) {
        String tenantId = body.get("tenantId");
        String position = body.get("position");
        if (tenantId == null || tenantId.isBlank() || position == null || position.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId and position are required"));
        }
        try {
            userAccessService.updateRole(tenantId.trim(), userId.trim(), position.trim());
            return ResponseEntity.ok(Map.of("message", "Role updated"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/api/tenant/users/{userId}/status")
    public ResponseEntity<?> updateStatus(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        String tenantId = asString(body.get("tenantId"));
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        boolean active = Boolean.parseBoolean(String.valueOf(body.get("active")));
        try {
            userAccessService.updateStatus(tenantId, userId.trim(), active);
            return ResponseEntity.ok(Map.of("message", "Status updated"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping("/api/tenant/users/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable String userId, @RequestParam String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        try {
            userAccessService.deleteUser(tenantId.trim(), userId.trim());
            return ResponseEntity.ok(Map.of("message", "User deleted"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/api/user/tables")
    public ResponseEntity<?> listTables(@RequestParam String schema) {
        if (schema == null || schema.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "schema is required"));
        }
        return ResponseEntity.ok(Map.of("tables", userAccessService.listTablesForSchema(schema.trim())));
    }

    private String asString(Object value) {
        if (value == null) return null;
        String out = String.valueOf(value).trim();
        return out.isEmpty() ? null : out;
    }
}
