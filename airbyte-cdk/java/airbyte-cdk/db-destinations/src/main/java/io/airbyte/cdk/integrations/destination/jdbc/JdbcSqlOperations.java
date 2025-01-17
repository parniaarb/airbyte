/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.integrations.destination.jdbc;

import com.fasterxml.jackson.databind.JsonNode;
import io.airbyte.cdk.db.jdbc.JdbcDatabase;
import io.airbyte.cdk.integrations.base.JavaBaseConstants;
import io.airbyte.cdk.integrations.base.TypingAndDedupingFlag;
import io.airbyte.cdk.integrations.destination_async.partial_messages.PartialAirbyteMessage;
import io.airbyte.commons.exceptions.ConfigErrorException;
import io.airbyte.commons.json.Jsons;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public abstract class JdbcSqlOperations implements SqlOperations {

  protected static final String SHOW_SCHEMAS = "show schemas;";
  protected static final String NAME = "name";

  // this adapter modifies record message before inserting them to the destination
  protected final Optional<DataAdapter> dataAdapter;
  protected final Set<String> schemaSet = new HashSet<>();

  protected JdbcSqlOperations() {
    this.dataAdapter = Optional.empty();
  }

  protected JdbcSqlOperations(final DataAdapter dataAdapter) {
    this.dataAdapter = Optional.of(dataAdapter);
  }

  @Override
  public void createSchemaIfNotExists(final JdbcDatabase database, final String schemaName) throws Exception {
    try {
      if (!schemaSet.contains(schemaName) && !isSchemaExists(database, schemaName)) {
        database.execute(String.format("CREATE SCHEMA IF NOT EXISTS %s;", schemaName));
        schemaSet.add(schemaName);
      }
    } catch (final Exception e) {
      throw checkForKnownConfigExceptions(e).orElseThrow(() -> e);
    }
  }

  /**
   * When an exception occurs, we may recognize it as an issue with the users permissions or other
   * configuration options. In these cases, we can wrap the exception in a
   * {@link ConfigErrorException} which will exclude the error from our on-call paging/reporting
   *
   * @param e the exception to check.
   * @return A ConfigErrorException with a message with actionable feedback to the user.
   */
  protected Optional<ConfigErrorException> checkForKnownConfigExceptions(final Exception e) {
    return Optional.empty();
  }

  @Override
  public void createTableIfNotExists(final JdbcDatabase database, final String schemaName, final String tableName) throws SQLException {
    try {
      database.execute(createTableQuery(database, schemaName, tableName));
      for (final String postCreateSql : postCreateTableQueries(schemaName, tableName)) {
        database.execute(postCreateSql);
      }
    } catch (final SQLException e) {
      throw checkForKnownConfigExceptions(e).orElseThrow(() -> e);
    }
  }

  @Override
  public String createTableQuery(final JdbcDatabase database, final String schemaName, final String tableName) {
    if (TypingAndDedupingFlag.isDestinationV2()) {
      return createTableQueryV2(schemaName, tableName);
    } else {
      return createTableQueryV1(schemaName, tableName);
    }
  }

  /**
   * Some subclasses may want to execute additional SQL statements after creating the raw table. For
   * example, Postgres does not support index definitions within a CREATE TABLE statement, so we need
   * to run CREATE INDEX statements after creating the table.
   */
  protected List<String> postCreateTableQueries(final String schemaName, final String tableName) {
    return List.of();
  }

  protected String createTableQueryV1(final String schemaName, final String tableName) {
    return String.format(
        """
        CREATE TABLE IF NOT EXISTS %s.%s (
          %s VARCHAR PRIMARY KEY,
          %s JSONB,
          %s TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        );
        """,
        schemaName, tableName, JavaBaseConstants.COLUMN_NAME_AB_ID, JavaBaseConstants.COLUMN_NAME_DATA, JavaBaseConstants.COLUMN_NAME_EMITTED_AT);
  }

  protected String createTableQueryV2(final String schemaName, final String tableName) {
    // Note that Meta is the last column in order, there was a time when tables didn't have meta,
    // we issued Alter to add that column so it should be the last column.
    return String.format(
        """
        CREATE TABLE IF NOT EXISTS %s.%s (
          %s VARCHAR PRIMARY KEY,
          %s JSONB,
          %s TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
          %s TIMESTAMP WITH TIME ZONE DEFAULT NULL,
          %s JSONB
        );
        """,
        schemaName,
        tableName,
        JavaBaseConstants.COLUMN_NAME_AB_RAW_ID,
        JavaBaseConstants.COLUMN_NAME_DATA,
        JavaBaseConstants.COLUMN_NAME_AB_EXTRACTED_AT,
        JavaBaseConstants.COLUMN_NAME_AB_LOADED_AT,
        JavaBaseConstants.COLUMN_NAME_AB_META);
  }

  // TODO: This method seems to be used by Postgres and others while staging to local temp files.
  // Should there be a Local staging operations equivalent
  protected void writeBatchToFile(final File tmpFile, final List<PartialAirbyteMessage> records) throws Exception {
    try (final PrintWriter writer = new PrintWriter(tmpFile, StandardCharsets.UTF_8);
        final CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
      for (final PartialAirbyteMessage record : records) {
        final var uuid = UUID.randomUUID().toString();
        // TODO we only need to do this is formatData is overridden. If not, we can just do jsonData =
        // record.getSerialized()
        final var jsonData = Jsons.serialize(formatData(Jsons.deserializeExact(record.getSerialized())));
        final var airbyteMeta = Jsons.serialize(record.getRecord().getMeta());
        final var extractedAt = Timestamp.from(Instant.ofEpochMilli(record.getRecord().getEmittedAt()));
        if (TypingAndDedupingFlag.isDestinationV2()) {
          csvPrinter.printRecord(uuid, jsonData, extractedAt, null, airbyteMeta);
        } else {
          csvPrinter.printRecord(uuid, jsonData, extractedAt);
        }
      }
    }
  }

  protected JsonNode formatData(final JsonNode data) {
    return data;
  }

  @Override
  public String truncateTableQuery(final JdbcDatabase database, final String schemaName, final String tableName) {
    return String.format("TRUNCATE TABLE %s.%s;\n", schemaName, tableName);
  }

  @Override
  public String insertTableQuery(final JdbcDatabase database, final String schemaName, final String srcTableName, final String dstTableName) {
    return String.format("INSERT INTO %s.%s SELECT * FROM %s.%s;\n", schemaName, dstTableName, schemaName, srcTableName);
  }

  @Override
  public void executeTransaction(final JdbcDatabase database, final List<String> queries) throws Exception {
    final StringBuilder appendedQueries = new StringBuilder();
    appendedQueries.append("BEGIN;\n");
    for (final String query : queries) {
      appendedQueries.append(query);
    }
    appendedQueries.append("COMMIT;");
    database.execute(appendedQueries.toString());
  }

  @Override
  public void dropTableIfExists(final JdbcDatabase database, final String schemaName, final String tableName) throws SQLException {
    try {
      database.execute(dropTableIfExistsQuery(schemaName, tableName));
    } catch (final SQLException e) {
      throw checkForKnownConfigExceptions(e).orElseThrow(() -> e);
    }
  }

  public String dropTableIfExistsQuery(final String schemaName, final String tableName) {
    return String.format("DROP TABLE IF EXISTS %s.%s;\n", schemaName, tableName);
  }

  @Override
  public boolean isSchemaRequired() {
    return true;
  }

  @Override
  public boolean isValidData(final JsonNode data) {
    return true;
  }

  @Override
  public final void insertRecords(final JdbcDatabase database,
                                  final List<PartialAirbyteMessage> records,
                                  final String schemaName,
                                  final String tableName)
      throws Exception {
    dataAdapter.ifPresent(adapter -> records.forEach(airbyteRecordMessage -> {
      final JsonNode data = Jsons.deserializeExact(airbyteRecordMessage.getSerialized());
      adapter.adapt(data);
      airbyteRecordMessage.setSerialized(Jsons.serialize(data));
    }));
    if (TypingAndDedupingFlag.isDestinationV2()) {
      insertRecordsInternalV2(database, records, schemaName, tableName);
    } else {
      insertRecordsInternal(database, records, schemaName, tableName);
    }
  }

  protected abstract void insertRecordsInternal(JdbcDatabase database,
                                                List<PartialAirbyteMessage> records,
                                                String schemaName,
                                                String tableName)
      throws Exception;

  protected abstract void insertRecordsInternalV2(JdbcDatabase database,
                                                  List<PartialAirbyteMessage> records,
                                                  String schemaName,
                                                  String tableName)
      throws Exception;

}
