package com.paybridge.it.provider;

/** SIMULATED_CARD settles immediately. */
class SimulatedCardCapabilityIT extends AdapterCapabilitySuite {
    @Override
    String methodToken() {
        return "tok_sim_card_default";
    }
}
