/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.integration.test.catalog.hive;

import static com.datastrato.gravitino.catalog.hive.HiveCatalogPropertiesMeta.METASTORE_URIS;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.COMMENT;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.EXTERNAL;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.FORMAT;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.IGNORE_KEY_OUTPUT_FORMAT_CLASS;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.INPUT_FORMAT;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.LOCATION;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.NUM_FILES;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.OPENCSV_SERDE_CLASS;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.OUTPUT_FORMAT;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.SERDE_LIB;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.TABLE_TYPE;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.TEXT_INPUT_FORMAT_CLASS;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.TOTAL_SIZE;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.TRANSIENT_LAST_DDL_TIME;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.TableType.EXTERNAL_TABLE;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.TableType.MANAGED_TABLE;
import static org.apache.hadoop.hive.serde.serdeConstants.DATE_TYPE_NAME;
import static org.apache.hadoop.hive.serde.serdeConstants.INT_TYPE_NAME;
import static org.apache.hadoop.hive.serde.serdeConstants.STRING_TYPE_NAME;
import static org.apache.hadoop.hive.serde.serdeConstants.TINYINT_TYPE_NAME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datastrato.gravitino.Catalog;
import com.datastrato.gravitino.CatalogChange;
import com.datastrato.gravitino.MetalakeChange;
import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.auth.AuthConstants;
import com.datastrato.gravitino.catalog.hive.HiveClientPool;
import com.datastrato.gravitino.catalog.hive.HiveSchemaPropertiesMetadata;
import com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata;
import com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.TableType;
import com.datastrato.gravitino.client.GravitinoMetaLake;
import com.datastrato.gravitino.dto.rel.ColumnDTO;
import com.datastrato.gravitino.dto.rel.expressions.FieldReferenceDTO;
import com.datastrato.gravitino.dto.rel.partitions.IdentityPartitioningDTO;
import com.datastrato.gravitino.dto.rel.partitions.Partitioning;
import com.datastrato.gravitino.exceptions.NoSuchCatalogException;
import com.datastrato.gravitino.exceptions.NoSuchMetalakeException;
import com.datastrato.gravitino.exceptions.NoSuchSchemaException;
import com.datastrato.gravitino.exceptions.NoSuchTableException;
import com.datastrato.gravitino.integration.test.container.ContainerSuite;
import com.datastrato.gravitino.integration.test.container.HiveContainer;
import com.datastrato.gravitino.integration.test.util.AbstractIT;
import com.datastrato.gravitino.integration.test.util.GravitinoITUtils;
import com.datastrato.gravitino.rel.Schema;
import com.datastrato.gravitino.rel.SchemaChange;
import com.datastrato.gravitino.rel.Table;
import com.datastrato.gravitino.rel.TableChange;
import com.datastrato.gravitino.rel.expressions.NamedReference;
import com.datastrato.gravitino.rel.expressions.distributions.Distribution;
import com.datastrato.gravitino.rel.expressions.distributions.Distributions;
import com.datastrato.gravitino.rel.expressions.distributions.Strategy;
import com.datastrato.gravitino.rel.expressions.sorts.NullOrdering;
import com.datastrato.gravitino.rel.expressions.sorts.SortDirection;
import com.datastrato.gravitino.rel.expressions.sorts.SortOrder;
import com.datastrato.gravitino.rel.expressions.sorts.SortOrders;
import com.datastrato.gravitino.rel.expressions.transforms.Transform;
import com.datastrato.gravitino.rel.expressions.transforms.Transforms;
import com.datastrato.gravitino.rel.types.Types;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.spark.sql.SparkSession;
import org.apache.thrift.TException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag("gravitino-docker-it")
public class CatalogHiveIT extends AbstractIT {
  private static final Logger LOG = LoggerFactory.getLogger(CatalogHiveIT.class);
  public static final String metalakeName =
      GravitinoITUtils.genRandomName("CatalogHiveIT_metalake");
  public static final String catalogName = GravitinoITUtils.genRandomName("CatalogHiveIT_catalog");
  public static final String SCHEMA_PREFIX = "CatalogHiveIT_schema";
  public static final String schemaName = GravitinoITUtils.genRandomName(SCHEMA_PREFIX);
  public static final String TABLE_PREFIX = "CatalogHiveIT_table";
  public static final String tableName = GravitinoITUtils.genRandomName(TABLE_PREFIX);
  public static final String ALTER_TABLE_NAME = "alert_table_name";
  public static final String TABLE_COMMENT = "table_comment";
  public static final String HIVE_COL_NAME1 = "hive_col_name1";
  public static final String HIVE_COL_NAME2 = "hive_col_name2";
  public static final String HIVE_COL_NAME3 = "hive_col_name3";
  private static String HIVE_METASTORE_URIS;
  private static final String provider = "hive";
  private static final ContainerSuite containerSuite = ContainerSuite.getInstance();
  private static HiveClientPool hiveClientPool;
  private static GravitinoMetaLake metalake;
  private static Catalog catalog;
  private static SparkSession sparkSession;
  private static FileSystem hdfs;
  private static final String SELECT_ALL_TEMPLATE = "SELECT * FROM %s.%s";
  private static final String INSERT_WITHOUT_PARTITION_TEMPLATE = "INSERT INTO %s.%s VALUES (%s)";
  private static final String INSERT_WITH_PARTITION_TEMPLATE =
      "INSERT INTO %s.%s PARTITION (%s) VALUES (%s)";

  private static final Map<String, String> typeConstant =
      ImmutableMap.of(
          TINYINT_TYPE_NAME,
          "1",
          INT_TYPE_NAME,
          "2",
          DATE_TYPE_NAME,
          "'2023-01-01'",
          STRING_TYPE_NAME,
          "'gravitino_it_test'");

