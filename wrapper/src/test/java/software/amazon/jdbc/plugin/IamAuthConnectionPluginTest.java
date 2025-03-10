/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package software.amazon.jdbc.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.regions.Region;
import software.amazon.jdbc.Driver;
import software.amazon.jdbc.HostSpec;
import software.amazon.jdbc.JdbcCallable;
import software.amazon.jdbc.PluginService;
import software.amazon.jdbc.PropertyDefinition;
import software.amazon.jdbc.dialect.Dialect;

class IamAuthConnectionPluginTest {

  private static final String GENERATED_TOKEN = "generatedToken";
  private static final String TEST_TOKEN = "testToken";
  private static final int DEFAULT_PG_PORT = 5432;
  private static final int DEFAULT_MYSQL_PORT = 3306;
  private static final String PG_CACHE_KEY = "us-east-2:pg.testdb.us-east-2.rds.amazonaws.com:"
      + DEFAULT_PG_PORT + ":postgresqlUser";
  private static final String MYSQL_CACHE_KEY = "us-east-2:mysql.testdb.us-east-2.rds.amazonaws.com:"
      + DEFAULT_MYSQL_PORT + ":mysqlUser";
  private static final String PG_DRIVER_PROTOCOL = "jdbc:postgresql:";
  private static final String MYSQL_DRIVER_PROTOCOL = "jdbc:mysql:";
  private static final HostSpec PG_HOST_SPEC = new HostSpec("pg.testdb.us-east-2.rds.amazonaws.com");
  private static final HostSpec PG_HOST_SPEC_WITH_PORT = new HostSpec("pg.testdb.us-east-2.rds.amazonaws.com", 1234);
  private static final HostSpec PG_HOST_SPEC_WITH_REGION = new HostSpec("pg.testdb.us-west-1.rds.amazonaws.com");
  private static final HostSpec MYSQL_HOST_SPEC = new HostSpec("mysql.testdb.us-east-2.rds.amazonaws.com");
  private Properties props;

  @Mock JdbcCallable<Connection, SQLException> mockLambda;
  @Mock PluginService mockPluginService;
  @Mock Dialect mockDialect;

  @BeforeEach
  public void init() {
    MockitoAnnotations.openMocks(this);
    props = new Properties();
    props.setProperty(PropertyDefinition.USER.name, "postgresqlUser");
    props.setProperty(PropertyDefinition.PASSWORD.name, "postgresqlPassword");
    props.setProperty(PropertyDefinition.PLUGINS.name, "iam");
    IamAuthConnectionPlugin.clearCache();

    when(mockPluginService.getDialect()).thenReturn(mockDialect);
  }

  @BeforeAll
  public static void registerDrivers() throws SQLException {
    if (!org.postgresql.Driver.isRegistered()) {
      org.postgresql.Driver.register();
    }

    if (!Driver.isRegistered()) {
      Driver.register();
    }
  }

  @Test
  public void testPostgresConnectValidTokenInCache() throws SQLException {
    IamAuthConnectionPlugin.tokenCache.put(PG_CACHE_KEY,
        new IamAuthConnectionPlugin.TokenInfo(TEST_TOKEN, Instant.now().plusMillis(300000)));

    when(mockDialect.getDefaultPort()).thenReturn(DEFAULT_PG_PORT);

    testTokenSetInProps(PG_DRIVER_PROTOCOL, PG_HOST_SPEC);
  }

  @Test
  public void testMySqlConnectValidTokenInCache() throws SQLException {
    props.setProperty(PropertyDefinition.USER.name, "mysqlUser");
    props.setProperty(PropertyDefinition.PASSWORD.name, "mysqlPassword");
    IamAuthConnectionPlugin.tokenCache.put(MYSQL_CACHE_KEY,
        new IamAuthConnectionPlugin.TokenInfo(TEST_TOKEN, Instant.now().plusMillis(300000)));

    when(mockDialect.getDefaultPort()).thenReturn(DEFAULT_MYSQL_PORT);

    testTokenSetInProps(MYSQL_DRIVER_PROTOCOL, MYSQL_HOST_SPEC);
  }

  @Test
  public void testPostgresConnectWithInvalidPortFallbacksToHostPort() throws SQLException {
    final String invalidIamDefaultPort = "0";
    props.setProperty(IamAuthConnectionPlugin.IAM_DEFAULT_PORT.name, invalidIamDefaultPort);

    final String cacheKeyWithNewPort = "us-east-2:pg.testdb.us-east-2.rds.amazonaws.com:"
        + PG_HOST_SPEC_WITH_PORT.getPort() + ":postgresqlUser";
    IamAuthConnectionPlugin.tokenCache.put(cacheKeyWithNewPort,
        new IamAuthConnectionPlugin.TokenInfo(TEST_TOKEN, Instant.now().plusMillis(300000)));

    testTokenSetInProps(PG_DRIVER_PROTOCOL, PG_HOST_SPEC_WITH_PORT);
  }

  @Test
  public void testPostgresConnectWithInvalidPortAndNoHostPortFallbacksToHostPort() throws SQLException {
    final String invalidIamDefaultPort = "0";
    props.setProperty(IamAuthConnectionPlugin.IAM_DEFAULT_PORT.name, invalidIamDefaultPort);

    when(mockDialect.getDefaultPort()).thenReturn(DEFAULT_PG_PORT);

    final String cacheKeyWithNewPort = "us-east-2:pg.testdb.us-east-2.rds.amazonaws.com:"
        + DEFAULT_PG_PORT + ":postgresqlUser";
    IamAuthConnectionPlugin.tokenCache.put(cacheKeyWithNewPort,
        new IamAuthConnectionPlugin.TokenInfo(TEST_TOKEN, Instant.now().plusMillis(300000)));

    testTokenSetInProps(PG_DRIVER_PROTOCOL, PG_HOST_SPEC);
  }

