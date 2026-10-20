package org.dreamcat.elasticsearch;

import org.dreamcat.elasticsearch.client.EsHttpClient;
import org.dreamcat.elasticsearch.config.EsConfig;
import org.dreamcat.elasticsearch.jdbc.EsConnectionHandler;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/** A minimal JDBC driver for Elasticsearch HTTP search and primary-key update requests. */
public class Driver implements java.sql.Driver {

    public static final String URL_PREFIX = EsConfig.URL_PREFIX;
    public static final String ELASTICSEARCH_URL_PREFIX = EsConfig.ELASTICSEARCH_URL_PREFIX;
    public static final int MAJOR_VERSION = 0;
    public static final int MINOR_VERSION = 1;

    static {
        try {
            DriverManager.registerDriver(new Driver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        EsConfig config = EsConfig.parse(url, info);
        new EsHttpClient(config).get("/");
        return (Connection) Proxy.newProxyInstance(
                Driver.class.getClassLoader(), new Class<?>[]{Connection.class},
                new EsConnectionHandler(config));
    }

    @Override
    public boolean acceptsURL(String url) {
        return EsConfig.urlPrefix(url) != null;
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[]{new DriverPropertyInfo("user", ""), new DriverPropertyInfo("password", "")};
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return Logger.getGlobal();
    }
}
