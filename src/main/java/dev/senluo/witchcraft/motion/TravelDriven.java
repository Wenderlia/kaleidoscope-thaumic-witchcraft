package dev.senluo.witchcraft.motion;

/** A flight entity explicitly plans and commits TravelSteps instead of the native velocity adapter.
 * It owns its native tick, pause/save rules and the meaning of the returned intrinsic length. */
public interface TravelDriven {}
