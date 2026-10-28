package com.example.smarthomedashboard
import org.junit.Assert.*
import org.junit.Test
class PinSessionTest {
 @Test fun expirationIsExactAndUsesCurrentConfiguredDuration(){var time=0L;val s=PinSession{time};s.grant();time=59_999;assertTrue(s.isValid(1));time=60_000;assertFalse(s.isValid(1))}
 @Test fun restartHasNoAuthorization(){val first=PinSession{0};first.grant();assertTrue(first.isValid(5));assertFalse(PinSession{0}.isValid(5))}
 @Test fun monotonicRollbackInvalidatesSession(){var t=10L;val s=PinSession{t};s.grant();t=9;assertFalse(s.isValid(5));t=11;assertFalse(s.isValid(5))}
 @Test fun zeroNegativeAndRevocationRequireAuthentication(){val s=PinSession{0};s.grant();assertFalse(s.isValid(0));s.grant();assertFalse(s.isValid(-1));s.grant();s.revoke();assertFalse(s.isValid(5))}
 @Test fun shorterCurrentTimeoutCannotReuseOldLongSession(){var t=0L;val s=PinSession{t};s.grant();t=120_000;assertFalse(s.isValid(1))}
 @Test fun settingsInteractionRenewsIdleInterval(){var t=0L;val s=PinSession{t};s.grant();t=240_000;assertTrue(s.touch(5));t=480_000;assertTrue(s.isValid(5));t=540_000;assertFalse(s.isValid(5))}
 @Test fun settingsEntryRenewsAlreadyValidSession(){var t=0L;val s=PinSession{t};s.grant();t=299_999;assertTrue(s.touch(5));t=300_001;assertTrue(s.isValid(5))}
 @Test fun inputAfterIdleExpirationCannotReviveAuthentication(){var t=0L;val s=PinSession{t};s.grant();t=300_000;assertFalse(s.touch(5));assertFalse(s.isValid(5))}
 @Test fun panelTimeDoesNotExtendSessionWithoutSettingsInput(){var t=0L;val s=PinSession{t};s.grant();t=200_000;assertTrue(s.isValid(5));t=300_000;assertFalse(s.isValid(5))}
}
