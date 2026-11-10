package com.zalexdev.stryker.hid.configfs;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class GadgetState {

    public final boolean strykerGadgetExists;
    public final boolean strykerGadgetBound;
    public final String boundUdc;
    public final Set<GadgetFunction> linkedFunctions;
    public final String massStorageFile;
    /** Whether /dev/hidg0 exists — the same signal the HID Attacks screen uses. */
    public final boolean hidNodePresent;

    public GadgetState(boolean strykerGadgetExists,
                       boolean strykerGadgetBound,
                       String boundUdc,
                       Set<GadgetFunction> linkedFunctions,
                       String massStorageFile,
                       boolean hidNodePresent) {
        this.strykerGadgetExists = strykerGadgetExists;
        this.strykerGadgetBound = strykerGadgetBound;
        this.boundUdc = boundUdc;
        this.linkedFunctions = linkedFunctions == null
                ? Collections.unmodifiableSet(EnumSet.noneOf(GadgetFunction.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(linkedFunctions));
        this.massStorageFile = massStorageFile;
        this.hidNodePresent = hidNodePresent;
    }

    /** True when this state exposes a HID function (keyboard or mouse). */
    public boolean exposesHid() {
        return linkedFunctions.contains(GadgetFunction.HID_KEYBOARD)
                || linkedFunctions.contains(GadgetFunction.HID_MOUSE);
    }
}
