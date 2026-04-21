package com.voxel;

public class Entity {
    public float wx, wy, wz; // World position
    public int axis; // Rotation axis
    public float cos, sin; // Rotation values
    public int model; // Model index
    public int childStart; // Child start index
    public int childCount; // Child count

    public Entity(float wx, float wy, float wz, int axis, float cos, float sin, int model, int childStart, int childCount) {
        this.wx = wx;
        this.wy = wy;
        this.wz = wz;
        this.axis = axis;
        this.cos = cos;
        this.sin = sin;
        this.model = model;
        this.childStart = childStart;
        this.childCount = childCount;
    }

    public Entity() {
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

    public void addChild() {
        childCount++;
    }
}