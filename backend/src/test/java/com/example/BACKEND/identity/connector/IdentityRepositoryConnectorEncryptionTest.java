package com.example.BACKEND.identity.connector;

import com.example.BACKEND.identity.IdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityRepositoryConnectorEncryptionTest {

    @Mock
    private JdbcTemplate jdbc;

    private ConnectorCredentialCipher cipher;
    private IdentityRepository repo;

    @BeforeEach
    void setUp() {
        cipher = new ConnectorCredentialCipher(ConnectorCredentialCipher.generateKeyBase64());
        repo = new IdentityRepository(jdbc, cipher);
    }

    @Test
    void upsertConnector_persistsCiphertextNotPlaintext() {
        UUID workspaceId = UUID.randomUUID();
        String plain = "{\"provider\":\"bigquery\",\"projectId\":\"demo\",\"serviceAccountJson\":\"SECRET_JSON\"}";
        UUID existingId = UUID.randomUUID();

        doReturn(1).when(jdbc).update(anyString(), any(), any(), any(), any());
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(workspaceId), eq("BIGQUERY")))
                .thenReturn(List.of(existingId));

        repo.upsertConnector(workspaceId, "BIGQUERY", plain);

        ArgumentCaptor<Object> storedCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("INSERT INTO workspace_connectors"),
                any(), eq(workspaceId), eq("BIGQUERY"), storedCaptor.capture());
        String stored = String.valueOf(storedCaptor.getValue());
        assertTrue(cipher.isSealed(stored));
        assertFalse(stored.contains("SECRET_JSON"));
        assertFalse(stored.contains("demo"));
        assertEquals(plain, cipher.unseal(stored).plaintext());
    }

    @Test
    void findConnectorCredentials_decryptsAndMigratesLegacyPlaintext() {
        UUID workspaceId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        String legacy = "{\"provider\":\"snowflake\",\"password\":\"pw\"}";

        when(jdbc.queryForList(contains("SELECT id, encrypted_credentials"), eq(workspaceId)))
                .thenReturn(List.of(Map.of(
                        "id", rowId.toString(),
                        "encrypted_credentials", legacy
                )));
        doReturn(1).when(jdbc).update(anyString(), any(), eq(rowId));

        Optional<String> result = repo.findConnectorCredentials(workspaceId, null);
        assertTrue(result.isPresent());
        assertEquals(legacy, result.get());

        ArgumentCaptor<Object> sealedCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("UPDATE workspace_connectors"), sealedCaptor.capture(), eq(rowId));
        assertTrue(cipher.isSealed(String.valueOf(sealedCaptor.getValue())));
        assertEquals(legacy, cipher.unseal(String.valueOf(sealedCaptor.getValue())).plaintext());
    }

    @Test
    void findConnectorCredentials_returnsDecryptedSealedRowWithoutRewrite() {
        UUID workspaceId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        String plain = "{\"provider\":\"bigquery\",\"projectId\":\"p\"}";
        String sealed = cipher.seal(plain);

        when(jdbc.queryForList(contains("SELECT id, encrypted_credentials"), eq(workspaceId)))
                .thenReturn(List.of(Map.of(
                        "id", rowId.toString(),
                        "encrypted_credentials", sealed
                )));

        Optional<String> result = repo.findConnectorCredentials(workspaceId);
        assertEquals(Optional.of(plain), result);
    }

    @Test
    void findConnectorCredentials_byWarehouseType_filtersBigQuery() {
        UUID workspaceId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        String plain = "{\"provider\":\"bigquery\",\"projectId\":\"p\"}";
        String sealed = cipher.seal(plain);

        when(jdbc.queryForList(contains("warehouse_type"), eq(workspaceId), eq("BIGQUERY")))
                .thenReturn(List.of(Map.of(
                        "id", rowId.toString(),
                        "encrypted_credentials", sealed
                )));

        Optional<String> result = repo.findConnectorCredentials(workspaceId, "BIGQUERY");
        assertEquals(Optional.of(plain), result);
    }

    @Test
    void upsertConnector_persistsPostgresqlWarehouseTypeAsCiphertext() {
        UUID workspaceId = UUID.randomUUID();
        String plain = "{\"provider\":\"postgresql\",\"host\":\"db.example.com\",\"username\":\"u\",\"password\":\"SUPER_SECRET\"}";
        UUID existingId = UUID.randomUUID();

        doReturn(1).when(jdbc).update(anyString(), any(), any(), any(), any());
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(workspaceId), eq("POSTGRESQL")))
                .thenReturn(List.of(existingId));

        repo.upsertConnector(workspaceId, "POSTGRESQL", plain);

        ArgumentCaptor<Object> storedCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(contains("INSERT INTO workspace_connectors"),
                any(), eq(workspaceId), eq("POSTGRESQL"), storedCaptor.capture());
        String stored = String.valueOf(storedCaptor.getValue());
        assertTrue(cipher.isSealed(stored));
        assertFalse(stored.contains("SUPER_SECRET"));
        assertFalse(stored.contains("db.example.com"));
        assertEquals(plain, cipher.unseal(stored).plaintext());
    }
}
