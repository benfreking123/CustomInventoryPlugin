package com.example.custominventoryplugin.party;

/**
 * Outcome of an invite attempt. Parties' {@code invitePlayer} collapses every
 * refusal into a null return, so we pre-check the cases we can name and report
 * them separately — a bare "could not invite" sends people hunting for bugs
 * that aren't there.
 */
public enum InviteResult {
    SENT,
    SELF,
    ALREADY_IN_PARTY,
    ALREADY_INVITED,
    PARTY_FULL,
    FAILED
}