  @Test
  public void testConnectExpiredTokenInCache() throws SQLException {
    IamAuthConnectionPlugin.tokenCache.put(PG_CACHE_KEY,
        new IamAuthConnectionPlugin.TokenInfo(TEST_TOKEN, Instant.now().minusMillis(300000)));

    when(mockDialect.getDefaultPort()).thenReturn(DEFAULT_PG_PORT);

    testGenerateToken(PG_DRIVER_PROTOCOL, PG_HOST_SPEC);
  }

  @Test
  public void testConnectEmptyCache() throws SQLException {
    when(mockDialect.getDefaultPort()).thenReturn(DEFAULT_PG_PORT);

    testGenerateToken(PG_DRIVER_PROTOCOL, PG_HOST_SPEC);
  }

  @Test
  public void testConnectWithSpecifiedPort() throws SQLException {
    final String cacheKeyWithNewPort = "us-east-2:pg.testdb.us-east-2.rds.amazonaws.com:1234:" + "postgresqlUser";
    IamAuthConnectionPlugin.tokenCache.put(cacheKeyWithNewPort,
        new IamAuthConnectionPlugin.TokenInfo(TEST_TOKEN, Instant.now().plusMillis(300000)));

    testTokenSetInProps(PG_DRIVER_PROTOCOL, PG_HOST_SPEC_WITH_PORT);
  }

  @Test
  public void testConnectWithSpecifiedIamDefaultPort() throws SQLException {
    final String iamDefaultPort = "9999";
    props.setProperty(IamAuthConnectionPlugin.IAM_DEFAULT_PORT.name, iamDefaultPort);
    final String cacheKeyWithNewPort = "us-east-2:pg.testdb.us-east-2.rds.amazonaws.com:"
        + iamDefaultPort + ":postgresqlUser";
    IamAuthConnectionPlugin.tokenCache.put(cacheKeyWithNewPort,
        new IamAuthConnectionPlugin.TokenInfo(TEST_TOKEN, Instant.now().plusMillis(300000)));

    testTokenSetInProps(PG_DRIVER_PROTOCOL, PG_HOST_SPEC_WITH_PORT);
  }

  @Test
  public void testConnectWithSpecifiedRegion() throws SQLException {
    final String cacheKeyWithNewRegion =
        "us-west-1:pg.testdb.us-west-1.rds.amazonaws.com:" + DEFAULT_PG_PORT + ":" + "postgresqlUser";
    props.setProperty(IamAuthConnectionPlugin.IAM_REGION.name, "us-west-1");
    IamAuthConnectionPlugin.tokenCache.put(cacheKeyWithNewRegion,
        new IamAuthConnectionPlugin.TokenInfo(TEST_TOKEN, Instant.now().plusMillis(300000)));

    when(mockDialect.getDefaultPort()).thenReturn(DEFAULT_PG_PORT);

    testTokenSetInProps(PG_DRIVER_PROTOCOL, PG_HOST_SPEC_WITH_REGION);
  }

  @Test
  public void testConnectWithSpecifiedHost() throws SQLException {
    props.setProperty(IamAuthConnectionPlugin.IAM_REGION.name, "us-east-2");
    props.setProperty(IamAuthConnectionPlugin.IAM_HOST.name, "pg.testdb.us-east-2.rds.amazonaws.com");

    when(mockDialect.getDefaultPort()).thenReturn(DEFAULT_PG_PORT);

    testGenerateToken(
        PG_DRIVER_PROTOCOL,
        new HostSpec("8.8.8.8"),
        "pg.testdb.us-east-2.rds.amazonaws.com");
  }

  @Test
  public void testAwsSupportedRegionsUrlExists() throws IOException {
    final URL url =
        new URL("https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.RegionsAndAvailabilityZones.html");
    final HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
    final int responseCode = urlConnection.getResponseCode();

    assertEquals(HttpURLConnection.HTTP_OK, responseCode);
  }

  public void testTokenSetInProps(final String protocol, final HostSpec hostSpec) throws SQLException {

    IamAuthConnectionPlugin targetPlugin = new IamAuthConnectionPlugin(mockPluginService);
    doThrow(new SQLException()).when(mockLambda).call();

    assertThrows(SQLException.class, () -> targetPlugin.connect(protocol, hostSpec, props, true, mockLambda));
    verify(mockLambda, times(1)).call();

    assertEquals(TEST_TOKEN, PropertyDefinition.PASSWORD.getString(props));
  }

  private void testGenerateToken(final String protocol, final HostSpec hostSpec) throws SQLException {
    testGenerateToken(protocol, hostSpec, hostSpec.getHost());
  }

  private void testGenerateToken(
      final String protocol,
      final HostSpec hostSpec,
      final String expectedHost) throws SQLException {
    final IamAuthConnectionPlugin targetPlugin = new IamAuthConnectionPlugin(mockPluginService);
    final IamAuthConnectionPlugin spyPlugin = Mockito.spy(targetPlugin);

    doReturn(GENERATED_TOKEN).when(spyPlugin)
        .generateAuthenticationToken(
            hostSpec,
            props,
            expectedHost,
            DEFAULT_PG_PORT,
            Region.US_EAST_2);
    doThrow(new SQLException()).when(mockLambda).call();

    assertThrows(SQLException.class,
        () -> spyPlugin.connect(protocol, hostSpec, props, true, mockLambda));
    verify(mockLambda, times(1)).call();

    assertEquals(GENERATED_TOKEN, PropertyDefinition.PASSWORD.getString(props));
    assertEquals(GENERATED_TOKEN, IamAuthConnectionPlugin.tokenCache.get(PG_CACHE_KEY).getToken());
  }
}
