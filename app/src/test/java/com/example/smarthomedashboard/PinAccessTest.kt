package com.example.smarthomedashboard
import org.junit.Assert.*
import org.junit.Test

class PinAccessTest {
 private class Fixture {
  var data:PinAccess.State?=null;var legacyPin:String?="1234";var writes=true;var cleanup=true;var now=0L;var boot="one"
  val access=PinAccess(object:PinAccess.Storage{override fun read()=data;override fun write(state:PinAccess.State):Boolean {if(!writes)return false;data=state;return true}},
   object:PinAccess.Legacy{override fun read()=legacyPin;override fun remove():Boolean{if(!cleanup)return false;legacyPin=null;return true}},
   clock=object:PinAccess.Clock{override fun elapsed()=now;override fun boot()=boot},worker={})
 }
 @Test fun oldFourDigitPinMigratesWithoutChangingIt() {
  val f=Fixture();assertEquals(PinAccess.Status.SUCCESS,f.access.verify("1234".toCharArray()).status)
  assertNull(f.legacyPin);assertNotNull(f.data!!.pin);assertNull(f.data!!.recovery)
 }
 @Test fun disabledRemainsDisabled() {
  val f=Fixture();f.legacyPin="";assertEquals(PinAccess.Status.DISABLED,f.access.status().status)
  assertNull(f.data!!.pin);assertNull(f.legacyPin)
 }
 @Test fun failedWriteNeverRemovesTheLegacyCopy() {
  val f=Fixture();f.writes=false;assertEquals(PinAccess.Status.ERROR,f.access.status().status)
  assertEquals("1234",f.legacyPin);assertNull(f.data)
 }
 @Test fun cleanupFailureDoesNotGrantAccessAndCanResume() {
  val f=Fixture();f.cleanup=false;assertEquals(PinAccess.Status.ERROR,f.access.verify("1234".toCharArray()).status)
  assertNotNull(f.data!!.pin);f.cleanup=true
  assertEquals(PinAccess.Status.SUCCESS,f.access.verify("1234".toCharArray()).status)
 }
 @Test fun fiveFailuresThrottleAndRebootDoesNotClearPenalty() {
  val f=Fixture();repeat(4){assertEquals(PinAccess.Status.WRONG,f.access.verify("0000".toCharArray()).status)}
  val blocked=f.access.verify("0000".toCharArray());assertEquals(PinAccess.Status.WAIT,blocked.status);assertEquals(30_000L,blocked.retryAfterMillis)
  assertEquals(PinAccess.Status.WAIT,f.access.verify("1234".toCharArray()).status)
  f.boot="two";f.now=1;assertEquals(30_000L,f.access.status().retryAfterMillis)
  f.now+=30_001;assertEquals(PinAccess.Status.SUCCESS,f.access.verify("1234".toCharArray()).status)
 }
 @Test fun recoveryIsSingleUseAndCommitsReplacementWithoutLosingDataOnFailure() {
  val f=Fixture();val changed=f.access.change("1234".toCharArray(),"246802".toCharArray());assertEquals(PinAccess.Status.SUCCESS,changed.status)
  val code=checkNotNull(changed.recoveryCode);val state=f.data
  f.writes=false;assertEquals(PinAccess.Status.ERROR,f.access.recover(code,"135791".toCharArray()).status);assertSame(state,f.data)
  f.writes=true;val recovered=f.access.recover(code,"135791".toCharArray());assertEquals(PinAccess.Status.SUCCESS,recovered.status)
  assertNotNull(recovered.recoveryCode);assertEquals(PinAccess.Status.WRONG,f.access.recover(code,"111111".toCharArray()).status)
  assertEquals(PinAccess.Status.SUCCESS,f.access.verify("135791".toCharArray()).status)
  code.fill(' ');recovered.recoveryCode!!.fill(' ')
 }
 @Test fun disablingRequiresFreshPinAndInvalidNewPinDoesNotWrite() {
  val f=Fixture();assertEquals(PinAccess.Status.INVALID_NEW_PIN,f.access.change("1234".toCharArray(),"12".toCharArray()).status);assertNull(f.data)
  assertEquals(PinAccess.Status.WRONG,f.access.disable("9999".toCharArray()).status)
  assertEquals(PinAccess.Status.DISABLED,f.access.disable("1234".toCharArray()).status);assertNull(f.data!!.pin)
 }
}
