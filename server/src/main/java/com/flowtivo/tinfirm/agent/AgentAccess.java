package com.flowtivo.tinfirm.agent;

/**
 * What a caller may do with an agent (the package 3 contract, §5.1, {@code Agents.access}):
 * <ul>
 * <li>its owner sees, uses and edits it;</li>
 * <li>a member of a group it is shared with, and a member it is shared with on their own, see and use it;</li>
 * <li>OWNER and ADMIN see and edit (share, pause, delete) every agent, and use one only as its owner or through a
 * share (open point 11);</li>
 * <li>anyone else nothing: the agent is {@code 404} to them (I17).</li>
 * </ul>
 */
public record AgentAccess(boolean sees, boolean uses, boolean edits) {

    public static final AgentAccess NONE = new AgentAccess(false, false, false);
}
