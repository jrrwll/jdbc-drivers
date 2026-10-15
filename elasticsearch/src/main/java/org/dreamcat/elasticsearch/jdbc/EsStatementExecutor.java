package org.dreamcat.elasticsearch.jdbc;

import org.dreamcat.elasticsearch.client.EsSearchClient;
import org.dreamcat.elasticsearch.client.EsUpdateClient;
import org.dreamcat.elasticsearch.client.SearchResult;
import org.dreamcat.elasticsearch.sql.SqlCommand;
import org.dreamcat.elasticsearch.sql.SqlCommands;
import org.dreamcat.elasticsearch.sql.SqlQuery;
import org.dreamcat.elasticsearch.sql.SqlUpdate;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;

/** Shared parse-and-execute state used by JDBC Statement and PreparedStatement. */
final class EsStatementExecutor {

    private final Connection connection;
    private final EsSearchClient searchClient;
    private final EsUpdateClient updateClient;
    private ResultSet resultSet;
    private long updateCount = -1;
    private long maxRows;

    EsStatementExecutor(Connection connection, EsSearchClient searchClient, EsUpdateClient updateClient) {
        this.connection = connection;
        this.searchClient = searchClient;
        this.updateClient = updateClient;
    }

    ResultSet executeQuery(String sql) throws SQLException {
        SqlCommand command = SqlCommands.parse(sql, connection.getCatalog());
        if (!(command instanceof SqlQuery))
            throw new SQLException("executeQuery cannot be used for an UPDATE statement");
        resultSet = query((SqlQuery) command);
        updateCount = -1;
        return resultSet;
    }

    boolean execute(String sql) throws SQLException {
        SqlCommand command = SqlCommands.parse(sql, connection.getCatalog());
        if (command instanceof SqlQuery) {
            resultSet = query((SqlQuery) command);
            updateCount = -1;
            return true;
        }
        update((SqlUpdate) command);
        return false;
    }

    long executeUpdate(String sql) throws SQLException {
        SqlCommand command = SqlCommands.parse(sql, connection.getCatalog());
        if (!(command instanceof SqlUpdate))
            throw new SQLException("executeUpdate requires an UPDATE statement");
        return update((SqlUpdate) command);
    }

    ResultSet getResultSet() {
        return resultSet;
    }

    long getUpdateCount() {
        return updateCount;
    }

    void setMaxRows(long value) {
        maxRows = value;
    }

    long getMaxRows() {
        return maxRows;
    }

    boolean getMoreResults(int mode) throws SQLException {
        if (mode == Statement.KEEP_CURRENT_RESULT) {
            throw new SQLFeatureNotSupportedException("Keeping multiple results is not supported");
        }
        if (mode != Statement.CLOSE_CURRENT_RESULT && mode != Statement.CLOSE_ALL_RESULTS) {
            throw new SQLException("Invalid result closing mode");
        }
        closeResultSet();
        updateCount = -1;
        return false;
    }

    void close() throws SQLException {
        closeResultSet();
        updateCount = -1;
    }

    private ResultSet query(SqlQuery query) throws SQLException {
        closeResultSet();
        SearchResult result;
        if (query.isConnectionProbe()) {
            result = new SearchResult(query.columns, Collections.singletonList(query.probeRow));
        } else {
            if (maxRows > 0) query.body.put("size", (int) Math.min(maxRows, (Integer) query.body.get("size")));
            result = searchClient.search(query);
        }
        if (maxRows > 0 && result.rows.size() > maxRows) {
            result = new SearchResult(result.columns, new ArrayList<>(result.rows.subList(0, (int) maxRows)));
        }
        return (ResultSet) Proxy.newProxyInstance(EsStatementExecutor.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                new EsResultSetHandler(result));
    }

    private long update(SqlUpdate update) throws SQLException {
        closeResultSet();
        updateCount = updateClient.update(update);
        return updateCount;
    }

    private void closeResultSet() throws SQLException {
        if (resultSet != null) resultSet.close();
        resultSet = null;
    }
}
