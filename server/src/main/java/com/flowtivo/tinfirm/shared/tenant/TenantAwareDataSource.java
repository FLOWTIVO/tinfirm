package com.flowtivo.tinfirm.shared.tenant;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.ConnectionProxy;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * The hook that makes the tenant context impossible to forget.
 * <p>
 * Every connection handed out by the application {@link DataSource} is wrapped. When a transaction begins, the
 * transaction manager (JPA/Hibernate or JDBC) switches the connection to manual commit with
 * {@code setAutoCommit(false)}. At exactly that point the wrapper writes {@link TenantContextHolder#current()} into
 * the new transaction with {@code set_config(..., true)}. No service, repository or query has to remember it.
 * <p>
 * Work outside a transaction (auto-commit) gets no context at all, so row-level security denies every tenant row:
 * forgetting {@code @Transactional} fails closed instead of leaking data.
 */
public final class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(obtainTargetDataSource().getConnection(username, password));
    }

    private static Connection wrap(Connection target) {
        return (Connection) Proxy.newProxyInstance(ConnectionProxy.class.getClassLoader(),
                new Class<?>[] { ConnectionProxy.class }, new TenantConnectionHandler(target));
    }

    private record TenantConnectionHandler(Connection target) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "getTargetConnection":
                    return target;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "TenantAwareConnection[" + target + "]";
                case "unwrap":
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return proxy;
                    }
                    break;
                case "isWrapperFor":
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return true;
                    }
                    break;
                case "setAutoCommit":
                    if (!((Boolean) args[0]) && target.getAutoCommit()) {
                        target.setAutoCommit(false);
                        // A transaction begins here: establish the tenant context for it.
                        TenantSettings.apply(target, TenantContextHolder.current());
                        return null;
                    }
                    break;
                default:
                    break;
            }
            try {
                return method.invoke(target, args);
            }
            catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }
    }
}
