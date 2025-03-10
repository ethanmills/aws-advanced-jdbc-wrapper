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
import software.amazon.jdbc.exceptions.ExceptionHandler;
import software.amazon.jdbc.exceptions.MariaDBExceptionHandler;

public class MariaDbDialect implements Dialect {

  private static MariaDBExceptionHandler mariaDBExceptionHandler;

  @Override
  public int getDefaultPort() {
    return 3306;
  }

  @Override
  public ExceptionHandler getExceptionHandler() {
    if (mariaDBExceptionHandler == null) {
      mariaDBExceptionHandler = new MariaDBExceptionHandler();
    }
    return mariaDBExceptionHandler;
  }

  @Override
  public String getHostAliasQuery() {
    return "SELECT CONCAT(@@hostname, ':', @@port)";
  }

  @Override
  public String getServerVersionQuery() {
    return "SELECT VERSION()";
  }

  @Override
  public boolean isDialect(final Connection connection) {
    try (final Statement stmt = connection.createStatement();
        final ResultSet rs = stmt.executeQuery(this.getServerVersionQuery())) {
      while (rs.next()) {
        final String columnValue = rs.getString(1);
        if (columnValue != null && columnValue.toLowerCase().contains("mariadb")) {
          return true;
        }
      }
    } catch (final SQLException ex) {
      // ignore
    }
    return false;
  }

  @Override
  public List<String> getDialectUpdateCandidates() {
    return null;
  }

}
