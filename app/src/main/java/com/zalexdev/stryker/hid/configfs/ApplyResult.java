package com.zalexdev.stryker.hid.configfs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Outcome of {@link UsbGadgetController#applyProfile}. Carries not just success
 * but which of the profile's requested functions the kernel could actually
 * create, so the UI can warn when (for example) the ACM serial driver is not
 * compiled into the kernel and the gadget falls back to HID-only.
 */
public final class ApplyResult {

    public final boolean success;
    @Nullable
    public final String boundUdc;
    /** Functions the profile asked for. */
    public final Set<GadgetFunction> requested;
    /** Functions actually created and linked into the gadget. */
    public final Set<GadgetFunction> active;
    /** Requested functions the kernel could not provide (requested minus active). */
    public final Set<GadgetFunction> dropped;

    private ApplyResult(boolean success,
                        @Nullable String boundUdc,
                        @NonNull Set<GadgetFunction> requested,
                        @NonNull Set<GadgetFunction> active) {
        this.success = success;
        this.boundUdc = boundUdc;
        this.requested = immutable(requested);
        this.active = immutable(active);
        EnumSet<GadgetFunction> drop = isEmpty(requested)
                ? EnumSet.noneOf(GadgetFunction.class)
                : EnumSet.copyOf(requested);
        drop.removeAll(active);
        this.dropped = Collections.unmodifiableSet(drop);
    }

    public boolean hasDropped() {
        return !dropped.isEmpty();
    }

    static ApplyResult failure(@NonNull Set<GadgetFunction> requested,
                               @NonNull Set<GadgetFunction> active) {
        return new ApplyResult(false, null, requested, active);
    }

    static ApplyResult bound(@NonNull String udc,
                             @NonNull Set<GadgetFunction> requested,
                             @NonNull Set<GadgetFunction> active) {
        return new ApplyResult(true, udc, requested, active);
    }

    private static boolean isEmpty(Set<GadgetFunction> s) {
        return s == null || s.isEmpty();
    }

    private static Set<GadgetFunction> immutable(Set<GadgetFunction> s) {
        return isEmpty(s)
                ? Collections.unmodifiableSet(EnumSet.noneOf(GadgetFunction.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(s));
    }
}
