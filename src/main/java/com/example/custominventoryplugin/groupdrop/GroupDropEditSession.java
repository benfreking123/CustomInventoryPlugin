package com.example.custominventoryplugin.groupdrop;

/**
 * Per-player working state for the in-game editor. Holds a mutable draft of the
 * group that survives round-trips into the bundle sub-editor. Stored in a
 * {@code Map<UUID, GroupDropEditSession>} owned by the listener.
 */
public class GroupDropEditSession {

    /** Working copy being edited; only persisted on a clean editor close. */
    public final GroupDrop draft;

    /** When &gt;= 0, the bundle sub-editor is open for this option slot. */
    public int bundleSlot = -1;

    /** True while we deliberately swap between main/bundle windows (skip save-on-close). */
    public boolean suspend = false;

    /** True when the admin pressed CANCEL — discard instead of save. */
    public boolean cancelled = false;

    public GroupDropEditSession(GroupDrop draft) {
        this.draft = draft;
    }
}
