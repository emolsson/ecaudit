//**********************************************************************
// Copyright 2018 Telefonaktiebolaget LM Ericsson
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//**********************************************************************
package com.ericsson.bss.cassandra.ecaudit.integration;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SimpleStatement;
import com.ericsson.bss.cassandra.ecaudit.logger.Slf4jAuditLogger;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@RunWith(MockitoJUnitRunner.class)
public class ITDataAudit
{
    private static CassandraDaemonForAuditTest cdt;
    private static Cluster superCluster;
    private static Session superSession;

    private static String unmodifiedUsername;
    private static Cluster unmodifiedCluster;
    private static Session unmodifiedSession;

    private static AtomicInteger usernameNumber = new AtomicInteger();

    @Mock
    private Appender<ILoggingEvent> mockAuditAppender;

    @BeforeClass
    public static void beforeClass() throws Exception
    {
        cdt = CassandraDaemonForAuditTest.getInstance();

        try (Cluster cassandraCluster = cdt.createCluster();
             Session cassandraSession = cassandraCluster.connect())
        {
            cassandraSession.execute(new SimpleStatement(
            "CREATE ROLE superdata WITH PASSWORD = 'secret' AND LOGIN = true AND SUPERUSER = true"));
            cassandraSession.execute(new SimpleStatement(
            "ALTER ROLE superdata WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'roles'}"));
            cassandraSession.execute(new SimpleStatement(
            "ALTER ROLE superdata WITH OPTIONS = { 'grant_audit_whitelist_for_all' : 'data'}"));
        }

        superCluster = cdt.createCluster("superdata", "secret");
        superSession = superCluster.connect();

        unmodifiedUsername = givenSuperuserWithMinimalWhitelist();
        unmodifiedCluster = cdt.createCluster(unmodifiedUsername, "secret");
        unmodifiedSession = unmodifiedCluster.connect();
    }

    @Before
    public void before()
    {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(Slf4jAuditLogger.AUDIT_LOGGER_NAME).addAppender(mockAuditAppender);
    }

    @After
    public void after()
    {
        verifyNoMoreInteractions(mockAuditAppender);
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger(Slf4jAuditLogger.AUDIT_LOGGER_NAME).detachAppender(mockAuditAppender);
    }

    @AfterClass
    public static void afterClass()
    {
        unmodifiedSession.close();
        unmodifiedCluster.close();

        for (int i = 0; i < usernameNumber.get(); i++)
        {
            superSession.execute(new SimpleStatement("DROP ROLE IF EXISTS datarole" + i));
        }
        superSession.close();
        superCluster.close();

        try (Cluster cassandraCluster = cdt.createCluster();
             Session cassandraSession = cassandraCluster.connect())
        {
            cassandraSession.execute(new SimpleStatement("DROP ROLE IF EXISTS superdata"));
        }
    }

    @Test
    public void preparedSelectIsLogged()
    {
        givenTable("dataks", "tbl");
        String username = unmodifiedUsername;

        PreparedStatement preparedStatement = unmodifiedSession.prepare("SELECT * FROM dataks.tbl WHERE key = ?");
        unmodifiedSession.execute(preparedStatement.bind(5));

        thenAuditLogContainEntryForUser("SELECT * FROM dataks.tbl WHERE key = ?[5]", username);
    }

    @Test
    public void preparedSelectIsWhitelisted()
    {
        givenTable("dataks", "tbl");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "select", "data/dataks/tbl");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            PreparedStatement preparedStatement = privateSession.prepare("SELECT * FROM dataks.tbl WHERE key = ?");
            privateSession.execute(preparedStatement.bind(5));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleSelectIsLogged()
    {
        givenTable("dataks", "tbl");
        String username = unmodifiedUsername;

        unmodifiedSession.execute("SELECT * FROM dataks.tbl WHERE key = 12");

        thenAuditLogContainEntryForUser("SELECT * FROM dataks.tbl WHERE key = 12", username);
    }

    @Test
    public void simpleSelectIsWhitelisted()
    {
        givenTable("dataks", "tbl");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "select", "data/dataks/tbl");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute("SELECT * FROM dataks.tbl WHERE key = 12");
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void preparedInsertIsLogged()
    {
        givenTable("dataks", "tbl");
        String username = unmodifiedUsername;

        PreparedStatement preparedStatement = unmodifiedSession.prepare("INSERT INTO dataks.tbl (key, value) VALUES (?, ?)");
        unmodifiedSession.execute(preparedStatement.bind(5, "hepp"));

        thenAuditLogContainEntryForUser("INSERT INTO dataks.tbl (key, value) VALUES (?, ?)[5, 'hepp']", username);
    }

