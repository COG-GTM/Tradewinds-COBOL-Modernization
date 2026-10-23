package com.ibm.cics.cip.bank.core.screens;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** R-S1: BNKMENU options 1-7 and A, invalid selection, PF3/PF12 exit, CLEAR. */
class MenuRouterTest {

    final MenuRouter router = new MenuRouter();

    @ParameterizedTest(name = "option {0} -> {1}")
    @CsvSource({"1,ODCS", "2,ODAC", "3,OCCS", "4,OCAC", "5,OUAC", "6,OCRA", "7,OTFN", "A,OCCA"})
    void validOptionsStartTheirTransaction(String option, String transid) {
        ScreenTransition t = router.route(option, AidKey.ENTER);
        assertThat(t.kind()).isEqualTo(ScreenTransition.Kind.START);
        assertThat(t.transid()).isEqualTo(transid);
    }

    @ParameterizedTest(name = "option ''{0}'' is invalid")
    @CsvSource({"0", "8", "9", "a", "B", "X", "' '", "11"})
    void invalidOptionsRedisplayWithMessage(String option) {
        ScreenTransition t = router.route(option, AidKey.ENTER);
        assertThat(t.kind()).isEqualTo(ScreenTransition.Kind.REDISPLAY);
        assertThat(t.message()).isEqualTo("You must enter a valid value (1-7 or A).");
    }

    @Test
    void missingOptionIsInvalid() {
        assertThat(router.route(null, AidKey.ENTER).message()).isEqualTo(MenuRouter.INVALID_OPTION);
    }

    @ParameterizedTest
    @CsvSource({"PF3", "PF12"})
    void pf3AndPf12EndTheSession(AidKey key) {
        ScreenTransition t = router.route("1", key);
        assertThat(t.kind()).isEqualTo(ScreenTransition.Kind.END_SESSION);
        assertThat(t.message()).isEqualTo("Session Ended");
    }

    @Test
    void clearErasesTheScreen() {
        assertThat(router.route("1", AidKey.CLEAR).kind()).isEqualTo(ScreenTransition.Kind.CLEAR);
    }

    @ParameterizedTest
    @CsvSource({"PA1", "PA2", "PA3"})
    void paKeysAreIgnored(AidKey key) {
        assertThat(router.route("1", key).kind()).isEqualTo(ScreenTransition.Kind.IGNORE);
    }

    @ParameterizedTest
    @CsvSource({"PF1", "PF5", "PF24"})
    void otherKeysAreInvalid(AidKey key) {
        ScreenTransition t = router.route("1", key);
        assertThat(t.kind()).isEqualTo(ScreenTransition.Kind.REDISPLAY);
        assertThat(t.message()).isEqualTo("Invalid key pressed.");
    }

    @Test
    void firstDisplaySendsErasedMenu() {
        List<List<String>> ev = router.handle(ScreenInput.firstDisplay()).events();
        assertThat(ev.get(0)).containsExactly("SEND_MAP", "BNK1ME", "BNK1MAI", "ERASE FREEKB ");
        assertThat(ev.get(ev.size() - 1)).containsExactly("RETURN_TRANSID", "OMEN", "", "1");
    }

    @Test
    void invalidSelectionKeepsMenu() {
        ScreenOutput out = router.handle(ScreenInput.of(AidKey.ENTER, Map.of("ACTION", "9")));
        assertThat(out.fields()).containsEntry("MESSAGE", MenuRouter.INVALID_OPTION);
        assertThat(out.returnTransid()).isEqualTo("OMEN");
    }

    @Test
    void validSelectionReturnsImmediately() {
        ScreenOutput out = router.handle(ScreenInput.of(AidKey.ENTER, Map.of("ACTION", "A")));
        assertThat(out.returnTransid()).isEqualTo("OCCA");
        assertThat(out.immediate()).isTrue();
    }
}
