call gravitino.system.create_catalog(
    'gt_mysql',
    'jdbc-mysql',
    map(
        array['jdbc-url', 'jdbc-user', 'jdbc-password', 'jdbc-driver'],
        array['${mysql_uri}/?useSSL=false', 'trino', 'ds123', 'com.mysql.cj.jdbc.Driver']
    )
);