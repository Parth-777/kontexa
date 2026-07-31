package com.example.BACKEND.tenancy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MembershipRepositoryInvariantTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void listByTenantRejectsNullTenantIdWithoutQuerying() {
        MembershipRepository repo = new MembershipRepository(jdbc);
        assertThrows(IllegalArgumentException.class, () -> repo.listByTenant(null));
        verify(jdbc, never()).query(
                ArgumentMatchers.anyString(),
                ArgumentMatchers.<RowMapper<?>>any(),
                ArgumentMatchers.any());
    }

    @Test
    void findByTenantAndUserRejectsNullTenantId() {
        MembershipRepository repo = new MembershipRepository(jdbc);
        assertThrows(IllegalArgumentException.class,
                () -> repo.findByTenantAndUser(null, UUID.randomUUID()));
    }
}