  @BeforeAll
  public static void startup() throws Exception {
    containerSuite.startHiveContainer();

    HIVE_METASTORE_URIS =
        String.format(
            "thrift://%s:%d",
            containerSuite.getHiveContainer().getContainerIpAddress(),
            HiveContainer.HIVE_METASTORE_PORT);

    HiveConf hiveConf = new HiveConf();
    hiveConf.set(HiveConf.ConfVars.METASTOREURIS.varname, HIVE_METASTORE_URIS);

    // Check if hive client can connect to hive metastore
    hiveClientPool = new HiveClientPool(1, hiveConf);
    List<String> dbs = hiveClientPool.run(client -> client.getAllDatabases());
    Assertions.assertFalse(dbs.isEmpty());

    sparkSession =
        SparkSession.builder()
            .master("local[1]")
            .appName("Hive Catalog integration test")
            .config("hive.metastore.uris", HIVE_METASTORE_URIS)
            .config(
                "spark.sql.warehouse.dir",
                String.format(
                    "hdfs://%s:%d/user/hive/warehouse",
                    containerSuite.getHiveContainer().getContainerIpAddress(),
                    HiveContainer.HDFS_DEFAULTFS_PORT))
            .config("spark.sql.storeAssignmentPolicy", "LEGACY")
            .config("mapreduce.input.fileinputformat.input.dir.recursive", "true")
            .enableHiveSupport()
            .getOrCreate();

    Configuration conf = new Configuration();
    conf.set(
        "fs.defaultFS",
        String.format(
            "hdfs://%s:%d",
            containerSuite.getHiveContainer().getContainerIpAddress(),
            HiveContainer.HDFS_DEFAULTFS_PORT));
    hdfs = FileSystem.get(conf);

    createMetalake();
    createCatalog();
    createSchema();
  }

  @AfterAll
  public static void stop() throws IOException {
    client.dropMetalake(NameIdentifier.of(metalakeName));
    if (hiveClientPool != null) {
      hiveClientPool.close();
    }

    if (sparkSession != null) {
      sparkSession.close();
    }

    if (hdfs != null) {
      hdfs.close();
    }
    try {
      closer.close();
    } catch (Exception e) {
      LOG.error("Failed to close CloseableGroup", e);
    }
  }

  @AfterEach
  public void resetSchema() throws TException, InterruptedException {
    catalog.asSchemas().dropSchema(NameIdentifier.of(metalakeName, catalogName, schemaName), true);
    assertThrows(
        NoSuchObjectException.class,
        () -> hiveClientPool.run(client -> client.getDatabase(schemaName)));
    createSchema();
  }

  private static void createMetalake() {
    GravitinoMetaLake[] gravitinoMetaLakes = client.listMetalakes();
    Assertions.assertEquals(0, gravitinoMetaLakes.length);

    GravitinoMetaLake createdMetalake =
        client.createMetalake(NameIdentifier.of(metalakeName), "comment", Collections.emptyMap());
    GravitinoMetaLake loadMetalake = client.loadMetalake(NameIdentifier.of(metalakeName));
    Assertions.assertEquals(createdMetalake, loadMetalake);

    metalake = loadMetalake;
  }

  private static void createCatalog() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put(METASTORE_URIS, HIVE_METASTORE_URIS);

    metalake.createCatalog(
        NameIdentifier.of(metalakeName, catalogName),
        Catalog.Type.RELATIONAL,
        provider,
        "comment",
        properties);

