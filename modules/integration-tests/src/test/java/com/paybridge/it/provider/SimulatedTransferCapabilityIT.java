package com.paybridge.it.provider;

/** SIMULATED_TRANSFER is pending first and settles after the default delay of three seconds. */
class SimulatedTransferCapabilityIT extends AdapterCapabilitySuite {
    @Override
    String methodToken() {
        return "tok_sim_transfer_default";
    }
}
