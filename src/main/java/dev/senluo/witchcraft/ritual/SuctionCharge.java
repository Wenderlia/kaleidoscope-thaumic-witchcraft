package dev.senluo.witchcraft.ritual;

import java.math.BigInteger;

/** Paid range/rate, independent of a vessel, world scan or transfer implementation. */
public record SuctionCharge(int flowGroups, int rangeGroups) {
    public static final int GROUP_SIZE = 16, MAX_GROUPS = Integer.MAX_VALUE / GROUP_SIZE;
    public SuctionCharge {
        if (flowGroups < 0 || rangeGroups < 0 || flowGroups > MAX_GROUPS || rangeGroups > MAX_GROUPS)
            throw new IllegalArgumentException("Invalid suction activation quote");
    }
    public static SuctionCharge quote(int vacuos, int ordo) {
        if (vacuos < 0 || ordo < 0) throw new IllegalArgumentException("Negative aspect balance");
        return new SuctionCharge(vacuos / GROUP_SIZE, ordo / GROUP_SIZE);
    }
    public int flowCost() { return flowGroups * GROUP_SIZE; }
    public int rangeCost() { return rangeGroups * GROUP_SIZE; }
    public boolean canPay(int vacuos, int ordo) { return vacuos >= flowCost() && ordo >= rangeCost(); }

    /** No radius cap or enormous bounding-box allocation. An overflowing radius includes any finite position. */
    public boolean contains(double distanceSquared) {
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0) return false;
        return distanceSquared <= Math.scalb(16.0, rangeGroups > 510 ? 1024 : rangeGroups * 2);
    }

    /** One channel's flow is available independently at every legal output. Idle output never banks work. */
    public Rate rate(int nativeInfusionInterval) {
        return rate(nativeInfusionInterval, 1);
    }
    public Rate rate(int nativeInfusionInterval, int nativeEssentiaPerPulse) {
        if (nativeInfusionInterval < 1 || nativeEssentiaPerPulse < 1) throw new IllegalArgumentException("Invalid infusion rate");
        long period = 2L * nativeInfusionInterval;
        var pulse = BigInteger.valueOf(nativeEssentiaPerPulse);
        long remainder = BigInteger.TWO.modPow(BigInteger.valueOf(flowGroups), BigInteger.valueOf(period))
                .multiply(pulse).mod(BigInteger.valueOf(period)).longValueExact();
        // Quotes wider than any representable loaded inventory saturate a scalar, never allocate 2^groups
        // or execute that many iterations. Each actual native transfer is later bounded by its real balance.
        int periodBits = 64 - Long.numberOfLeadingZeros(period);
        long whole = flowGroups >= 63 + periodBits ? Long.MAX_VALUE : pulse.shiftLeft(flowGroups)
                .divide(BigInteger.valueOf(period)).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
        return new Rate(period, whole, remainder);
    }
    public record Rate(long period, long whole, long remainder) {
        /** Preserve the fractional unit across datapack interval changes; old saves omit the period. */
        public long rebaseCredit(long credit, long previousPeriod) {
            if (previousPeriod <= 0 || previousPeriod == period) return Math.floorMod(credit, period);
            return BigInteger.valueOf(Math.floorMod(credit, previousPeriod)).multiply(BigInteger.valueOf(period))
                    .divide(BigInteger.valueOf(previousPeriod)).longValueExact();
        }
        public Pulse advance(long credit) {
            long total = Math.floorMod(credit, period) + remainder;
            long carry = total / period;
            long amount = whole > Long.MAX_VALUE - carry ? Long.MAX_VALUE : whole + carry;
            return new Pulse(amount, total % period);
        }
    }
    public record Pulse(long amount, long credit) {}
}
