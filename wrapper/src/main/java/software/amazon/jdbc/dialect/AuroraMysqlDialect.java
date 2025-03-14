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

package software.amazon.jdbc.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class AuroraMysqlDialect extends MysqlDialect implements TopologyAwareDatabaseCluster {

  @Override
  public String getTopologyQuery() {
    return "SELECT SERVER_ID, CASE WHEN SESSION_ID = 'MASTER_SESSION_ID' THEN TRUE ELSE FALSE END, "
        + "CPU, REPLICA_LAG_IN_MILLISECONDS, LAST_UPDATE_TIMESTAMP "
        + "FROM information_schema.replica_host_status "
        // filter out nodes that haven't been updated in the last 5 minutes
        + "WHERE time_to_sec(timediff(now(), LAST_UPDATE_TIMESTAMP)) <= 300 OR SESSION_ID = 'MASTER_SESSION_ID' ";
  }

  @Override
  public String getNodeIdQuery() {
    return "SELECT @@aurora_server_id";
  }

  @Override
  public String getIsReaderQuery() {
    return "SELECT @@innodb_read_only";
  }

  @Override
  public boolean isDialect(final Connection connection) {
    try (final Statement stmt = connection.createStatement();
        final ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'aurora_version'")) {
      if (rs.next()) {
        // If variable with such name is presented then it means it's an Aurora cluster
        return true;
      }
    } catch (final SQLException ex) {
      // ignore
    }
    return false;
  }

  @Override
  public List</* dialect code */ String> getDialectUpdateCandidates() {
    return null;
  }
}