    @Test
    public void preparedInsertIsWhitelisted()
    {
        givenTable("dataks", "tbl");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "modify", "data/dataks/tbl");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            PreparedStatement preparedStatement = privateSession.prepare("INSERT INTO dataks.tbl (key, value) VALUES (?, ?)");
            privateSession.execute(preparedStatement.bind(5, "hepp"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleInsertIsLogged()
    {
        givenTable("dataks", "tbl");
        String username = unmodifiedUsername;

        unmodifiedSession.execute("INSERT INTO dataks.tbl (key, value) VALUES (45, 'hepp')");

        thenAuditLogContainEntryForUser("INSERT INTO dataks.tbl (key, value) VALUES (45, 'hepp')", username);
    }

    @Test
    public void simpleInsertIsWhitelisted()
    {
        givenTable("dataks", "tbl");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "modify", "data/dataks/tbl");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute("INSERT INTO dataks.tbl (key, value) VALUES (45, 'hepp')");
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void preparedUpdateIsLogged()
    {
        givenTable("dataks", "tbl");
        String username = unmodifiedUsername;

        PreparedStatement preparedStatement = unmodifiedSession.prepare("UPDATE dataks.tbl SET value = ? WHERE key = ?");
        unmodifiedSession.execute(preparedStatement.bind("hepp", 34));

        thenAuditLogContainEntryForUser("UPDATE dataks.tbl SET value = ? WHERE key = ?['hepp', 34]", username);
    }

    @Test
    public void preparedUpdateIsWhitelisted()
    {
        givenTable("dataks", "tbl");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "modify", "data/dataks/tbl");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            PreparedStatement preparedStatement = privateSession.prepare("UPDATE dataks.tbl SET value = ? WHERE key = ?");
            privateSession.execute(preparedStatement.bind("hepp", 565));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleUpdateIsLogged()
    {
        givenTable("dataks", "tbl");
        String username = unmodifiedUsername;

        unmodifiedSession.execute("UPDATE dataks.tbl SET value = 'hepp' WHERE key = 88");

        thenAuditLogContainEntryForUser("UPDATE dataks.tbl SET value = 'hepp' WHERE key = 88", username);
    }

    @Test
    public void simpleUpdateIsWhitelisted()
    {
        givenTable("dataks", "tbl");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "modify", "data/dataks/tbl");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute("UPDATE dataks.tbl SET value = 'hepp' WHERE key = 99");
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void preparedDeleteIsLogged()
    {
        givenTable("dataks", "tbl");
        String username = unmodifiedUsername;

        PreparedStatement preparedStatement = unmodifiedSession.prepare("DELETE value FROM dataks.tbl WHERE key = ?");
        unmodifiedSession.execute(preparedStatement.bind(22));

        thenAuditLogContainEntryForUser("DELETE value FROM dataks.tbl WHERE key = ?[22]", username);
    }

    @Test
    public void preparedDeleteIsWhitelisted()
    {
        givenTable("dataks", "tbl");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "modify", "data/dataks/tbl");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            PreparedStatement preparedStatement = privateSession.prepare("DELETE value FROM dataks.tbl WHERE key = ?");
            privateSession.execute(preparedStatement.bind(222));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleDeleteIsLogged()
    {
        givenTable("dataks", "tbl");
        String username = unmodifiedUsername;

        unmodifiedSession.execute("DELETE value FROM dataks.tbl WHERE key = 5654");

        thenAuditLogContainEntryForUser("DELETE value FROM dataks.tbl WHERE key = 5654", username);
    }

    @Test
    public void simpleDeleteIsWhitelisted()
    {
        givenTable("dataks", "tbl");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "modify", "data/dataks/tbl");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute("DELETE value FROM dataks.tbl WHERE key = 5654");
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleCreateKsIsLogged()
    {
        String username = unmodifiedUsername;

        unmodifiedSession.execute("CREATE KEYSPACE IF NOT EXISTS dataks WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false");

        thenAuditLogContainEntryForUser("CREATE KEYSPACE IF NOT EXISTS dataks WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false", username);
    }

    @Test
    public void simpleCreateKsIsWhitelisted()
    {
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "create", "data/dataks");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute("CREATE KEYSPACE IF NOT EXISTS dataks WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false");
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleCreateTableIsLogged()
    {
        givenKeyspace("dataks");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("CREATE TABLE IF NOT EXISTS dataks.tbl1 (key int PRIMARY KEY, value text)"));

        thenAuditLogContainEntryForUser("CREATE TABLE IF NOT EXISTS dataks.tbl1 (key int PRIMARY KEY, value text)", username);
    }

    @Test
    public void simpleCreateTableIsWhitelisted()
    {
        givenKeyspace("dataks");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "create", "data/dataks");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("CREATE TABLE IF NOT EXISTS dataks.tbl1 (key int PRIMARY KEY, value text)"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleCreateIndexIsLogged()
    {
        givenTable("dataks", "tbl51");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("CREATE INDEX IF NOT EXISTS ON dataks.tbl51 (value)"));

        thenAuditLogContainEntryForUser("CREATE INDEX IF NOT EXISTS ON dataks.tbl51 (value)", username);
    }

    @Test
    public void simpleCreateIndexIsWhitelisted()
    {
        givenTable("dataks", "tbl52");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "alter", "data/dataks/tbl52");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("CREATE INDEX IF NOT EXISTS ON dataks.tbl52 (value)"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleCreateViewIsLogged()
    {
        givenTable("dataks", "tbl53");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("CREATE MATERIALIZED VIEW IF NOT EXISTS dataks.view53 AS " +
                                                      "SELECT value " +
                                                      "FROM dataks.tbl53 " +
                                                      "WHERE value IS NOT NULL AND key IS NOT NULL " +
                                                      "PRIMARY KEY (value, key)"));

        thenAuditLogContainEntryForUser("CREATE MATERIALIZED VIEW IF NOT EXISTS dataks.view53 AS " +
                                        "SELECT value " +
                                        "FROM dataks.tbl53 " +
                                        "WHERE value IS NOT NULL AND key IS NOT NULL " +
                                        "PRIMARY KEY (value, key)", username);
    }

    @Test
    public void simpleCreateViewIsWhitelisted()
    {
        givenTable("dataks", "tbl54");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "alter", "data/dataks/tbl54");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("CREATE MATERIALIZED VIEW IF NOT EXISTS dataks.view54 AS " +
                                                       "SELECT value " +
                                                       "FROM dataks.tbl54 " +
                                                       "WHERE value IS NOT NULL AND key IS NOT NULL " +
                                                       "PRIMARY KEY (value, key)"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleDropKsIsLogged()
    {
        givenKeyspace("dataksdropks");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("DROP KEYSPACE IF EXISTS dataksdropks"));

        thenAuditLogContainEntryForUser("DROP KEYSPACE IF EXISTS dataksdropks", username);
    }

    @Test
    public void simpleDropKsIsWhitelisted()
    {
        givenKeyspace("dataksdropks");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "drop", "data/dataksdropks");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("DROP KEYSPACE IF EXISTS dataksdropks"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleDropTableIsLogged()
    {
        givenTable("dataksdroptable", "tbl2");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("DROP TABLE IF EXISTS dataksdroptable.tbl2"));

        thenAuditLogContainEntryForUser("DROP TABLE IF EXISTS dataksdroptable.tbl2", username);
    }

    @Test
    public void simpleDropTableIsWhitelisted()
    {
        givenTable("dataksdroptable", "tbl2");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "drop", "data/dataksdroptable/tbl2");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("DROP TABLE IF EXISTS dataksdroptable.tbl2"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleDropIndexIsLogged()
    {
        givenIndex("dataks", "tbl234", "idx234");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("DROP INDEX IF EXISTS dataks.idx234"));

        thenAuditLogContainEntryForUser("DROP INDEX IF EXISTS dataks.idx234", username);
    }

    @Test
    public void simpleDropIndexIsWhitelisted()
    {
        givenIndex("dataks", "tbl234", "idx234");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "alter", "data/dataks/tbl234");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("DROP INDEX IF EXISTS dataks.idx234"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleDropViewIsLogged()
    {
        givenView("dataks", "tbl764", "view764");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("DROP MATERIALIZED VIEW IF EXISTS dataks.view764"));

        thenAuditLogContainEntryForUser("DROP MATERIALIZED VIEW IF EXISTS dataks.view764", username);
    }

    @Test
    public void simpleDropViewIsWhitelisted()
    {
        givenView("dataks", "tbl765", "view765");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "alter", "data/dataks/tbl765");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("DROP MATERIALIZED VIEW IF EXISTS dataks.view765"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleAlterKsIsLogged()
    {
        givenKeyspace("dataksalterks");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("ALTER KEYSPACE dataksalterks WITH DURABLE_WRITES = false"));

        thenAuditLogContainEntryForUser("ALTER KEYSPACE dataksalterks WITH DURABLE_WRITES = false", username);
    }

    @Test
    public void simpleAlterKsIsWhitelisted()
    {
        givenKeyspace("dataksalterks");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "alter", "data/dataksalterks");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("ALTER KEYSPACE dataksalterks WITH DURABLE_WRITES = false"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleAlterTableIsLogged()
    {
        givenTable("dataksaltertable", "tbl2");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("ALTER TABLE dataksaltertable.tbl2 WITH gc_grace_seconds = 0"));

        thenAuditLogContainEntryForUser("ALTER TABLE dataksaltertable.tbl2 WITH gc_grace_seconds = 0", username);
    }

    @Test
    public void simpleAlterTableIsWhitelisted()
    {
        givenTable("dataksaltertable", "tbl2");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "alter", "data/dataksaltertable/tbl2");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("ALTER TABLE dataksaltertable.tbl2 WITH gc_grace_seconds = 0"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleAlterViewIsLogged()
    {
        givenView("dataksalterview", "tbl1", "view1");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("ALTER MATERIALIZED VIEW dataksalterview.view1 WITH gc_grace_seconds = 1"));

        thenAuditLogContainEntryForUser("ALTER MATERIALIZED VIEW dataksalterview.view1 WITH gc_grace_seconds = 1", username);
    }

    @Test
    public void simpleAlterViewIsWhitelisted()
    {
        givenView("dataksalterview", "tbl2", "view2");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "alter", "data/dataksalterview/tbl2");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("ALTER MATERIALIZED VIEW dataksalterview.view2 WITH gc_grace_seconds = 1"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleGrantIsLogged()
    {
        givenTable("dataks", "tbl5");
        String username = unmodifiedUsername;
        String grantee = givenSuperuserWithMinimalWhitelist();

        unmodifiedSession.execute(new SimpleStatement("GRANT SELECT ON TABLE dataks.tbl5 TO " + grantee));

        thenAuditLogContainEntryForUser("GRANT SELECT ON TABLE dataks.tbl5 TO " + grantee, username);
    }

    @Test
    public void simpleGrantIsWhitelisted()
    {
        givenTable("dataks", "tbl5");
        String username = givenSuperuserWithMinimalWhitelist();
        String grantee = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "authorize", "data/dataks/tbl5");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("GRANT SELECT ON TABLE dataks.tbl5 TO " + grantee));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleCreateTypeIsLogged()
    {
        givenKeyspace("dataks");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("CREATE TYPE dataks.week1 (" +
                                                      "year int, " +
                                                      "week int" +
                                                      ")"));

        thenAuditLogContainEntryForUser("CREATE TYPE dataks.week1 (" +
                                        "year int, " +
                                        "week int" +
                                        ")", username);
    }

