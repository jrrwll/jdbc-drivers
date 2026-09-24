# Elasticsearch JDBC Driver

> 构建脚本使用 Gradle 9，需要 JDK 17+ 运行 Gradle；项目源码按 Java 8 编译。

一个只读的 Elasticsearch JDBC 驱动，通过 HTTP Search API 查询单个 index，不依赖 Elasticsearch Java 客户端。当前面向 ES 7.x。

支持的SQL示例：

```sql
select c1, c2, c3
from index_name
where (
    c1 = 1
        and
    c5 not like 'prefix%'
    )
   or (
    c2 in ('x', 'y')
        and
    c3 between '2026-01-01' and '2026-02-01'
        and
    c4 >= 100
    )
order by ctime desc, _id asc
limit 50 offset 20
```

## 连接与查询

```java
import java.sql.*;
import java.util.Properties;

Properties properties = new Properties();
properties.

setProperty("user","elastic");
properties.

setProperty("password","secret");

try(
Connection connection = DriverManager.getConnection("jdbc:es://localhost:9200", properties);
Statement statement = connection.createStatement();
ResultSet rows = statement.executeQuery(
        "select _id, user.name, score from records " +
                "where score >= 100 order by score desc limit 50 offset 20")){
        while(rows.

next()){
        System.out.

println(rows.getString("_id") +" "+rows.

getString("user.name"));
        }
        }
```

URL 支持 `jdbc:es://localhost:9200` 和 `jdbc:elasticsearch://localhost:9200`，两者行为相同。 也可以使用
`jdbc:es://localhost:9200?user=elastic&password=secret`。同时提供 URL 参数和 `Properties` 时，`Properties` 优先。未提供凭据时不发送
Basic Auth。当前 URL 使用 HTTP。

可重复添加 `index` URL 参数限制可见和可查询的 index，例如 `jdbc:es://localhost:9200?index=some%25index&index=some_index2`
。多个模式取并集；仅 `%` 匹配任意长度字符串，`_` 是普通字符。URL 中标准写法是 `%25`，驱动也接受未编码的裸 `%`（例如
`index=some%index`）。未配置 `index` 时不限制；不匹配的 index 不会出现在表、字段、主键及索引元数据中，直接查询会被拒绝。此限制由驱动实现，不是
Elasticsearch 服务端权限控制。设置 `index` 后，批量字段枚举只读取命中的 index mapping，不再请求全量 `/_mapping`。大集群在
IDEA 中刷新时建议设置足够窄的 `index` 模式；未设置或使用 `index=%25` 等价于允许全量 index，仍可能使 IDEA 的目录同步负载过高。

支持单 index 的 `SELECT`、列名或 `*`、`WHERE` 中的括号、`AND/OR`、比较、`LIKE/NOT LIKE`、`IN/NOT IN`、`BETWEEN`、`IS NULL`、多列
`ORDER BY`、`LIMIT/OFFSET`。未写 `LIMIT` 时请求 `size: 500`。`%`/`_` 分别转换为 ES wildcard 的 `*`/`?`。普通列从 `_source`
读取，点号路径支持嵌套字段，缺失字段返回 `null`；显式查询的 `_id` 从 hit 元数据读取。`SELECT *` 只展开 `_source`。

不支持子查询、JOIN、聚合、`GROUP BY`、`HAVING`、`DISTINCT`、UNION、写操作、`PreparedStatement` 和事务。不支持的 JDBC 方法抛
`SQLFeatureNotSupportedException`。结果集只读、仅向前遍历，一次 Search 请求读取一页；ES 的 `index.max_result_window` 限制仍适用。

IDEA 生成的 catalog 限定名与表别名也可用，例如 `SELECT t.* FROM "es-dev-test".some_index t`。catalog 必须是当前 ES
cluster；表别名前缀会从查询字段中去掉。为兼容 IDE 的连接保活探测，无 `FROM` 的纯常量 `SELECT`（可带列别名或 `LIMIT 1`
）在驱动本地返回一行，不向 Elasticsearch 发请求。其他无 `FROM` 查询仍不支持。

## JDBC 元数据

`Connection.getMetaData()` 支持以下方法：

- `getCatalogs()`：ES cluster 名称作为 catalog。
- `getSchemas()`：ES 无 schema，返回空结果集。
- `getTables(...)`：由 `/_cat/indices` 返回 index。
- `getColumns(...)`：由 `/_mapping` 返回字段，含嵌套字段、多字段和 `_id` 伪列。
- `getPrimaryKeys(...)`：返回 `_id`。
- `getIndexInfo(...)`：返回 mapping 中未设置 `index: false` 的字段。

## 在 IntelliJ IDEA Database 中使用

执行 `./gradlew elasticsearch-jdbc-driver:shadowJar`，在 IDEA Database 创建自定义 JDBC Driver：

- Driver Files 添加 `elasticsearch/build/libs/elasticsearch-jdbc-driver-0.1-SNAPSHOT-all.jar`
- Driver Class 填 `org.dreamcat.elasticsearch.jdbc.Driver`
- URL 模板填 `jdbc:es://{host::localhost}?[:{port::9200}][\?<&,user={user},password={password},{:identifier}={:param}>]`
