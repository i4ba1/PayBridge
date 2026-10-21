package com.paybridge.application.port;

/** The two cluster wide switches in system_flag, cached briefly by the implementation. */
public interface SystemFlags {

    boolean maintenanceOn();

    boolean capacityClosed();
}
