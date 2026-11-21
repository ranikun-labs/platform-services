package com.ranikunlabs.platform.identity.adapter.jdbc;

import com.ranikunlabs.platform.identity.reconciliation.ReconciliationModel.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/**
 * Offline, package-local conservative comparison of Identity-owned rows. No application wiring,
 * row mutation, namespace move, account merging, identifier remapping or credential normalization.
 * Existing public decision contracts are unchanged. Equality includes all selected values;
 * a difference is a conflict, never permission to overwrite. Source databases must be frozen by
 * the caller: individual repeatable-read sessions do not provide a cross-database atomic snapshot.
 * Refresh rows have no domain in the existing contract: any such rows block readiness rather
 * than being silently omitted. Registration operations and nonzero credential revisions are
 * also unsupported until a migration contract can preserve their replay invariants. Product-owned
 * users/Customer rows are never queried.
 */
final class JdbcIdentitySnapshotReconciler {
    ReconciliationPlanSummary reconcile(Map<SourceIdentity, JdbcSourceConfiguration> configurations,
                                        byte[] correlationKey) throws SQLException {
        Objects.requireNonNull(configurations);
        if (correlationKey == null || correlationKey.length < 32) {
            throw new IllegalArgumentException("correlation key must contain at least 32 bytes");
        }
        byte[] key = correlationKey.clone();
        try {
            List<SourceIdentity> sources = configurations.keySet().stream()
                    .sorted(Comparator.comparing(SourceIdentity::logicalName)).toList();
            if (sources.stream().map(SourceIdentity::logicalName).distinct().count() != sources.size()) {
                throw new IllegalArgumentException("duplicate logical source");
            }
            if (sources.stream().filter(s -> s.kind() == SourceKind.TARGET_IDENTITY).count() != 1) {
                throw new IllegalArgumentException("exactly one target source required");
            }
            EnumMap<Decision, Integer> counts = new EnumMap<>(Decision.class);
            List<ConflictDescriptor> conflicts = new ArrayList<>();
            Map<SourceIdentity, Snapshot> snapshots = new LinkedHashMap<>();
            int unsupported = 0;
            for (SourceIdentity source : sources) {
                JdbcSourceConfiguration configuration = Objects.requireNonNull(configurations.get(source));
                if (!source.logicalName().equals(configuration.logicalName()) || source.kind() != configuration.sourceKind()) {
                    throw new IllegalArgumentException("source identity/configuration mismatch");
                }
                if (source.isExplicitlyExcluded()) { increment(counts, Decision.EXCLUDED_SOURCE); continue; }
                if (!source.isAttested()) {
                    conflict(conflicts, ConflictCode.SOURCE_UNATTESTED, source, "source", key); continue;
                }
                SourceInspectionProfile profile = switch(source.kind()) {
                    case PLATFORM_IDENTITY -> SourceInspectionProfile.platformLegacy();
                    case CARELOG_IDENTITY -> SourceInspectionProfile.carelogLegacy();
                    case TARGET_IDENTITY -> SourceInspectionProfile.canonicalTarget();
                };
                if (!new JdbcSourceInspector().inspect(configuration, profile).isExpected()) {
                    conflict(conflicts, ConflictCode.UNEXPECTED_SCHEMA_HISTORY, source, "structure", key); continue;
                }
                Snapshot snapshot = read(configuration, profile.schema());
                snapshots.put(source, snapshot);
                if (snapshot.hasRefreshRows) unsupported++;
                if (snapshot.hasRegistrationState) unsupported++;
                // The existing Carelog account/public-user UUID invariant cannot be certified by
                // Identity rows alone. Do not query product users or silently bless that mapping.
                if (source.kind() == SourceKind.CARELOG_IDENTITY) unsupported++;
            }
            SourceIdentity targetSource = sources.stream().filter(s -> s.kind() == SourceKind.TARGET_IDENTITY).findFirst().orElseThrow();
            Snapshot target = snapshots.get(targetSource);
            // Never turn an unattested/excluded/structurally invalid target into an empty insert target.
            if (target == null) unsupported++;
            Map<String, Row> candidates = new TreeMap<>();
            for (var entry : snapshots.entrySet()) {
                SourceIdentity source = entry.getKey();
                if (source == targetSource) continue;
                Snapshot snapshot = entry.getValue();
                Set<String> accounts = snapshot.rows.stream().filter(r -> r.domain == Domain.ACCOUNT).map(r -> r.owner).collect(java.util.stream.Collectors.toSet());
                for (Row row : snapshot.rows) {
                    if ((row.domain == Domain.CREDENTIAL || row.domain == Domain.OAUTH) && !accounts.contains(row.owner)) {
                        conflict(conflicts, row.domain == Domain.CREDENTIAL ? ConflictCode.ORPHAN_CREDENTIAL : ConflictCode.ORPHAN_OAUTH_IDENTITY, source, row.key, key); continue;
                    }
                    Row prior = candidates.putIfAbsent(row.key, row);
                    if (prior != null && !prior.values.equals(row.values)) {
                        conflict(conflicts, mismatch(row, prior), source, row.key, key); continue;
                    }
                    if (target == null) continue;
                    Row existing = target.byKey.get(row.key);
                    if (existing == null) increment(counts, Decision.INSERT);
                    else if (existing.values.equals(row.values)) increment(counts, Decision.IDENTICAL);
                    else conflict(conflicts, mismatch(row, existing), source, row.key, key);
                }
            }
            if (target != null) for (Row row : target.rows) {
                if (!candidates.containsKey(row.key)) conflict(conflicts, ConflictCode.TARGET_ROW_UNEXPECTED, targetSource, row.key, key);
            }
            counts.put(Decision.CONFLICT, conflicts.size());
            return new ReconciliationPlanSummary(sources, counts, unsupported, conflicts);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    private Snapshot read(JdbcSourceConfiguration configuration, String schema) throws SQLException {
        // Schema names originate only from the three existing fixed inspection profiles.
        if (!Set.of("public", "identity").contains(schema)) throw new IllegalArgumentException("unsupported schema");
        try (ReadOnlyJdbcConnection session = ReadOnlyJdbcConnection.open(configuration)) {
            Connection connection = session.connection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            }
            List<Row> rows = new ArrayList<>();
            query(connection, schema, "platform_accounts", "id,status,primary_email,created_at,updated_at", Domain.ACCOUNT, 0, -1, 0, rows);
            query(connection, schema, "password_credentials", "account_id,login_id,password_hash,created_at,updated_at", Domain.CREDENTIAL, 1, -1, 0, rows);
            query(connection, schema, "external_identities", "account_id,provider,provider_subject,email_snapshot,created_at,updated_at", Domain.OAUTH, 1, 2, 0, rows);
            query(connection, schema, "product_clients", "client_id,product,channel,enabled,created_at,updated_at", Domain.PRODUCT_CLIENT, 0, -1, -1, rows);
            boolean hasRefresh;
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT EXISTS(SELECT 1 FROM " + schema + ".refresh_token)")) {
                result.next(); hasRefresh = result.getBoolean(1);
            }
            boolean hasRegistrationState = false;
            if (schema.equals("identity")) {
                try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                        "SELECT EXISTS(SELECT 1 FROM identity.carelog_registration_operation) "
                                + "OR EXISTS(SELECT 1 FROM identity.password_credentials WHERE credential_revision <> 0)")) {
                    result.next(); hasRegistrationState = result.getBoolean(1);
                }
            }
            rows.sort(Comparator.comparing(r -> r.key));
            return new Snapshot(rows, hasRefresh, hasRegistrationState);
        } catch (SQLException failure) {
            throw new SourceInspectionException(configuration.logicalName(), "read identity snapshot", failure);
        }
    }

    private void query(Connection connection, String schema, String table, String columns, Domain domain,
                       int keyIndex, int secondKeyIndex, int ownerIndex, List<Row> rows) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT " + columns + " FROM " + schema + "." + table)) {
            while (result.next()) {
                List<Object> values = new ArrayList<>();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) values.add(result.getObject(column));
                // Length-prefix components so provider/subject boundaries cannot collide.
                String first = String.valueOf(values.get(keyIndex));
                String identity = first.length() + ":" + first;
                if (secondKeyIndex >= 0) { String second = String.valueOf(values.get(secondKeyIndex)); identity += second.length() + ":" + second; }
                String owner = ownerIndex >= 0 ? String.valueOf(values.get(ownerIndex)) : "";
                rows.add(new Row(domain, domain.name() + ":" + identity, owner, values));
            }
        }
    }

    private static ConflictCode mismatch(Row row, Row existing) {
        return switch(row.domain) {
            case ACCOUNT -> ConflictCode.ACCOUNT_SEMANTIC_MISMATCH;
            case CREDENTIAL -> row.owner.equals(existing.owner) ? ConflictCode.CREDENTIAL_MISMATCH : ConflictCode.DUPLICATE_LOGIN_OWNERSHIP;
            case OAUTH -> ConflictCode.OAUTH_OWNERSHIP_COLLISION;
            case PRODUCT_CLIENT -> ConflictCode.UNEXPECTED_PRODUCT_CLIENT;
            default -> throw new IllegalArgumentException("unsupported row domain");
        };
    }
    private static void increment(Map<Decision,Integer> counts, Decision decision) { counts.merge(decision, 1, Integer::sum); }
    private static void conflict(List<ConflictDescriptor> conflicts, ConflictCode code, SourceIdentity source, String correlation, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            OpaqueIdentifier opaque = new OpaqueIdentifier(HexFormat.of().formatHex(mac.doFinal(correlation.getBytes(StandardCharsets.UTF_8))));
            conflicts.add(new ConflictDescriptor(code, code.domain(), List.of(source), List.of(new CandidateIdentity(code.domain(), opaque, source)), List.of()));
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("correlation unavailable"); }
    }
    private static final class Row {
        final Domain domain; final String key; final String owner; final List<Object> values;
        Row(Domain domain, String key, String owner, List<Object> values) { this.domain=domain; this.key=key; this.owner=owner; this.values=Collections.unmodifiableList(new ArrayList<>(values)); }
        @Override public String toString() { return "IdentityRow[redacted]"; }
    }
    private static final class Snapshot {
        final List<Row> rows; final Map<String,Row> byKey; final boolean hasRefreshRows; final boolean hasRegistrationState;
        Snapshot(List<Row> rows, boolean hasRefreshRows, boolean hasRegistrationState) { this.rows=List.copyOf(rows); this.hasRefreshRows=hasRefreshRows; this.hasRegistrationState=hasRegistrationState; Map<String,Row> indexed=new TreeMap<>(); for(Row row:rows) if(indexed.put(row.key,row)!=null) throw new IllegalStateException("duplicate identity key"); byKey=Collections.unmodifiableMap(indexed); }
        @Override public String toString() { return "IdentitySnapshot[redacted]"; }
    }
}
