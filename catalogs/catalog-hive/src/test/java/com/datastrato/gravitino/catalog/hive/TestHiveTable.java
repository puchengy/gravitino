/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.hive;

import static com.datastrato.gravitino.catalog.BaseCatalog.CATALOG_BYPASS_PREFIX;
import static com.datastrato.gravitino.catalog.hive.HiveCatalogPropertiesMeta.METASTORE_URIS;
import static com.datastrato.gravitino.catalog.hive.HiveTablePropertiesMetadata.TABLE_TYPE;
import static com.datastrato.gravitino.rel.expressions.transforms.Transforms.day;
import static com.datastrato.gravitino.rel.expressions.transforms.Transforms.identity;
import static org.apache.hadoop.hive.metastore.TableType.EXTERNAL_TABLE;

import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.Namespace;
import com.datastrato.gravitino.catalog.hive.miniHMS.MiniHiveMetastoreService;
import com.datastrato.gravitino.exceptions.NoSuchSchemaException;
import com.datastrato.gravitino.exceptions.TableAlreadyExistsException;
import com.datastrato.gravitino.meta.AuditInfo;
import com.datastrato.gravitino.meta.CatalogEntity;
import com.datastrato.gravitino.rel.Column;
import com.datastrato.gravitino.rel.Table;
import com.datastrato.gravitino.rel.TableChange;
import com.datastrato.gravitino.rel.expressions.NamedReference;
import com.datastrato.gravitino.rel.expressions.distributions.Distribution;
import com.datastrato.gravitino.rel.expressions.distributions.Distributions;
import com.datastrato.gravitino.rel.expressions.literals.Literals;
import com.datastrato.gravitino.rel.expressions.sorts.NullOrdering;
import com.datastrato.gravitino.rel.expressions.sorts.SortDirection;
import com.datastrato.gravitino.rel.expressions.sorts.SortOrder;
import com.datastrato.gravitino.rel.expressions.sorts.SortOrders;
import com.datastrato.gravitino.rel.expressions.transforms.Transform;
import com.datastrato.gravitino.rel.expressions.transforms.Transforms;
import com.datastrato.gravitino.rel.types.Types;
import com.google.common.collect.Maps;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import org.apache.hadoop.hive.conf.HiveConf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestHiveTable extends MiniHiveMetastoreService {

  private static final String META_LAKE_NAME = "metalake";

  private static final String HIVE_CATALOG_NAME = "test_catalog";
  private static final String HIVE_SCHEMA_NAME = "test_schema";
  private static final String HIVE_COMMENT = "test_comment";
  private static HiveCatalog hiveCatalog;
  private static HiveSchema hiveSchema;
  private static final NameIdentifier schemaIdent =
      NameIdentifier.of(META_LAKE_NAME, HIVE_CATALOG_NAME, HIVE_SCHEMA_NAME);

  @BeforeAll
  private static void setup() {
    initHiveCatalog();
    initHiveSchema();
  }

  @AfterEach
  private void resetSchema() {
    hiveCatalog.asSchemas().dropSchema(schemaIdent, true);
    initHiveSchema();
  }

  private static void initHiveSchema() {
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");

    hiveSchema =
        (HiveSchema) hiveCatalog.asSchemas().createSchema(schemaIdent, HIVE_COMMENT, properties);
  }

  private static void initHiveCatalog() {
    AuditInfo auditInfo =
        new AuditInfo.Builder().withCreator("testHiveUser").withCreateTime(Instant.now()).build();

    CatalogEntity entity =
        new CatalogEntity.Builder()
            .withId(1L)
            .withName(HIVE_CATALOG_NAME)
            .withNamespace(Namespace.of(META_LAKE_NAME))
            .withType(HiveCatalog.Type.RELATIONAL)
            .withProvider("hive")
            .withAuditInfo(auditInfo)
            .build();

    Map<String, String> conf = Maps.newHashMap();
    metastore.hiveConf().forEach(e -> conf.put(e.getKey(), e.getValue()));

    conf.put(METASTORE_URIS, hiveConf.get(HiveConf.ConfVars.METASTOREURIS.varname));
    conf.put(
        CATALOG_BYPASS_PREFIX + HiveConf.ConfVars.METASTOREWAREHOUSE.varname,
        hiveConf.get(HiveConf.ConfVars.METASTOREWAREHOUSE.varname));
    conf.put(
        CATALOG_BYPASS_PREFIX
            + HiveConf.ConfVars.METASTORE_DISALLOW_INCOMPATIBLE_COL_TYPE_CHANGES.varname,
        hiveConf.get(HiveConf.ConfVars.METASTORE_DISALLOW_INCOMPATIBLE_COL_TYPE_CHANGES.varname));

    conf.put(
        CATALOG_BYPASS_PREFIX + HiveConf.ConfVars.HIVE_IN_TEST.varname,
        hiveConf.get(HiveConf.ConfVars.HIVE_IN_TEST.varname));

    hiveCatalog = new HiveCatalog().withCatalogConf(conf).withCatalogEntity(entity);
  }

  private Distribution createDistribution() {
    return Distributions.hash(10, NamedReference.field("col_1"));
  }

  private SortOrder[] createSortOrder() {
    return new SortOrders.SortImpl[] {
      SortOrders.of(
          NamedReference.field("col_2"), SortDirection.DESCENDING, NullOrdering.NULLS_FIRST)
    };
  }

  @Test
  public void testCreateHiveTable() {
    String hiveTableName = "test_hive_table";
    NameIdentifier tableIdentifier =
        NameIdentifier.of(META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), hiveTableName);
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");

    HiveColumn col1 =
        new HiveColumn.Builder()
            .withName("col_1")
            .withType(Types.ByteType.get())
            .withComment(HIVE_COMMENT)
            .build();
    HiveColumn col2 =
        new HiveColumn.Builder()
            .withName("col_2")
            .withType(Types.DateType.get())
            .withComment(HIVE_COMMENT)
            .build();
    Column[] columns = new Column[] {col1, col2};

    Distribution distribution = createDistribution();
    SortOrder[] sortOrders = createSortOrder();

    Table table =
        hiveCatalog
            .asTableCatalog()
            .createTable(
                tableIdentifier,
                columns,
                HIVE_COMMENT,
                properties,
                new Transform[0],
                distribution,
                sortOrders);
    Assertions.assertEquals(tableIdentifier.name(), table.name());
    Assertions.assertEquals(HIVE_COMMENT, table.comment());
    Assertions.assertEquals("val1", table.properties().get("key1"));
    Assertions.assertEquals("val2", table.properties().get("key2"));

    Table loadedTable = hiveCatalog.asTableCatalog().loadTable(tableIdentifier);
    Assertions.assertEquals(table.auditInfo().creator(), loadedTable.auditInfo().creator());
    Assertions.assertNull(loadedTable.auditInfo().lastModifier());
    Assertions.assertNull(loadedTable.auditInfo().lastModifiedTime());

    Assertions.assertEquals("val1", loadedTable.properties().get("key1"));
    Assertions.assertEquals("val2", loadedTable.properties().get("key2"));

    Assertions.assertTrue(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
    NameIdentifier[] tableIdents =
        hiveCatalog.asTableCatalog().listTables(tableIdentifier.namespace());
    Assertions.assertTrue(Arrays.asList(tableIdents).contains(tableIdentifier));

    // Compare sort and order
    Assertions.assertEquals(distribution.number(), loadedTable.distribution().number());
    Assertions.assertArrayEquals(
        distribution.expressions(), loadedTable.distribution().expressions());

    Assertions.assertEquals(sortOrders.length, loadedTable.sortOrder().length);
    for (int i = 0; i < loadedTable.sortOrder().length; i++) {
      Assertions.assertEquals(sortOrders[i].direction(), loadedTable.sortOrder()[i].direction());
      Assertions.assertEquals(sortOrders[i].expression(), loadedTable.sortOrder()[i].expression());
    }

    // Test exception
    Throwable exception =
        Assertions.assertThrows(
            TableAlreadyExistsException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .createTable(
                        tableIdentifier,
                        columns,
                        HIVE_COMMENT,
                        properties,
                        new Transform[0],
                        distribution,
                        sortOrders));
    Assertions.assertTrue(exception.getMessage().contains("Table already exists"));

    HiveColumn illegalColumn =
        new HiveColumn.Builder()
            .withName("col_3")
            .withType(Types.ByteType.get())
            .withComment(HIVE_COMMENT)
            .withNullable(false)
            .build();

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .createTable(
                        tableIdentifier,
                        new Column[] {illegalColumn},
                        HIVE_COMMENT,
                        properties,
                        new Transform[0],
                        distribution,
                        sortOrders));
    Assertions.assertTrue(
        exception
            .getMessage()
            .contains(
                "The NOT NULL constraint for column is only supported since Hive 3.0, "
                    + "but the current Gravitino Hive catalog only supports Hive 2.x"));

    HiveColumn withDefault =
        new HiveColumn.Builder()
            .withName("col_3")
            .withType(Types.ByteType.get())
            .withComment(HIVE_COMMENT)
            .withNullable(true)
            .withDefaultValue(Literals.NULL)
            .build();
    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .createTable(
                        tableIdentifier,
                        new Column[] {withDefault},
                        HIVE_COMMENT,
                        properties,
                        Transforms.EMPTY_TRANSFORM,
                        distribution,
                        sortOrders));
    Assertions.assertTrue(
        exception
            .getMessage()
            .contains(
                "The DEFAULT constraint for column is only supported since Hive 3.0, "
                    + "but the current Gravitino Hive catalog only supports Hive 2.x"),
        "The exception message is: " + exception.getMessage());
  }

  @Test
  public void testCreatePartitionedHiveTable() {
    NameIdentifier tableIdentifier =
        NameIdentifier.of(META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), genRandomName());
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");

    HiveColumn col1 =
        new HiveColumn.Builder()
            .withName("city")
            .withType(Types.ByteType.get())
            .withComment(HIVE_COMMENT)
            .build();
    HiveColumn col2 =
        new HiveColumn.Builder()
            .withName("dt")
            .withType(Types.DateType.get())
            .withComment(HIVE_COMMENT)
            .build();
    Column[] columns = new Column[] {col1, col2};

    Transform[] partitions = new Transform[] {identity(col2.name())};

    Table table =
        hiveCatalog
            .asTableCatalog()
            .createTable(tableIdentifier, columns, HIVE_COMMENT, properties, partitions);
    Assertions.assertEquals(tableIdentifier.name(), table.name());
    Assertions.assertEquals(HIVE_COMMENT, table.comment());
    Assertions.assertEquals("val1", table.properties().get("key1"));
    Assertions.assertEquals("val2", table.properties().get("key2"));
    Assertions.assertArrayEquals(partitions, table.partitioning());

    Table loadedTable = hiveCatalog.asTableCatalog().loadTable(tableIdentifier);

    Assertions.assertEquals(table.auditInfo().creator(), loadedTable.auditInfo().creator());
    Assertions.assertNull(loadedTable.auditInfo().lastModifier());
    Assertions.assertNull(loadedTable.auditInfo().lastModifiedTime());

    Assertions.assertEquals("val1", loadedTable.properties().get("key1"));
    Assertions.assertEquals("val2", loadedTable.properties().get("key2"));
    Assertions.assertArrayEquals(partitions, loadedTable.partitioning());

    Assertions.assertTrue(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
    NameIdentifier[] tableIdents =
        hiveCatalog.asTableCatalog().listTables(tableIdentifier.namespace());
    Assertions.assertTrue(Arrays.asList(tableIdents).contains(tableIdentifier));

    // Test exception
    Throwable exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .createTable(
                        tableIdentifier,
                        columns,
                        HIVE_COMMENT,
                        properties,
                        new Transform[] {day(new String[] {col2.name()})}));
    Assertions.assertTrue(
        exception.getMessage().contains("Hive partition only supports identity transform"));

    exception =
        Assertions.assertThrows(
            RuntimeException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .createTable(
                        NameIdentifier.of(
                            META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), genRandomName()),
                        columns,
                        HIVE_COMMENT,
                        properties,
                        new Transform[] {identity(new String[] {col1.name(), col2.name()})}));
    Assertions.assertTrue(
        exception.getMessage().contains("Hive partition does not support nested field"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .createTable(
                        tableIdentifier,
                        columns,
                        HIVE_COMMENT,
                        properties,
                        new Transform[] {identity(new String[] {col1.name()})}));
    Assertions.assertEquals(
        "The partition field must be placed at the end of the columns in order",
        exception.getMessage());
  }

  @Test
  public void testDropHiveTable() {
    NameIdentifier tableIdentifier =
        NameIdentifier.of(META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), genRandomName());
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");

    HiveColumn col1 =
        new HiveColumn.Builder()
            .withName("col_1")
            .withType(Types.ByteType.get())
            .withComment(HIVE_COMMENT)
            .build();
    HiveColumn col2 =
        new HiveColumn.Builder()
            .withName("col_2")
            .withType(Types.DateType.get())
            .withComment(HIVE_COMMENT)
            .build();
    Column[] columns = new Column[] {col1, col2};

    hiveCatalog
        .asTableCatalog()
        .createTable(
            tableIdentifier,
            columns,
            HIVE_COMMENT,
            properties,
            new Transform[0],
            Distributions.NONE,
            new SortOrder[0]);

    Assertions.assertTrue(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
    hiveCatalog.asTableCatalog().dropTable(tableIdentifier);
    Assertions.assertFalse(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
  }

  @Test
  public void testListTableException() {
    Namespace tableNs = Namespace.of("metalake", hiveCatalog.name(), "not_exist_db");
    Throwable exception =
        Assertions.assertThrows(
            NoSuchSchemaException.class, () -> hiveCatalog.asTableCatalog().listTables(tableNs));
    Assertions.assertTrue(exception.getMessage().contains("Schema (database) does not exist"));
  }

  @Test
  public void testAlterHiveTable() {
    // create a table with random name
    NameIdentifier tableIdentifier =
        NameIdentifier.of(META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), genRandomName());
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");

    HiveColumn col1 =
        new HiveColumn.Builder()
            .withName("col_1")
            .withType(Types.ByteType.get())
            .withComment(HIVE_COMMENT)
            .build();
    HiveColumn col2 =
        new HiveColumn.Builder()
            .withName("col_2")
            .withType(Types.DateType.get())
            .withComment(HIVE_COMMENT)
            .build();
    Column[] columns = new Column[] {col1, col2};

    Distribution distribution = createDistribution();
    SortOrder[] sortOrders = createSortOrder();

    Table createdTable =
        hiveCatalog
            .asTableCatalog()
            .createTable(
                tableIdentifier,
                columns,
                HIVE_COMMENT,
                properties,
                new Transform[] {identity(col2.name())},
                distribution,
                sortOrders);
    Assertions.assertTrue(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));

    // test exception
    Throwable exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.updateColumnPosition(
                            new String[] {"not_exist_col"},
                            TableChange.ColumnPosition.after("col_1"))));
    Assertions.assertTrue(exception.getMessage().contains("UpdateColumnPosition does not exist"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.updateColumnPosition(
                            new String[] {"col_1"},
                            TableChange.ColumnPosition.after("not_exist_col"))));
    Assertions.assertTrue(exception.getMessage().contains("Column does not exist"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.updateColumnPosition(new String[] {"col_1"}, null)));
    Assertions.assertTrue(exception.getMessage().contains("Column position cannot be null"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.addColumn(
                            new String[] {"col_3"},
                            Types.ByteType.get(),
                            null,
                            TableChange.ColumnPosition.first(),
                            false)));
    Assertions.assertTrue(
        exception
            .getMessage()
            .contains(
                "The NOT NULL constraint for column is only supported since Hive 3.0, "
                    + "but the current Gravitino Hive catalog only supports Hive 2.x"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.addColumn(
                            new String[] {"col_3"},
                            Types.ByteType.get(),
                            TableChange.ColumnPosition.after(col2.name()))));
    Assertions.assertTrue(
        exception.getMessage().contains("Cannot add column after partition column"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.addColumn(
                            new String[] {col1.name()},
                            Types.ByteType.get(),
                            "comment",
                            TableChange.ColumnPosition.after(col1.name()))));
    Assertions.assertTrue(exception.getMessage().contains("Cannot add column with duplicate name"));

    exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () ->
                hiveCatalog
                    .asTableCatalog()
                    .alterTable(
                        tableIdentifier,
                        TableChange.updateColumnNullability(new String[] {"col_1"}, false)));
    Assertions.assertEquals(
        "Hive does not support altering column nullability", exception.getMessage());

    // test alter
    hiveCatalog
        .asTableCatalog()
        .alterTable(
            tableIdentifier,
            TableChange.rename("test_hive_table_new"),
            TableChange.updateComment(HIVE_COMMENT + "_new"),
            TableChange.removeProperty("key1"),
            TableChange.setProperty("key2", "val2_new"),
            // columns current format: [col_1:I8:comment, col_2:DATE:comment]
            TableChange.addColumn(new String[] {"col_3"}, Types.StringType.get()),
            // columns current format: [col_1:I8:comment, col_3:STRING:null, col_2:DATE:comment]
            TableChange.renameColumn(new String[] {"col_3"}, "col_3_new"),
            // columns current format: [col_1:I8:comment, col_3_new:STRING:null, col_2:DATE:comment]
            TableChange.updateColumnComment(new String[] {"col_1"}, HIVE_COMMENT + "_new"),
            // columns current format: [col_1:I8:comment_new, col_3_new:STRING:null,
            // col_2:DATE:comment]
            TableChange.updateColumnType(new String[] {"col_1"}, Types.IntegerType.get()),
            // columns current format: [col_1:I32:comment_new, col_3_new:STRING:null,
            // col_2:DATE:comment]
            TableChange.updateColumnPosition(
                new String[] {"col_3_new"}, TableChange.ColumnPosition.first())
            // columns current: [col_3_new:STRING:null, col_1:I32:comment_new, col_2:DATE:comment]
            );
    Table alteredTable =
        hiveCatalog
            .asTableCatalog()
            .loadTable(NameIdentifier.of(tableIdentifier.namespace(), "test_hive_table_new"));

    Assertions.assertEquals(HIVE_COMMENT + "_new", alteredTable.comment());
    Assertions.assertFalse(alteredTable.properties().containsKey("key1"));
    Assertions.assertEquals(alteredTable.properties().get("key2"), "val2_new");

    Assertions.assertEquals(createdTable.auditInfo().creator(), alteredTable.auditInfo().creator());
    Assertions.assertNull(alteredTable.auditInfo().lastModifier());
    Assertions.assertNull(alteredTable.auditInfo().lastModifiedTime());
    Assertions.assertNotNull(alteredTable.partitioning());
    Assertions.assertArrayEquals(createdTable.partitioning(), alteredTable.partitioning());

    Column[] expected =
        new Column[] {
          new HiveColumn.Builder()
              .withName("col_3_new")
              .withType(Types.StringType.get())
              .withComment(null)
              .build(),
          new HiveColumn.Builder()
              .withName("col_1")
              .withType(Types.IntegerType.get())
              .withComment(HIVE_COMMENT + "_new")
              .build(),
          new HiveColumn.Builder()
              .withName("col_2")
              .withType(Types.DateType.get())
              .withComment(HIVE_COMMENT)
              .build()
        };
    Assertions.assertArrayEquals(expected, alteredTable.columns());

    // test delete column change
    hiveCatalog
        .asTableCatalog()
        .alterTable(
            NameIdentifier.of(tableIdentifier.namespace(), "test_hive_table_new"),
            TableChange.deleteColumn(new String[] {"not_exist_col"}, true));

    hiveCatalog
        .asTableCatalog()
        .alterTable(
            NameIdentifier.of(tableIdentifier.namespace(), "test_hive_table_new"),
            TableChange.deleteColumn(new String[] {"col_1"}, false));
    Table alteredTable1 =
        hiveCatalog
            .asTableCatalog()
            .loadTable(NameIdentifier.of(tableIdentifier.namespace(), "test_hive_table_new"));
    expected =
        Arrays.stream(expected).filter(c -> !"col_1".equals(c.name())).toArray(Column[]::new);
    Assertions.assertArrayEquals(expected, alteredTable1.columns());

    Assertions.assertEquals(
        createdTable.auditInfo().creator(), alteredTable1.auditInfo().creator());
    Assertions.assertNull(alteredTable1.auditInfo().lastModifier());
    Assertions.assertNull(alteredTable1.auditInfo().lastModifiedTime());
    Assertions.assertNotNull(alteredTable.partitioning());
    Assertions.assertArrayEquals(createdTable.partitioning(), alteredTable.partitioning());
  }

  @Test
  public void testPurgeHiveTable() {
    String hiveTableName = "test_hive_table";
    NameIdentifier tableIdentifier =
        NameIdentifier.of(META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), hiveTableName);
    Map<String, String> properties = Maps.newHashMap();
    properties.put("key1", "val1");
    properties.put("key2", "val2");

    HiveColumn col1 =
        new HiveColumn.Builder()
            .withName("col_1")
            .withType(Types.ByteType.get())
            .withComment(HIVE_COMMENT)
            .build();
    HiveColumn col2 =
        new HiveColumn.Builder()
            .withName("col_2")
            .withType(Types.DateType.get())
            .withComment(HIVE_COMMENT)
            .build();
    Column[] columns = new Column[] {col1, col2};

    Distribution distribution = createDistribution();
    SortOrder[] sortOrders = createSortOrder();

    hiveCatalog
        .asTableCatalog()
        .createTable(
            tableIdentifier,
            columns,
            HIVE_COMMENT,
            properties,
            new Transform[0],
            distribution,
            sortOrders);
    Assertions.assertTrue(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
    hiveCatalog.asTableCatalog().purgeTable(tableIdentifier);
    Assertions.assertFalse(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
  }

  @Test
  public void testPurgeExternalHiveTable() {
    String hiveTableName = "test_hive_table";
    NameIdentifier tableIdentifier =
        NameIdentifier.of(META_LAKE_NAME, hiveCatalog.name(), hiveSchema.name(), hiveTableName);
    Map<String, String> properties = Maps.newHashMap();
    properties.put(TABLE_TYPE, EXTERNAL_TABLE.name().toLowerCase(Locale.ROOT));

    HiveColumn col1 =
        new HiveColumn.Builder()
            .withName("col_1")
            .withType(Types.ByteType.get())
            .withComment(HIVE_COMMENT)
            .build();
    HiveColumn col2 =
        new HiveColumn.Builder()
            .withName("col_2")
            .withType(Types.DateType.get())
            .withComment(HIVE_COMMENT)
            .build();
    Column[] columns = new Column[] {col1, col2};

    Distribution distribution = createDistribution();
    SortOrder[] sortOrders = createSortOrder();

    hiveCatalog
        .asTableCatalog()
        .createTable(
            tableIdentifier,
            columns,
            HIVE_COMMENT,
            properties,
            new Transform[0],
            distribution,
            sortOrders);
    Assertions.assertTrue(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
    Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> {
          hiveCatalog.asTableCatalog().purgeTable(tableIdentifier);
        },
        "Can't purge a external hive table");
    Assertions.assertTrue(hiveCatalog.asTableCatalog().tableExists(tableIdentifier));
  }
}