    @Test
    public void simpleCreateTypeIsWhitelisted()
    {
        givenKeyspace("dataks");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "create", "data/dataks");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("CREATE TYPE dataks.week2 (" +
                                                       "year int, " +
                                                       "week int" +
                                                       ")"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleAlterTypeIsLogged()
    {
        givenType("dataks", "type1");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("ALTER TYPE dataks.type1 " +
                                                      "ADD data3 int"));

        thenAuditLogContainEntryForUser("ALTER TYPE dataks.type1 " +
                                        "ADD data3 int", username);
    }

    @Test
    public void simpleAlterTypeIsWhitelisted()
    {
        givenType("dataks", "type2");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "alter", "data/dataks");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("ALTER TYPE dataks.type2 " +
                                                       "ADD data3 int"));
        }

        thenAuditLogContainNothingForUser();
    }

    @Test
    public void simpleDropTypeIsLogged()
    {
        givenType("dataks", "type3");
        String username = unmodifiedUsername;

        unmodifiedSession.execute(new SimpleStatement("DROP TYPE dataks.type3"));

        thenAuditLogContainEntryForUser("DROP TYPE dataks.type3", username);
    }

    @Test
    public void simpleDropTypeIsWhitelisted()
    {
        givenType("dataks", "type4");
        String username = givenSuperuserWithMinimalWhitelist();
        whenRoleIsWhitelistedForOperationOnResource(username, "drop", "data/dataks");

        try (Cluster privateCluster = cdt.createCluster(username, "secret");
             Session privateSession = privateCluster.connect())
        {
            privateSession.execute(new SimpleStatement("DROP TYPE dataks.type4"));
        }

        thenAuditLogContainNothingForUser();
    }

    private void givenKeyspace(String keyspace)
    {
        superSession.execute(new SimpleStatement(
        "CREATE KEYSPACE IF NOT EXISTS " + keyspace + " WITH REPLICATION = {'class' : 'SimpleStrategy', 'replication_factor' : 1} AND DURABLE_WRITES = false"));
    }

    private void givenTable(String keyspace, String table)
    {
        givenKeyspace(keyspace);
        superSession.execute(new SimpleStatement(
        "CREATE TABLE IF NOT EXISTS " + keyspace + "." + table + " (key int PRIMARY KEY, value text)"));
    }

    private void givenIndex(String keyspace, String table, String index)
    {
        givenTable(keyspace, table);
        superSession.execute(new SimpleStatement(
        "CREATE INDEX IF NOT EXISTS " + index + " ON " + keyspace + "." + table + " (value)"));
    }

    private void givenView(String keyspace, String table, String view)
    {
        givenTable(keyspace, table);
        superSession.execute(new SimpleStatement("CREATE MATERIALIZED VIEW IF NOT EXISTS " + keyspace + "." + view + " AS " +
                                                 "SELECT value " +
                                                 "FROM " + keyspace + "." + table + " " +
                                                 "WHERE value IS NOT NULL AND key IS NOT NULL " +
                                                 "PRIMARY KEY (value, key)"));
    }

    private void givenType(String keyspace, String type)
    {
        givenKeyspace(keyspace);
        superSession.execute(new SimpleStatement("CREATE TYPE " + keyspace + "." + type + "(" +
                                                 "data1 int, " +
                                                 "data2 int" +
                                                 ")"));
    }

    private static String givenSuperuserWithMinimalWhitelist()
    {
        String username = "datarole" + usernameNumber.getAndIncrement();
        superSession.execute(new SimpleStatement(
        "CREATE ROLE " + username + " WITH PASSWORD = 'secret' AND LOGIN = true AND SUPERUSER = true"));
        superSession.execute(new SimpleStatement(
        "ALTER ROLE " + username + " WITH OPTIONS = { 'grant_audit_whitelist_for_all'  : 'connections' }"));
        superSession.execute(new SimpleStatement(
        "ALTER ROLE " + username + " WITH OPTIONS = { 'grant_audit_whitelist_for_all'  : 'data/system' }"));
        superSession.execute(new SimpleStatement(
        "ALTER ROLE " + username + " WITH OPTIONS = { 'grant_audit_whitelist_for_all'  : 'data/system_schema' }"));
        return username;
    }

    private void whenRoleIsWhitelistedForOperationOnResource(String username, String operation, String resource)
    {
        superSession.execute("ALTER ROLE " + username + " WITH OPTIONS = {'grant_audit_whitelist_for_all' : '" + resource + "'}");
    }

    private void thenAuditLogContainNothingForUser()
    {
        verify(mockAuditAppender, times(0)).doAppend(any(ILoggingEvent.class));
    }

    private void thenAuditLogContainEntryForUser(String auditOperation, String username)
    {
        ArgumentCaptor<ILoggingEvent> loggingEventCaptor = ArgumentCaptor.forClass(ILoggingEvent.class);
        verify(mockAuditAppender, times(1)).doAppend(loggingEventCaptor.capture());
        List<ILoggingEvent> loggingEvents = loggingEventCaptor.getAllValues();

        assertThat(loggingEvents.get(0).getFormattedMessage()).isEqualTo(expectedAuditEntry(auditOperation, username));
    }

    private String expectedAuditEntry(String auditOperation, String username)
    {
        String obfuscatedOperation = auditOperation.replaceAll("secret", "*****");
        return String.format("client:'127.0.0.1'|user:'%s'|status:'ATTEMPT'|operation:'%s'", username, obfuscatedOperation);
    }
}
