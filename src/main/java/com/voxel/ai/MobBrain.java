package com.voxel.ai;

/**
 * Pluggable decision-maker for an entity. Brains sit above the legacy FSMs:
 * when installed on an entity they receive perception each tick and may claim
 * the tick's decisions; returning false falls through to legacy behavior.
 */
public interface MobBrain {

    /**
     * Called once per logic tick before {@link #update(float)} with a fresh
     * {@link Senses} snapshot.
     */
    void perceive(Senses senses);

    /**
     * Drives this tick's movement/action decisions.
     *
     * @return true if the brain fully handled decisions (legacy AI is skipped),
     *         false to defer to legacy FSM behavior.
     */
    boolean update(float dt);

    /**
     * Asynchronous world events delivered from the {@link StimulusBus}
     * (threat sightings, damage, gestures). Brains that subscribe themselves
     * to the bus receive them between ticks.
     */
    default void onStimulus(Stimulus stimulus) {
    }
}
