package com.paybridge.simulator.config;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;

/**
 * Implements the "no response" failures of the TIMEOUT_BEFORE_PROCESSING and SUCCESS_RESPONSE_LOST scenarios. A handler
 * marks the request with {@link #ATTRIBUTE} and writes nothing; after the servlet returns, this valve tells Tomcat to
 * close the socket immediately, before any status line or body byte is sent. The client sees a connection that ended
 * without an answer, exactly like a crashed or partitioned provider.
 */
public class ConnectionAbortValve extends ValveBase {
    public static final String ATTRIBUTE = "paybridge.simulator.abort";

    public ConnectionAbortValve() {
        super(true);
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        getNext().invoke(request, response);
        if (request.getAttribute(ATTRIBUTE) != null) {
            response.getCoyoteResponse().action(ActionCode.CLOSE_NOW, null);
        }
    }
}
