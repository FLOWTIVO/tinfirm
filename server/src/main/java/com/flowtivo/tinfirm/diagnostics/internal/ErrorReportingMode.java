package com.flowtivo.tinfirm.diagnostics.internal;

/** The modes of error reporting (ADR-0070, §4). */
enum ErrorReportingMode {

    /** Nothing is captured. */
    OFF,

    /** Captured and shown to instance admins, never sent. */
    LOCAL,

    /** Captured, shown, and delivered to the destination. */
    AUTOMATIC
}
