package com.flowtivo.tinfirm.company;

/**
 * The companies the deployment declares ({@code TINFIRM_COMPANIES_<KEY>_*}, ADR-0075): the only way a company comes
 * into being on an instance. Applied at every start by the bootstrap, after the first instance admin; the owners are
 * also applied at each sign-in.
 */
public interface DeclaredCompanies {

    /**
     * Creates each declared company that does not exist yet, or adopts a company from before keys, and makes its
     * declared owner its OWNER while it has none. One replica at a time, as actor SYSTEM. Throws, and so stops the
     * start, when a declaration cannot be applied: an id that names no company or one with another key, or several
     * companies without a key that have the declared name.
     */
    void apply();
}
