package com.ranikunlabs.platform.identity.adapter.jdbc;

import com.ranikunlabs.platform.identity.reconciliation.ReconciliationModel.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class JdbcIdentitySnapshotReconcilerIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String A="11111111-1111-1111-1111-111111111111", B="22222222-2222-2222-2222-222222222222";
    private static final String TS="'2026-01-01T00:00:00Z'";
    private static final byte[] KEY = new byte[32]; // synthetic correlation key only
    private static final SourceIdentity SOURCE = new SourceIdentity("legacy",SourceKind.PLATFORM_IDENTITY,SourceAttestation.IN_SCOPE);
    private static final SourceIdentity TARGET = new SourceIdentity("target",SourceKind.TARGET_IDENTITY,SourceAttestation.IN_SCOPE);
    private final JdbcIdentitySnapshotReconciler reconciler = new JdbcIdentitySnapshotReconciler();
    @BeforeAll static void start() throws SQLException {
        POSTGRES.start();
        try(var c=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());var s=c.createStatement()) { s.execute("CREATE DATABASE snapshot_source"); s.execute("CREATE DATABASE snapshot_target"); }
    }
    @AfterAll static void stop(){ POSTGRES.stop(); }
    @BeforeEach void reset() throws SQLException {
        for(boolean target:new boolean[]{false,true}) {
            String schema=target?"identity":"public";
            sql(target,"DROP SCHEMA IF EXISTS "+schema+" CASCADE");
            sql(target,"CREATE SCHEMA "+schema);
            Flyway.configure().dataSource(url(target),POSTGRES.getUsername(),POSTGRES.getPassword()).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
            sql(target,"UPDATE "+schema+".product_clients SET created_at="+TS+",updated_at="+TS);
        }
    }
    @Test void identicalSnapshotsAreDeterministicReadOnlyAndNonDisclosing() throws SQLException {
        for(boolean target:new boolean[]{false,true}) { account(target,A); credential(target,A,"synthetic-login","synthetic-hash"); oauth(target,A,"synthetic-subject"); }
        String sourceBefore=dump(false),targetBefore=dump(true);
        var first=plan(); Map<SourceIdentity,JdbcSourceConfiguration> reverse=new LinkedHashMap<>(); reverse.put(TARGET,config(TARGET)); reverse.put(SOURCE,config(SOURCE));
        assertThat(reconciler.reconcile(reverse,KEY)).isEqualTo(first);
        assertThat(first.readiness().isReady()).isTrue();
        assertThat(first.decisionCount(Decision.IDENTICAL)).isEqualTo(5);
        assertThat(dump(false)).isEqualTo(sourceBefore);assertThat(dump(true)).isEqualTo(targetBefore);
        assertThat(first.toString()).doesNotContain("synthetic-login","synthetic-hash","synthetic-subject",A);
    }
    @Test void identicalRowsFromTwoSourcesAreCountedPerSourceCandidate() throws SQLException {
        for(boolean target:new boolean[]{false,true}) { account(target,A); credential(target,A,"synthetic-login","synthetic-hash"); oauth(target,A,"synthetic-subject"); }
        SourceIdentity secondSource = new SourceIdentity("legacy-copy",SourceKind.PLATFORM_IDENTITY,SourceAttestation.IN_SCOPE);
        Map<SourceIdentity,JdbcSourceConfiguration> configurations = new LinkedHashMap<>();
        configurations.put(SOURCE,config(SOURCE));
        // The two logical sources deliberately read the same synthetic snapshot.
        configurations.put(secondSource,config(secondSource));
        configurations.put(TARGET,config(TARGET));

        var result = reconciler.reconcile(configurations,KEY);
        assertThat(result.readiness().isReady()).isTrue();
        assertThat(result.decisionCount(Decision.IDENTICAL)).isEqualTo(10);
        assertThat(result.decisionCount(Decision.INSERT)).isZero();
        assertThat(result.decisionCount(Decision.CONFLICT)).isZero();
    }
    @Test void absentAccountAndCredentialAreProposedWithoutMutationOrUuidRemapping() throws SQLException {
        account(false,A);credential(false,A,"synthetic-login","synthetic-hash");
        String before=dump(true);var result=plan();
        assertThat(result.readiness().isReady()).isTrue(); assertThat(result.decisionCount(Decision.INSERT)).isEqualTo(2);
        assertThat(dump(true)).isEqualTo(before);assertThat(dump(false)).contains(A);
    }
    @Test void credentialBytesAndAccountDifferencesFailClosedWithOpaqueEvidence() throws SQLException {
        for(boolean target:new boolean[]{false,true}) { account(target,A);credential(target,A,"synthetic-login",target?"different-hash":"synthetic-hash"); }
        sql(true,"UPDATE identity.platform_accounts SET status='INACTIVE'");
        var result=plan();assertThat(codes(result)).contains(ConflictCode.CREDENTIAL_MISMATCH,ConflictCode.ACCOUNT_SEMANTIC_MISMATCH);
        assertThat(result.readiness().isReady()).isFalse();assertThat(result.toString()).doesNotContain("synthetic-login","different-hash","synthetic-hash",A);
        for(var conflict:result.unresolvedConflicts()) for(var candidate:conflict.candidates()) assertThat(candidate.correlationId().sanitizedValue()).matches("[0-9a-f]{64}");
    }
    @Test void duplicateLoginAndOauthOwnershipAreNeverMergedByEmail() throws SQLException {
        account(false,A);account(true,B);credential(false,A,"synthetic-login","same-hash");credential(true,B,"synthetic-login","same-hash");oauth(false,A,"synthetic-subject");oauth(true,B,"synthetic-subject");
        var result=plan();assertThat(codes(result)).contains(ConflictCode.DUPLICATE_LOGIN_OWNERSHIP,ConflictCode.OAUTH_OWNERSHIP_COLLISION,ConflictCode.TARGET_ROW_UNEXPECTED);
        assertThat(result.readiness().isReady()).isFalse();assertThat(dump(false)).contains(A);assertThat(dump(true)).contains(B);
    }
    @Test void unknownHistoryUnattestedAndExcludedSourcesDoNotBecomeInsertTargets() throws SQLException {
        sql(false,"UPDATE public.flyway_schema_history SET checksum=0 WHERE version='2'");
        assertThat(codes(plan())).contains(ConflictCode.UNEXPECTED_SCHEMA_HISTORY);
        var unattested=new SourceIdentity("unattested",SourceKind.PLATFORM_IDENTITY,SourceAttestation.UNATTESTED);
        var excluded=new SourceIdentity("excluded",SourceKind.PLATFORM_IDENTITY,SourceAttestation.DISPOSABLE);
        Map<SourceIdentity,JdbcSourceConfiguration> configs=new LinkedHashMap<>();configs.put(TARGET,config(TARGET));configs.put(unattested,unreachable(unattested));configs.put(excluded,unreachable(excluded));
        var result=reconciler.reconcile(configs,KEY);assertThat(codes(result)).contains(ConflictCode.SOURCE_UNATTESTED);assertThat(result.decisionCount(Decision.EXCLUDED_SOURCE)).isEqualTo(1);assertThat(result.readiness().isReady()).isFalse();
        var excludedTarget=new SourceIdentity("target",SourceKind.TARGET_IDENTITY,SourceAttestation.DISPOSABLE);
        result=reconciler.reconcile(Map.of(SOURCE,config(SOURCE),excludedTarget,unreachable(excludedTarget)),KEY);
        assertThat(result.unsupportedSourceStateCount()).isGreaterThan(0);assertThat(result.decisionCount(Decision.INSERT)).isZero();
    }
    @Test void refreshRowsBlockUnsupportedSessionPlanningAndRemainByteUnchanged() throws SQLException {
        for(boolean target:new boolean[]{false,true}) account(target,A);
        sql(false,"INSERT INTO public.refresh_token(refresh_token,account_id,user_id,token_expires_at,created_at,updated_at) VALUES('synthetic-opaque-token','"+A+"','legacy-mirror','2030-01-01',"+TS+","+TS+")");
        String before=dump(false);var result=plan();assertThat(result.unsupportedSourceStateCount()).isEqualTo(1);assertThat(result.readiness().isReady()).isFalse();assertThat(dump(false)).isEqualTo(before);assertThat(result.toString()).doesNotContain("synthetic-opaque-token","legacy-mirror");
    }
    @Test void carelogHistoryDoesNotSilentlyCertifyProductUserUuidMapping() throws SQLException {
        sql(false,"CREATE TABLE public.users(id uuid)");
        sql(false,"DELETE FROM public.flyway_schema_history");
        try(var c=DriverManager.getConnection(url(false),POSTGRES.getUsername(),POSTGRES.getPassword());
            var statement=c.prepareStatement("INSERT INTO public.flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES(?,?,?,?,?,?,?,0,true)")) {
            int rank=0;
            for(var migration:HistoryProfile.carelogLegacy().expectedMigrations()) {
                statement.setInt(1,++rank);statement.setString(2,migration.version());statement.setString(3,migration.description());statement.setString(4,migration.type());statement.setString(5,migration.script());statement.setInt(6,migration.checksum());statement.setString(7,"synthetic");statement.executeUpdate();
            }
        }
        var carelog=new SourceIdentity("legacy",SourceKind.CARELOG_IDENTITY,SourceAttestation.IN_SCOPE);
        var configuration=new JdbcSourceConfiguration(carelog.logicalName(),carelog.kind(),url(false),POSTGRES.getUsername(),POSTGRES.getPassword().toCharArray());
        assertThat(new JdbcSourceInspector().inspect(configuration,SourceInspectionProfile.carelogLegacy()).isExpected()).isTrue();
        var result=reconciler.reconcile(Map.of(carelog,configuration,TARGET,config(TARGET)),KEY);
        assertThat(result.unresolvedConflicts()).isEmpty();assertThat(result.unsupportedSourceStateCount()).isEqualTo(1);assertThat(result.readiness().isReady()).isFalse();
    }
    private ReconciliationPlanSummary plan() throws SQLException { return reconciler.reconcile(Map.of(SOURCE,config(SOURCE),TARGET,config(TARGET)),KEY); }
    private Set<ConflictCode> codes(ReconciliationPlanSummary result){var codes=EnumSet.noneOf(ConflictCode.class);result.unresolvedConflicts().forEach(c->codes.add(c.code()));return codes;}
    private static String url(boolean target){return "jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/snapshot_"+(target?"target":"source");}
    private static JdbcSourceConfiguration config(SourceIdentity source){return new JdbcSourceConfiguration(source.logicalName(),source.kind(),url(source.kind()==SourceKind.TARGET_IDENTITY),POSTGRES.getUsername(),POSTGRES.getPassword().toCharArray());}
    private static JdbcSourceConfiguration unreachable(SourceIdentity source){return new JdbcSourceConfiguration(source.logicalName(),source.kind(),"jdbc:postgresql://127.0.0.1:1/not_connected","synthetic",new char[0]);}
    private static void sql(boolean target,String sql)throws SQLException {try(var c=DriverManager.getConnection(url(target),POSTGRES.getUsername(),POSTGRES.getPassword());var s=c.createStatement()){s.execute(sql);}}
    private static void account(boolean target,String id)throws SQLException{String schema=target?"identity":"public";sql(target,"INSERT INTO "+schema+".platform_accounts VALUES('"+id+"','ACTIVE','same-synthetic-email',"+TS+","+TS+")");}
    private static void credential(boolean target,String id,String login,String hash)throws SQLException{String schema=target?"identity":"public";sql(target,"INSERT INTO "+schema+".password_credentials VALUES('"+id+"','"+login+"','"+hash+"',"+TS+","+TS+")");}
    private static void oauth(boolean target,String id,String subject)throws SQLException{String schema=target?"identity":"public";sql(target,"INSERT INTO "+schema+".external_identities(account_id,provider,provider_subject,email_snapshot,created_at,updated_at) VALUES('"+id+"','SYNTHETIC','"+subject+"','same-synthetic-email',"+TS+","+TS+")");}
    private static String dump(boolean target)throws SQLException{String schema=target?"identity":"public";StringBuilder out=new StringBuilder();try(var c=DriverManager.getConnection(url(target),POSTGRES.getUsername(),POSTGRES.getPassword());var s=c.createStatement()){for(String table:List.of("platform_accounts","password_credentials","external_identities","product_clients","refresh_token","flyway_schema_history")){try(var r=s.executeQuery("SELECT row_to_json(t)::text FROM "+schema+"."+table+" t ORDER BY row_to_json(t)::text")){while(r.next())out.append(r.getString(1));}}}return out.toString();}
}
