package org.dreamcat.elasticsearch;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

final class EsConnectionHandler implements InvocationHandler {

    private final EsConfig config;
    private final EsSearchClient client;
    private final EsHttpClient http;
    private EsDatabaseMetaDataHandler metadata;
    private boolean closed;

    EsConnectionHandler(EsConfig config) {
        this.config = config;
        this.client = new EsSearchClient(config);
        this.http = new EsHttpClient(config);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class)
            return JdbcSupport.objectMethod(proxy, method, args, "ES7Connection[" + config.endpoint + "]");
        switch (method.getName()) {
            case "close":
                closed = true;
                return null;
            case "isClosed":
                return closed;
            case "isValid":
                if ((Integer) args[0] < 0) throw new SQLException("Timeout must not be negative");
                if (closed) return false;
                try {
                    http.get("/");
                    return true;
                } catch (SQLException e) {
                    return false;
                }
        }
        checkOpen();
        switch (method.getName()) {
            case "createStatement":
                if (args != null && args.length >= 2
                        && (((Integer) args[0]) != java.sql.ResultSet.TYPE_FORWARD_ONLY
                        || ((Integer) args[1]) != java.sql.ResultSet.CONCUR_READ_ONLY)) {
                    throw new SQLFeatureNotSupportedException("Only forward-only read-only statements are supported");
                }
                return Proxy.newProxyInstance(Driver.class.getClassLoader(), new Class<?>[]{Statement.class},
                        new EsStatementHandler((Connection) proxy, client));
            case "getMetaData":
                return Proxy.newProxyInstance(Driver.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                        metadata((Connection) proxy));
            case "isReadOnly":
                return true;
            case "setReadOnly":
                if (Boolean.FALSE.equals(args[0])) throw new SQLFeatureNotSupportedException("Connection is read-only");
                return null;
            case "getAutoCommit":
                return true;
            case "setAutoCommit":
                if (Boolean.FALSE.equals(args[0]))
                    throw new SQLFeatureNotSupportedException("Transactions are not supported");
                return null;
            case "commit":
            case "rollback":
                throw new SQLFeatureNotSupportedException("Transactions are not supported");
            case "getCatalog":
                return metadata((Connection) proxy).clusterName();
            case "setCatalog":
                if (args[0] != null && !args[0].equals(metadata((Connection) proxy).clusterName())) {
                    throw new SQLException("Unknown catalog: " + args[0]);
                }
                return null;
            case "getSchema":
                return null;
            case "getTransactionIsolation":
                return Connection.TRANSACTION_NONE;
            case "getWarnings":
                return null;
            case "clearWarnings":
                return null;
            case "getClientInfo":
                return args == null || args.length == 0 ? new java.util.Properties() : null;
            case "unwrap":
                if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                throw new SQLException("Not a wrapper for " + args[0]);
            case "isWrapperFor":
                return ((Class<?>) args[0]).isInstance(proxy);
            default:
                throw JdbcSupport.unsupported(method);
        }
    }

    private void checkOpen() throws SQLException {
        if (closed) throw new SQLException("Connection is closed");
    }

    private EsDatabaseMetaDataHandler metadata(Connection connection) {
        if (metadata == null) metadata = new EsDatabaseMetaDataHandler(connection, config);
        return metadata;
    }
}
