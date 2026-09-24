package org.dreamcat.elasticsearch;

import java.lang.reflect.Method;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

final class JdbcSupport {

    private JdbcSupport() {
    }

    static SQLException unsupported(Method method) {
        return new SQLFeatureNotSupportedException(method.getName() + " is not supported");
    }

    static Object objectMethod(Object proxy, Method method, Object[] args, String text) {
        switch (method.getName()) {
            case "toString":
                return text;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            default:
                return null;
        }
    }

}
