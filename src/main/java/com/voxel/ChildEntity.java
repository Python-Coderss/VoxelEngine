package com.voxel;

public class ChildEntity {
    public float wx, wy, wz; // World position relative to parent?
    public int axis; // Rotation axis
    public float cos, sin; // Rotation values
    public int model; // Model index

    public ChildEntity(float wx, float wy, float wz, int axis, float cos, float sin, int model) {
        this.wx = wx;
        this.wy = wy;
        this.wz = wz;
        this.axis = axis;
        this.cos = cos;
        this.sin = sin;
        this.model = model;
    }

    public ChildEntity() {
        // Default constructor with zeros
    }

    public void setRotation(float cos, float sin) {
        this.cos = cos;
        this.sin = sin;
    }

    public void move(float dx, float dy, float dz) {
        wx += dx;
        wy += dy;
        wz += dz;
    }

    public void rotate(float angle) {
        cos = (float) Math.cos(angle);
        sin = (float) Math.sin(angle);
    }
}