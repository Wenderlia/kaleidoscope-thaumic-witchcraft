package dev.senluo.witchcraft.brew;

/** Actual registered cooldown, including changes made by incoming-damage listeners. */
public interface MeleeWindow {
    int witchcraft$windowStart();
    long witchcraft$windowSerial();
}
