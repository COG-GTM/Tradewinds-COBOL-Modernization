package com.ibm.cics.cip.bank.core.delcus;

import com.ibm.cics.cip.bank.core.common.domain.Account;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Production {@link AccountDeletion}: delegates to the DELACC port {@code delacc.DeleteAccountService}
 * (T-08, public API {@code deleteAccount(long) : Account}).
 *
 * <p>The service is resolved by class name at call time so this package compiles and starts whether or not
 * the T-08 package is present yet (wave-2 ticket built in parallel with its dependency). Once both are on the
 * integration branch this bridge can be replaced by a constructor-injected {@code DeleteAccountService}.
 */
@Component
public class DelaccAccountDeletion implements AccountDeletion {

    static final String DELETE_ACCOUNT_SERVICE = "com.ibm.cics.cip.bank.core.delacc.DeleteAccountService";

    private final ApplicationContext context;
    private final String serviceClassName;
    private volatile Target target;

    @Autowired
    public DelaccAccountDeletion(ApplicationContext context) {
        this(context, DELETE_ACCOUNT_SERVICE);
    }

    DelaccAccountDeletion(ApplicationContext context, String serviceClassName) {
        this.context = context;
        this.serviceClassName = serviceClassName;
    }

    @Override
    public Account deleteAccount(long accountNumber) {
        Target t = resolve();
        try {
            return (Account) t.method().invoke(t.bean(), accountNumber);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("DELACC failed for account " + accountNumber, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot invoke " + serviceClassName + "#deleteAccount", e);
        }
    }

    private Target resolve() {
        Target t = target;
        if (t == null) {
            try {
                Class<?> type = Class.forName(serviceClassName, true, context.getClassLoader());
                Object bean = context.getBean(type);
                Method method = type.getMethod("deleteAccount", long.class);
                t = new Target(bean, method);
                target = t;
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                throw new IllegalStateException(
                        "DELACC port " + serviceClassName + "#deleteAccount(long) is not available", e);
            }
        }
        return t;
    }

    private record Target(Object bean, Method method) {
    }
}
