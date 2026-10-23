package com.ibm.cics.cip.bank.core.common.proctran;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Each HTTP request runs as one CICS task ({@link TaskNumberSupplier#inTask}): one EIBTASKN per request. */
@Component
public class CicsTaskFilter extends OncePerRequestFilter {

    private final TaskNumberSupplier taskNumbers;

    public CicsTaskFilter(TaskNumberSupplier taskNumbers) {
        this.taskNumbers = taskNumbers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            taskNumbers.inTask(() -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (ServletException e) {
                    throw new WrappedServletException(e);
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (WrappedServletException e) {
            throw (ServletException) e.getCause();
        }
    }

    private static final class WrappedServletException extends RuntimeException {
        WrappedServletException(ServletException cause) {
            super(cause);
        }
    }
}
