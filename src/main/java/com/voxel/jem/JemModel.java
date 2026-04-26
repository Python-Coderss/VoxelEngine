package com.voxel.jem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class JemModel {
    private final String name;
    private final List<JemPartDefinition> parts;

    public JemModel(String name, List<JemPartDefinition> parts) {
        this.name = name;
        this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
    }

    public String getName() {
        return name;
    }

    public List<JemPartDefinition> getParts() {
        return parts;
    }
}
