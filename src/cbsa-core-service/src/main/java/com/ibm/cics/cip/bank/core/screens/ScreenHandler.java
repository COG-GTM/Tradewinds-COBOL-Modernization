package com.ibm.cics.cip.bank.core.screens;

/** One BNK1* program: a pseudo-conversational handler for one BMS mapset. */
public interface ScreenHandler {

    /** The BMS map this handler sends and receives. */
    BmsMap map();

    /** One pseudo-conversational turn (the PREMIERE section of the COBOL program). */
    ScreenOutput handle(ScreenInput input);
}
