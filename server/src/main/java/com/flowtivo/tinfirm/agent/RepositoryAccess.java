package com.flowtivo.tinfirm.agent;

/** An agent's access to a repository (the package 3 contract, §3.4; ADR-0017): {@code READ} is cloned, never pushed. */
public enum RepositoryAccess {
    READ, WRITE;

    /** The narrower of the two. */
    public RepositoryAccess min(RepositoryAccess other) {
        return this == READ || other == READ ? READ : WRITE;
    }
}