    catalog = metalake.loadCatalog(NameIdentifier.of(metalakeName, catalogName));
  }

  private static void createSchema() throws TException, InterruptedException {
    NameIdentifier ident = NameIdentifier.of(metalakeName, catalogName, schemaName);
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");
    properties.put(
        "location",
        String.format(
            "hdfs://%s:%d/user/hive/warehouse/%s.db",
            containerSuite.getHiveContainer().getContainerIpAddress(),
            HiveContainer.HDFS_DEFAULTFS_PORT,
            schemaName.toLowerCase()));
    String comment = "comment";

    catalog.asSchemas().createSchema(ident, comment, properties);
    Schema loadSchema = catalog.asSchemas().loadSchema(ident);
    Assertions.assertEquals(schemaName.toLowerCase(), loadSchema.name());
    Assertions.assertEquals(comment, loadSchema.comment());
    Assertions.assertEquals("val1", loadSchema.properties().get("key1"));
    Assertions.assertEquals("val2", loadSchema.properties().get("key2"));
    Assertions.assertNotNull(loadSchema.properties().get(HiveSchemaPropertiesMetadata.LOCATION));

    // Directly get database from hive metastore to verify the schema creation
    Database database = hiveClientPool.run(client -> client.getDatabase(schemaName));
    Assertions.assertEquals(schemaName.toLowerCase(), database.getName());
    Assertions.assertEquals(comment, database.getDescription());
    Assertions.assertEquals("val1", database.getParameters().get("key1"));
    Assertions.assertEquals("val2", database.getParameters().get("key2"));
  }

  private ColumnDTO[] createColumns() {
    ColumnDTO col1 =
        new ColumnDTO.Builder<>()
            .withName(HIVE_COL_NAME1)
            .withDataType(Types.ByteType.get())
            .withComment("col_1_comment")
            .build();
    ColumnDTO col2 =
        new ColumnDTO.Builder<>()
            .withName(HIVE_COL_NAME2)
            .withDataType(Types.DateType.get())
            .withComment("col_2_comment")
            .build();
    ColumnDTO col3 =
        new ColumnDTO.Builder<>()
            .withName(HIVE_COL_NAME3)
            .withDataType(Types.StringType.get())
            .withComment("col_3_comment")
            .build();
    return new ColumnDTO[] {col1, col2, col3};
  }

  private void checkTableReadWrite(org.apache.hadoop.hive.metastore.api.Table table) {
    String dbName = table.getDbName();
    String tableName = table.getTableName();
    long count = sparkSession.sql(String.format(SELECT_ALL_TEMPLATE, dbName, tableName)).count();
    String values =
        table.getSd().getCols().stream()
            .map(f -> typeConstant.get(f.getType()))
            .map(Object::toString)
            .collect(Collectors.joining(","));
    if (table.getPartitionKeys().isEmpty()) {
      sparkSession.sql(String.format(INSERT_WITHOUT_PARTITION_TEMPLATE, dbName, tableName, values));
    } else {
      String partitionExpressions =
          table.getPartitionKeys().stream()
              .map(f -> f.getName() + "=" + typeConstant.get(f.getType()))
              .collect(Collectors.joining(","));
      sparkSession.sql(
          String.format(
              INSERT_WITH_PARTITION_TEMPLATE, dbName, tableName, partitionExpressions, values));
    }
    Assertions.assertEquals(
        count + 1, sparkSession.sql(String.format(SELECT_ALL_TEMPLATE, dbName, tableName)).count());
    // Assert HDFS owner
    Path tableDirectory = new Path(table.getSd().getLocation());
    FileStatus[] fileStatuses;
    try {
      fileStatuses = hdfs.listStatus(tableDirectory);
    } catch (IOException e) {
      LOG.warn("Failed to list status of table directory", e);
      throw new RuntimeException(e);
    }
    Assertions.assertTrue(fileStatuses.length > 0);
    for (FileStatus fileStatus : fileStatuses) {
      Assertions.assertEquals("datastrato", fileStatus.getOwner());
    }
  }

  private Map<String, String> createProperties() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");
    return properties;
  }

  @Test
  public void testCreateHiveTableWithDistributionAndSortOrder()
      throws TException, InterruptedException {
    // Create table from Gravitino API
    ColumnDTO[] columns = createColumns();

    NameIdentifier nameIdentifier =
        NameIdentifier.of(metalakeName, catalogName, schemaName, tableName);

    Distribution distribution =
        Distributions.of(Strategy.EVEN, 10, NamedReference.field(HIVE_COL_NAME1));

    final SortOrder[] sortOrders =
        new SortOrder[] {
          SortOrders.of(
              NamedReference.field(HIVE_COL_NAME2),
              SortDirection.DESCENDING,
              NullOrdering.NULLS_FIRST)
        };

    Map<String, String> properties = createProperties();
    Table createdTable =
        catalog
            .asTableCatalog()
            .createTable(
                nameIdentifier,
                columns,
                TABLE_COMMENT,
                properties,
                Transforms.EMPTY_TRANSFORM,
                distribution,
                sortOrders);

    // Directly get table from hive metastore to check if the table is created successfully.
    org.apache.hadoop.hive.metastore.api.Table hiveTab =
        hiveClientPool.run(client -> client.getTable(schemaName, tableName));
    properties
        .keySet()
        .forEach(
            key -> Assertions.assertEquals(properties.get(key), hiveTab.getParameters().get(key)));
    assertTableEquals(createdTable, hiveTab);
    checkTableReadWrite(hiveTab);

    // test null partition
    resetSchema();
    Table createdTable1 =
        catalog
            .asTableCatalog()
            .createTable(nameIdentifier, columns, TABLE_COMMENT, properties, (Transform[]) null);

    // Directly get table from hive metastore to check if the table is created successfully.
    org.apache.hadoop.hive.metastore.api.Table hiveTable1 =
        hiveClientPool.run(client -> client.getTable(schemaName, tableName));
    properties
        .keySet()
        .forEach(
            key ->
                Assertions.assertEquals(properties.get(key), hiveTable1.getParameters().get(key)));
    assertTableEquals(createdTable1, hiveTable1);
    checkTableReadWrite(hiveTable1);

    // Test bad request
    // Bad name in distribution
    final Distribution badDistribution =
        Distributions.of(Strategy.EVEN, 10, NamedReference.field(HIVE_COL_NAME1 + "bad_name"));
    Assertions.assertThrows(
        Exception.class,
        () -> {
          catalog
              .asTableCatalog()
              .createTable(
                  nameIdentifier,
                  columns,
                  TABLE_COMMENT,
                  properties,
                  Transforms.EMPTY_TRANSFORM,
                  badDistribution,
                  sortOrders);
        });

    final SortOrder[] badSortOrders =
        new SortOrder[] {
          SortOrders.of(
              NamedReference.field(HIVE_COL_NAME2 + "bad_name"),
              SortDirection.DESCENDING,
              NullOrdering.NULLS_FIRST)
        };

    Assertions.assertThrows(
        Exception.class,
        () -> {
          catalog
              .asTableCatalog()
              .createTable(
                  nameIdentifier,
                  columns,
                  TABLE_COMMENT,
                  properties,
                  Transforms.EMPTY_TRANSFORM,
                  distribution,
                  badSortOrders);
        });
  }

  @Test
  public void testCreateHiveTable() throws TException, InterruptedException {
    // Create table from Gravitino API
    ColumnDTO[] columns = createColumns();

    NameIdentifier nameIdentifier =
        NameIdentifier.of(metalakeName, catalogName, schemaName, tableName);
    Map<String, String> properties = createProperties();
    Table createdTable =
        catalog
            .asTableCatalog()
            .createTable(
                nameIdentifier, columns, TABLE_COMMENT, properties, Transforms.EMPTY_TRANSFORM);

    // Directly get table from hive metastore to check if the table is created successfully.
    org.apache.hadoop.hive.metastore.api.Table hiveTab =
        hiveClientPool.run(client -> client.getTable(schemaName, tableName));
    properties
        .keySet()
        .forEach(
            key -> Assertions.assertEquals(properties.get(key), hiveTab.getParameters().get(key)));
    assertTableEquals(createdTable, hiveTab);
    checkTableReadWrite(hiveTab);

    // test null comment
    resetSchema();
    createdTable =
        catalog
            .asTableCatalog()
            .createTable(nameIdentifier, columns, null, properties, Transforms.EMPTY_TRANSFORM);
    org.apache.hadoop.hive.metastore.api.Table hiveTab2 =
        hiveClientPool.run(client -> client.getTable(schemaName, tableName));
    assertTableEquals(createdTable, hiveTab2);
    checkTableReadWrite(hiveTab);

    // test null partition
    resetSchema();
    Table createdTable1 =
        catalog
            .asTableCatalog()
            .createTable(nameIdentifier, columns, TABLE_COMMENT, properties, (Transform[]) null);

    // Directly get table from hive metastore to check if the table is created successfully.
    org.apache.hadoop.hive.metastore.api.Table hiveTable1 =
        hiveClientPool.run(client -> client.getTable(schemaName, tableName));
    properties
        .keySet()
        .forEach(
            key ->
                Assertions.assertEquals(properties.get(key), hiveTable1.getParameters().get(key)));
    assertTableEquals(createdTable1, hiveTable1);
    checkTableReadWrite(hiveTable1);
  }

  @Test
  public void testHiveTableProperties() throws TException, InterruptedException {
    ColumnDTO[] columns = createColumns();
    NameIdentifier nameIdentifier =
        NameIdentifier.of(metalakeName, catalogName, schemaName, tableName);
    // test default properties
    Table createdTable =
        catalog
            .asTableCatalog()
            .createTable(
                nameIdentifier,
                columns,
                TABLE_COMMENT,
                ImmutableMap.of(),
                Transforms.EMPTY_TRANSFORM);
    HiveTablePropertiesMetadata tablePropertiesMetadata = new HiveTablePropertiesMetadata();
    org.apache.hadoop.hive.metastore.api.Table actualTable =
        hiveClientPool.run(client -> client.getTable(schemaName, tableName));
    assertDefaultTableProperties(createdTable, actualTable);
    checkTableReadWrite(actualTable);

    // test set properties
    String table2 = GravitinoITUtils.genRandomName(TABLE_PREFIX);
    Table createdTable2 =
        catalog
            .asTableCatalog()
            .createTable(
                NameIdentifier.of(metalakeName, catalogName, schemaName, table2),
                columns,
                TABLE_COMMENT,
                ImmutableMap.of(
                    TABLE_TYPE,
                    "external_table",
                    LOCATION,
                    String.format(
                        "hdfs://%s:%d/tmp",
                        containerSuite.getHiveContainer().getContainerIpAddress(),
                        HiveContainer.HDFS_DEFAULTFS_PORT),
                    FORMAT,
                    "textfile",
                    SERDE_LIB,
                    OPENCSV_SERDE_CLASS),
                Transforms.EMPTY_TRANSFORM);
    org.apache.hadoop.hive.metastore.api.Table actualTable2 =
        hiveClientPool.run(client -> client.getTable(schemaName, table2));

    Assertions.assertEquals(
        OPENCSV_SERDE_CLASS, actualTable2.getSd().getSerdeInfo().getSerializationLib());
    Assertions.assertEquals(TEXT_INPUT_FORMAT_CLASS, actualTable2.getSd().getInputFormat());
    Assertions.assertEquals(IGNORE_KEY_OUTPUT_FORMAT_CLASS, actualTable2.getSd().getOutputFormat());
    Assertions.assertEquals(EXTERNAL_TABLE.name(), actualTable2.getTableType());
    Assertions.assertEquals(table2, actualTable2.getSd().getSerdeInfo().getName());
    Assertions.assertEquals(TABLE_COMMENT, actualTable2.getParameters().get(COMMENT));
    Assertions.assertEquals(
        ((Boolean) tablePropertiesMetadata.getDefaultValue(EXTERNAL)).toString().toUpperCase(),
        actualTable.getParameters().get(EXTERNAL));
    Assertions.assertTrue(actualTable2.getSd().getLocation().endsWith("/tmp"));
    Assertions.assertNotNull(createdTable2.properties().get(TRANSIENT_LAST_DDL_TIME));
    Assertions.assertNotNull(createdTable2.properties().get(NUM_FILES));
    Assertions.assertNotNull(createdTable2.properties().get(TOTAL_SIZE));
    checkTableReadWrite(actualTable2);

    // test alter properties exception
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> {
              catalog
                  .asTableCatalog()
                  .alterTable(
                      NameIdentifier.of(metalakeName, catalogName, schemaName, tableName),
                      TableChange.setProperty(TRANSIENT_LAST_DDL_TIME, "1234"));
            });
    Assertions.assertTrue(exception.getMessage().contains("cannot be set"));
  }

  @Test
  public void testHiveSchemaProperties() throws TException, InterruptedException {
    // test LOCATION property
    NameIdentifier schemaIdent =
        NameIdentifier.of(metalakeName, catalogName, GravitinoITUtils.genRandomName(SCHEMA_PREFIX));
    Map<String, String> properties = Maps.newHashMap();
    String expectedSchemaLocation =
        String.format(
            "hdfs://%s:%d/tmp",
            containerSuite.getHiveContainer().getContainerIpAddress(),
            HiveContainer.HDFS_DEFAULTFS_PORT);

    properties.put(HiveSchemaPropertiesMetadata.LOCATION, expectedSchemaLocation);
    catalog.asSchemas().createSchema(schemaIdent, "comment", properties);

    Database actualSchema = hiveClientPool.run(client -> client.getDatabase(schemaIdent.name()));
    String actualSchemaLocation = actualSchema.getLocationUri();
    Assertions.assertTrue(actualSchemaLocation.endsWith(expectedSchemaLocation));

    NameIdentifier tableIdent =
        NameIdentifier.of(
            metalakeName,
            catalogName,
            schemaIdent.name(),
            GravitinoITUtils.genRandomName(TABLE_PREFIX));
    catalog
        .asTableCatalog()
        .createTable(
            tableIdent,
            createColumns(),
            TABLE_COMMENT,
            ImmutableMap.of(),
            Transforms.EMPTY_TRANSFORM);
    org.apache.hadoop.hive.metastore.api.Table actualTable =
        hiveClientPool.run(client -> client.getTable(schemaIdent.name(), tableIdent.name()));
    String actualTableLocation = actualTable.getSd().getLocation();
    // use `tableIdent.name().toLowerCase()` because HMS will convert table name to lower
    String expectedTableLocation = expectedSchemaLocation + "/" + tableIdent.name().toLowerCase();
    Assertions.assertTrue(actualTableLocation.endsWith(expectedTableLocation));
    checkTableReadWrite(actualTable);
  }

  @Test
  public void testCreatePartitionedHiveTable() throws TException, InterruptedException {
    // Create table from Gravitino API
    ColumnDTO[] columns = createColumns();

    NameIdentifier nameIdentifier =
        NameIdentifier.of(metalakeName, catalogName, schemaName, tableName);
    Map<String, String> properties = createProperties();
    Table createdTable =
        catalog
            .asTableCatalog()
            .createTable(
                nameIdentifier,
                columns,
                TABLE_COMMENT,
                properties,
                new Transform[] {
                  IdentityPartitioningDTO.of(columns[1].name()),
                  IdentityPartitioningDTO.of(columns[2].name())
                });

    // Directly get table from hive metastore to check if the table is created successfully.
    org.apache.hadoop.hive.metastore.api.Table hiveTab =
        hiveClientPool.run(client -> client.getTable(schemaName, tableName));
    properties
        .keySet()
        .forEach(
            key -> Assertions.assertEquals(properties.get(key), hiveTab.getParameters().get(key)));
    assertTableEquals(createdTable, hiveTab);
    checkTableReadWrite(hiveTab);

    // test exception
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> {
              catalog
                  .asTableCatalog()
                  .createTable(
                      nameIdentifier,
                      columns,
                      TABLE_COMMENT,
                      properties,
                      new Transform[] {
                        IdentityPartitioningDTO.of(columns[0].name()),
                        IdentityPartitioningDTO.of(columns[1].name())
                      });
            });
    Assertions.assertTrue(
        exception
            .getMessage()
            .contains("The partition field must be placed at the end of the columns in order"));
  }

  private void assertTableEquals(
      Table createdTable, org.apache.hadoop.hive.metastore.api.Table hiveTab) {
    Distribution distribution = createdTable.distribution();
    SortOrder[] sortOrders = createdTable.sortOrder();

    List<FieldSchema> actualColumns = new ArrayList<>();
    actualColumns.addAll(hiveTab.getSd().getCols());
    actualColumns.addAll(hiveTab.getPartitionKeys());
    Assertions.assertEquals(schemaName.toLowerCase(), hiveTab.getDbName());
    Assertions.assertEquals(tableName.toLowerCase(), hiveTab.getTableName());
    Assertions.assertEquals("MANAGED_TABLE", hiveTab.getTableType());
    Assertions.assertEquals(createdTable.comment(), hiveTab.getParameters().get("comment"));

    Assertions.assertEquals(HIVE_COL_NAME1, actualColumns.get(0).getName());
    Assertions.assertEquals("tinyint", actualColumns.get(0).getType());
    Assertions.assertEquals("col_1_comment", actualColumns.get(0).getComment());

    Assertions.assertEquals(HIVE_COL_NAME2, actualColumns.get(1).getName());
    Assertions.assertEquals("date", actualColumns.get(1).getType());
    Assertions.assertEquals("col_2_comment", actualColumns.get(1).getComment());

    Assertions.assertEquals(HIVE_COL_NAME3, actualColumns.get(2).getName());
    Assertions.assertEquals("string", actualColumns.get(2).getType());
    Assertions.assertEquals("col_3_comment", actualColumns.get(2).getComment());

    Assertions.assertEquals(
        distribution == null ? 0 : distribution.number(), hiveTab.getSd().getNumBuckets());

    List<String> resultDistributionCols =
        distribution == null
            ? Collections.emptyList()
            : Arrays.stream(distribution.expressions())
                .map(t -> ((FieldReferenceDTO) t).fieldName()[0])
                .collect(Collectors.toList());
    Assertions.assertEquals(resultDistributionCols, hiveTab.getSd().getBucketCols());

    for (int i = 0; i < sortOrders.length; i++) {
      Assertions.assertEquals(
          sortOrders[i].direction() == SortDirection.ASCENDING ? 0 : 1,
          hiveTab.getSd().getSortCols().get(i).getOrder());
      Assertions.assertEquals(
          ((FieldReferenceDTO) sortOrders[i].expression()).fieldName()[0],
          hiveTab.getSd().getSortCols().get(i).getCol());
    }
    Assertions.assertNotNull(createdTable.partitioning());
    Assertions.assertEquals(createdTable.partitioning().length, hiveTab.getPartitionKeys().size());
    List<String> partitionKeys =
        Arrays.stream(createdTable.partitioning())
            .map(p -> ((Partitioning.SingleFieldPartitioning) p).fieldName()[0])
            .collect(Collectors.toList());
    List<String> hivePartitionKeys =
        hiveTab.getPartitionKeys().stream().map(FieldSchema::getName).collect(Collectors.toList());
    Assertions.assertEquals(partitionKeys, hivePartitionKeys);
  }

  @Test
  void testAlterUnknownTable() {
    NameIdentifier identifier = NameIdentifier.of(metalakeName, catalogName, schemaName, "unknown");
    Assertions.assertThrows(
        NoSuchTableException.class,
        () -> {
          catalog.asTableCatalog().alterTable(identifier, TableChange.updateComment("new_comment"));
        });
  }

  @Test
  public void testAlterHiveTable() throws TException, InterruptedException {
    ColumnDTO[] columns = createColumns();
    Table createdTable =
        catalog
            .asTableCatalog()
            .createTable(
                NameIdentifier.of(metalakeName, catalogName, schemaName, tableName),
                columns,
                TABLE_COMMENT,
                createProperties(),
                new Transform[] {IdentityPartitioningDTO.of(columns[2].name())});
    Assertions.assertNull(createdTable.auditInfo().lastModifier());
    Assertions.assertEquals(AuthConstants.ANONYMOUS_USER, createdTable.auditInfo().creator());
    Table alteredTable =
        catalog
            .asTableCatalog()
            .alterTable(
                NameIdentifier.of(metalakeName, catalogName, schemaName, tableName),
                TableChange.rename(ALTER_TABLE_NAME),
                TableChange.updateComment(TABLE_COMMENT + "_new"),
                TableChange.removeProperty("key1"),
                TableChange.setProperty("key2", "val2_new"),
                TableChange.addColumn(
                    new String[] {"col_4"},
                    Types.StringType.get(),
                    null,
                    TableChange.ColumnPosition.after(columns[1].name())),
                TableChange.renameColumn(new String[] {HIVE_COL_NAME2}, "col_2_new"),
                TableChange.updateColumnComment(new String[] {HIVE_COL_NAME1}, "comment_new"),
                TableChange.updateColumnType(
                    new String[] {HIVE_COL_NAME1}, Types.IntegerType.get()));
    Assertions.assertEquals(AuthConstants.ANONYMOUS_USER, alteredTable.auditInfo().creator());
    Assertions.assertEquals(AuthConstants.ANONYMOUS_USER, alteredTable.auditInfo().lastModifier());
    // Direct get table from hive metastore to check if the table is altered successfully.
    org.apache.hadoop.hive.metastore.api.Table hiveTab =
        hiveClientPool.run(client -> client.getTable(schemaName, ALTER_TABLE_NAME));
    Assertions.assertEquals(schemaName.toLowerCase(), hiveTab.getDbName());
    Assertions.assertEquals(ALTER_TABLE_NAME, hiveTab.getTableName());
    Assertions.assertEquals("val2_new", hiveTab.getParameters().get("key2"));

    Assertions.assertEquals(HIVE_COL_NAME1, hiveTab.getSd().getCols().get(0).getName());
    Assertions.assertEquals("int", hiveTab.getSd().getCols().get(0).getType());
    Assertions.assertEquals("comment_new", hiveTab.getSd().getCols().get(0).getComment());

    Assertions.assertEquals("col_2_new", hiveTab.getSd().getCols().get(1).getName());
    Assertions.assertEquals("date", hiveTab.getSd().getCols().get(1).getType());
    Assertions.assertEquals("col_2_comment", hiveTab.getSd().getCols().get(1).getComment());

    Assertions.assertEquals("col_4", hiveTab.getSd().getCols().get(2).getName());
    Assertions.assertEquals("string", hiveTab.getSd().getCols().get(2).getType());
    Assertions.assertNull(hiveTab.getSd().getCols().get(2).getComment());

    Assertions.assertEquals(1, hiveTab.getPartitionKeys().size());
    Assertions.assertEquals(columns[2].name(), hiveTab.getPartitionKeys().get(0).getName());
    assertDefaultTableProperties(alteredTable, hiveTab);
    checkTableReadWrite(hiveTab);

    // test alter partition column exception
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> {
              catalog
                  .asTableCatalog()
                  .alterTable(
                      NameIdentifier.of(metalakeName, catalogName, schemaName, ALTER_TABLE_NAME),
                      TableChange.updateColumnType(
                          new String[] {HIVE_COL_NAME3}, Types.IntegerType.get()));
            });
    Assertions.assertTrue(exception.getMessage().contains("Cannot alter partition column"));

    // test updateColumnPosition exception
    ColumnDTO col1 =
        new ColumnDTO.Builder()
            .withName("name")
            .withDataType(Types.StringType.get())
            .withComment("comment")
            .build();
    ColumnDTO col2 =
        new ColumnDTO.Builder()
            .withName("address")
            .withDataType(Types.StringType.get())
            .withComment("comment")
            .build();
    ColumnDTO col3 =
        new ColumnDTO.Builder()
            .withName("date_of_birth")
            .withDataType(Types.DateType.get())
            .withComment("comment")
            .build();
    ColumnDTO[] newColumns = new ColumnDTO[] {col1, col2, col3};
    NameIdentifier tableIdentifier =
        NameIdentifier.of(
            metalakeName,
            catalogName,
            schemaName,
            GravitinoITUtils.genRandomName("CatalogHiveIT_table"));
    catalog
        .asTableCatalog()
        .createTable(
            tableIdentifier,
            newColumns,
            TABLE_COMMENT,
            ImmutableMap.of(),
            Transforms.EMPTY_TRANSFORM,
            Distributions.NONE,
            new SortOrder[0]);

    exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                catalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.updateColumnPosition(
                            new String[] {"date_of_birth"}, TableChange.ColumnPosition.first())));
    Assertions.assertTrue(
        exception
            .getMessage()
            .contains(
                "please ensure that the type of the new column position is compatible with the old one"));
  }

  private void assertDefaultTableProperties(
      Table gravitinoReturnTable, org.apache.hadoop.hive.metastore.api.Table actualTable) {
    HiveTablePropertiesMetadata tablePropertiesMetadata = new HiveTablePropertiesMetadata();
    Assertions.assertEquals(
        tablePropertiesMetadata.getDefaultValue(SERDE_LIB),
        actualTable.getSd().getSerdeInfo().getSerializationLib());
    Assertions.assertEquals(
        tablePropertiesMetadata.getDefaultValue(INPUT_FORMAT),
        actualTable.getSd().getInputFormat());
    Assertions.assertEquals(
        tablePropertiesMetadata.getDefaultValue(OUTPUT_FORMAT),
        actualTable.getSd().getOutputFormat());
    Assertions.assertEquals(
        ((TableType) tablePropertiesMetadata.getDefaultValue(TABLE_TYPE)).name(),
        actualTable.getTableType());
    Assertions.assertEquals(tableName, actualTable.getSd().getSerdeInfo().getName());
    Assertions.assertEquals(
        ((Boolean) tablePropertiesMetadata.getDefaultValue(EXTERNAL)).toString().toUpperCase(),
        actualTable.getParameters().get(EXTERNAL));
    Assertions.assertNotNull(actualTable.getParameters().get(COMMENT));
    Assertions.assertNotNull(actualTable.getSd().getLocation());
    Assertions.assertNotNull(gravitinoReturnTable.properties().get(TRANSIENT_LAST_DDL_TIME));
  }

  @Test
  public void testDropHiveTable() {
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(metalakeName, catalogName, schemaName, tableName),
            createColumns(),
            TABLE_COMMENT,
            createProperties(),
            Transforms.EMPTY_TRANSFORM);
    catalog
        .asTableCatalog()
        .dropTable(NameIdentifier.of(metalakeName, catalogName, schemaName, ALTER_TABLE_NAME));

    // Directly get table from hive metastore to check if the table is dropped successfully.
    assertThrows(
        NoSuchObjectException.class,
        () -> hiveClientPool.run(client -> client.getTable(schemaName, ALTER_TABLE_NAME)));
  }

  @Test
  public void testAlterSchema() throws TException, InterruptedException {
    NameIdentifier ident = NameIdentifier.of(metalakeName, catalogName, schemaName);

    GravitinoMetaLake metalake = client.loadMetalake(NameIdentifier.of(metalakeName));
    Catalog catalog = metalake.loadCatalog(NameIdentifier.of(metalakeName, catalogName));
    Schema schema = catalog.asSchemas().loadSchema(ident);
    Assertions.assertNull(schema.auditInfo().lastModifier());
    Assertions.assertEquals(AuthConstants.ANONYMOUS_USER, schema.auditInfo().creator());
    schema =
        catalog
            .asSchemas()
            .alterSchema(
                ident,
                SchemaChange.removeProperty("key1"),
                SchemaChange.setProperty("key2", "val2-alter"));

    Assertions.assertEquals(AuthConstants.ANONYMOUS_USER, schema.auditInfo().lastModifier());
    Assertions.assertEquals(AuthConstants.ANONYMOUS_USER, schema.auditInfo().creator());

    Map<String, String> properties2 = catalog.asSchemas().loadSchema(ident).properties();
    Assertions.assertFalse(properties2.containsKey("key1"));
    Assertions.assertEquals("val2-alter", properties2.get("key2"));

    Database database = hiveClientPool.run(client -> client.getDatabase(schemaName));
    Map<String, String> properties3 = database.getParameters();
    Assertions.assertFalse(properties3.containsKey("key1"));
    Assertions.assertEquals("val2-alter", properties3.get("key2"));
  }

  @Test
  void testLoadEntityWithSamePrefix() {
    GravitinoMetaLake metalake = client.loadMetalake(NameIdentifier.of(metalakeName));
    Catalog catalog = metalake.loadCatalog(NameIdentifier.of(metalakeName, catalogName));
    Assertions.assertNotNull(catalog);

    for (int i = 1; i < metalakeName.length(); i++) {
      // We can't get the metalake by prefix
      final int length = i;
      Assertions.assertThrows(
          NoSuchMetalakeException.class,
          () -> client.loadMetalake(NameIdentifier.of(metalakeName.substring(0, length))));
    }
    Assertions.assertThrows(
        NoSuchMetalakeException.class,
        () -> client.loadMetalake(NameIdentifier.of(metalakeName + "a")));

    for (int i = 1; i < catalogName.length(); i++) {
      // We can't get the catalog by prefix
      final int length = i;
      Assertions.assertThrows(
          NoSuchCatalogException.class,
          () ->
              metalake.loadCatalog(
                  NameIdentifier.of(metalakeName, catalogName.substring(0, length))));
    }

    // We can't load the catalog.
    Assertions.assertThrows(
        NoSuchCatalogException.class,
        () -> metalake.loadCatalog(NameIdentifier.of(metalakeName, catalogName + "a")));

    for (int i = 1; i < schemaName.length(); i++) {
      // We can't get the schema by prefix
      final int length = i;
      Assertions.assertThrows(
          NoSuchSchemaException.class,
          () ->
              catalog
                  .asSchemas()
                  .loadSchema(
                      NameIdentifier.of(
                          metalakeName, catalogName, schemaName.substring(0, length))));
    }

    Assertions.assertThrows(
        NoSuchSchemaException.class,
        () ->
            catalog
                .asSchemas()
                .loadSchema(NameIdentifier.of(metalakeName, catalogName, schemaName + "a")));

    for (int i = 1; i < tableName.length(); i++) {
      // We can't get the table by prefix
      final int length = i;
      Assertions.assertThrows(
          NoSuchTableException.class,
          () ->
              catalog
                  .asTableCatalog()
                  .loadTable(
                      NameIdentifier.of(
                          metalakeName, catalogName, schemaName, tableName.substring(0, length))));
    }

    Assertions.assertThrows(
        NoSuchTableException.class,
        () ->
            catalog
                .asTableCatalog()
                .loadTable(
                    NameIdentifier.of(metalakeName, catalogName, schemaName, tableName + "a")));
  }

  @Test
  void testAlterEntityName() {
    String metalakeName = GravitinoITUtils.genRandomName("CatalogHiveIT_metalake");
    client.createMetalake(NameIdentifier.of(metalakeName), "", ImmutableMap.of());
    final GravitinoMetaLake metalake = client.loadMetalake(NameIdentifier.of(metalakeName));
    String newMetalakeName = GravitinoITUtils.genRandomName("CatalogHiveIT_metalake_new");

    // Test rename metalake
    for (int i = 0; i < 2; i++) {
      Assertions.assertThrows(
          NoSuchMetalakeException.class,
          () -> client.loadMetalake(NameIdentifier.of(newMetalakeName)));
      client.alterMetalake(NameIdentifier.of(metalakeName), MetalakeChange.rename(newMetalakeName));
      client.loadMetalake(NameIdentifier.of(newMetalakeName));
      Assertions.assertThrows(
          NoSuchMetalakeException.class,
          () -> client.loadMetalake(NameIdentifier.of(metalakeName)));

      client.alterMetalake(NameIdentifier.of(newMetalakeName), MetalakeChange.rename(metalakeName));
      client.loadMetalake(NameIdentifier.of(metalakeName));
      Assertions.assertThrows(
          NoSuchMetalakeException.class,
          () -> client.loadMetalake(NameIdentifier.of(newMetalakeName)));
    }

    String catalogName = GravitinoITUtils.genRandomName("CatalogHiveIT_catalog");
    metalake.createCatalog(
        NameIdentifier.of(metalakeName, catalogName),
        Catalog.Type.RELATIONAL,
        provider,
        "comment",
        ImmutableMap.of(METASTORE_URIS, HIVE_METASTORE_URIS));

    Catalog catalog = metalake.loadCatalog(NameIdentifier.of(metalakeName, catalogName));
    // Test rename catalog
    String newCatalogName = GravitinoITUtils.genRandomName("CatalogHiveIT_catalog_new");
    for (int i = 0; i < 2; i++) {
      Assertions.assertThrows(
          NoSuchCatalogException.class,
          () -> metalake.loadCatalog(NameIdentifier.of(metalakeName, newMetalakeName)));
      metalake.alterCatalog(
          NameIdentifier.of(metalakeName, catalogName), CatalogChange.rename(newCatalogName));
      metalake.loadCatalog(NameIdentifier.of(metalakeName, newCatalogName));
      Assertions.assertThrows(
          NoSuchCatalogException.class,
          () -> metalake.loadCatalog(NameIdentifier.of(metalakeName, catalogName)));

      metalake.alterCatalog(
          NameIdentifier.of(metalakeName, newCatalogName), CatalogChange.rename(catalogName));
      catalog = metalake.loadCatalog(NameIdentifier.of(metalakeName, catalogName));
      Assertions.assertThrows(
          NoSuchCatalogException.class,
          () -> metalake.loadCatalog(NameIdentifier.of(metalakeName, newMetalakeName)));
    }

    // Schema does not have the rename operation.
    final String schemaName = GravitinoITUtils.genRandomName("CatalogHiveIT_schema");
    catalog
        .asSchemas()
        .createSchema(
            NameIdentifier.of(metalakeName, catalogName, schemaName), "", ImmutableMap.of());

    final Catalog cata = catalog;
    // Now try to rename table
    final String tableName = GravitinoITUtils.genRandomName("CatalogHiveIT_table");
    final String newTableName = GravitinoITUtils.genRandomName("CatalogHiveIT_table_new");
    ColumnDTO[] columns = createColumns();
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(metalakeName, catalogName, schemaName, tableName),
            columns,
            TABLE_COMMENT,
            createProperties(),
            Transforms.EMPTY_TRANSFORM);

    for (int i = 0; i < 2; i++) {
      // The table to be renamed does not exist
      Assertions.assertThrows(
          NoSuchTableException.class,
          () ->
              cata.asTableCatalog()
                  .loadTable(
                      NameIdentifier.of(metalakeName, catalogName, schemaName, newTableName)));
      catalog
          .asTableCatalog()
          .alterTable(
              NameIdentifier.of(metalakeName, catalogName, schemaName, tableName),
              TableChange.rename(newTableName));
      Table table =
          catalog
              .asTableCatalog()
              .loadTable(NameIdentifier.of(metalakeName, catalogName, schemaName, newTableName));
      Assertions.assertNotNull(table);

      // Old Table should not exist anymore.
      Assertions.assertThrows(
          NoSuchTableException.class,
          () ->
              cata.asTableCatalog()
                  .loadTable(NameIdentifier.of(metalakeName, catalogName, schemaName, tableName)));

      catalog
          .asTableCatalog()
          .alterTable(
              NameIdentifier.of(metalakeName, catalogName, schemaName, newTableName),
              TableChange.rename(tableName));
      table =
          catalog
              .asTableCatalog()
              .loadTable(NameIdentifier.of(metalakeName, catalogName, schemaName, tableName));
      Assertions.assertNotNull(table);
    }
  }

  @Test
  void testDropAndRename() {
    String metalakeName1 = GravitinoITUtils.genRandomName("CatalogHiveIT_metalake1");
    String metalakeName2 = GravitinoITUtils.genRandomName("CatalogHiveIT_metalake2");

    client.createMetalake(NameIdentifier.of(metalakeName1), "comment", Collections.emptyMap());
    client.createMetalake(NameIdentifier.of(metalakeName2), "comment", Collections.emptyMap());

    client.dropMetalake(NameIdentifier.of(metalakeName1));
    client.dropMetalake(NameIdentifier.of(metalakeName2));

    client.createMetalake(NameIdentifier.of(metalakeName1), "comment", Collections.emptyMap());

    client.alterMetalake(NameIdentifier.of(metalakeName1), MetalakeChange.rename(metalakeName2));

    client.loadMetalake(NameIdentifier.of(metalakeName2));

    Assertions.assertThrows(
        NoSuchMetalakeException.class, () -> client.loadMetalake(NameIdentifier.of(metalakeName1)));
  }

  @Test
  public void testDropHiveManagedTable() throws TException, InterruptedException, IOException {
    ColumnDTO[] columns = createColumns();
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(metalakeName, catalogName, schemaName, tableName),
            columns,
            TABLE_COMMENT,
            createProperties(),
            new Transform[] {IdentityPartitioningDTO.of(columns[2].name())});
    // Directly get table from hive metastore to check if the table is created successfully.
    org.apache.hadoop.hive.metastore.api.Table hiveTab =
        hiveClientPool.run(client -> client.getTable(schemaName, tableName));
    checkTableReadWrite(hiveTab);
    Assertions.assertEquals(MANAGED_TABLE.name(), hiveTab.getTableType());
    Path tableDirectory = new Path(hiveTab.getSd().getLocation());
    catalog
        .asTableCatalog()
        .dropTable(NameIdentifier.of(metalakeName, catalogName, schemaName, tableName));
    Boolean existed = hiveClientPool.run(client -> client.tableExists(schemaName, tableName));
    Assertions.assertFalse(existed, "The hive table should not exist");
    Assertions.assertFalse(hdfs.exists(tableDirectory), "The table directory should not exist");
  }

  @Test
  public void testDropHiveExternalTable() throws TException, InterruptedException, IOException {
    ColumnDTO[] columns = createColumns();
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(metalakeName, catalogName, schemaName, tableName),
            columns,
            TABLE_COMMENT,
            ImmutableMap.of(TABLE_TYPE, EXTERNAL_TABLE.name().toLowerCase(Locale.ROOT)),
            new Transform[] {IdentityPartitioningDTO.of(columns[2].name())});
    // Directly get table from hive metastore to check if the table is created successfully.
    org.apache.hadoop.hive.metastore.api.Table hiveTab =
        hiveClientPool.run(client -> client.getTable(schemaName, tableName));
    checkTableReadWrite(hiveTab);
    Assertions.assertEquals(EXTERNAL_TABLE.name(), hiveTab.getTableType());
    catalog
        .asTableCatalog()
        .dropTable(NameIdentifier.of(metalakeName, catalogName, schemaName, tableName));

    Boolean existed = hiveClientPool.run(client -> client.tableExists(schemaName, tableName));
    Assertions.assertFalse(existed, "The table should be not exist");
    Path tableDirectory = new Path(hiveTab.getSd().getLocation());
    Assertions.assertTrue(
        hdfs.listStatus(tableDirectory).length > 0, "The table should not be empty");
  }

  @Test
  public void testPurgeHiveManagedTable() throws TException, InterruptedException, IOException {
    ColumnDTO[] columns = createColumns();
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(metalakeName, catalogName, schemaName, tableName),
            columns,
            TABLE_COMMENT,
            createProperties(),
            new Transform[] {IdentityPartitioningDTO.of(columns[2].name())});
    // Directly get table from hive metastore to check if the table is created successfully.
    org.apache.hadoop.hive.metastore.api.Table hiveTab =
        hiveClientPool.run(client -> client.getTable(schemaName, tableName));
    checkTableReadWrite(hiveTab);
    Assertions.assertEquals(MANAGED_TABLE.name(), hiveTab.getTableType());
    catalog
        .asTableCatalog()
        .purgeTable(NameIdentifier.of(metalakeName, catalogName, schemaName, tableName));
    Boolean existed = hiveClientPool.run(client -> client.tableExists(schemaName, tableName));
    Assertions.assertFalse(existed, "The hive table should not exist");
    Path tableDirectory = new Path(hiveTab.getSd().getLocation());
    Assertions.assertFalse(hdfs.exists(tableDirectory), "The table directory should not exist");
    Path trashDirectory = hdfs.getTrashRoot(tableDirectory);
    Assertions.assertFalse(hdfs.exists(trashDirectory), "The trash should not exist");
  }

  @Test
  public void testPurgeHiveExternalTable() throws TException, InterruptedException, IOException {
    ColumnDTO[] columns = createColumns();
    catalog
        .asTableCatalog()
        .createTable(
            NameIdentifier.of(metalakeName, catalogName, schemaName, tableName),
            columns,
            TABLE_COMMENT,
            ImmutableMap.of(TABLE_TYPE, EXTERNAL_TABLE.name().toLowerCase(Locale.ROOT)),
            new Transform[] {IdentityPartitioningDTO.of(columns[2].name())});
    // Directly get table from hive metastore to check if the table is created successfully.
    org.apache.hadoop.hive.metastore.api.Table hiveTab =
        hiveClientPool.run(client -> client.getTable(schemaName, tableName));
    checkTableReadWrite(hiveTab);
    Assertions.assertEquals(EXTERNAL_TABLE.name(), hiveTab.getTableType());
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> {
          catalog
              .asTableCatalog()
              .purgeTable(NameIdentifier.of(metalakeName, catalogName, schemaName, tableName));
        },
        "Can't purge a external hive table");

    Boolean existed = hiveClientPool.run(client -> client.tableExists(schemaName, tableName));
    Assertions.assertTrue(existed, "The table should be still exist");
    Path tableDirectory = new Path(hiveTab.getSd().getLocation());
    Assertions.assertTrue(
        hdfs.listStatus(tableDirectory).length > 0, "The table should not be empty");
  }
}
