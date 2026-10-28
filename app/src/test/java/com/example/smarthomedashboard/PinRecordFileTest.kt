package com.example.smarthomedashboard
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class PinRecordFileTest {
 private val app=RuntimeEnvironment.getApplication()
 private val prefs=app.getSharedPreferences("pin-file-test",0)
 private val base=File(app.noBackupFilesDir,"pin-file-test/record.json")
 private val store=PinRecordFile(base,prefs) { it.isFile }
 @Test fun absenceBeforeMigrationDiffersFromLossAfterMigration() {
  assertNull(store.read());prefs.edit().putBoolean("pin_verifier_initialized",true).commit()
  try{store.read();fail("missing initialized record must fail closed")}catch(_:IllegalStateException){}
 }
 @Test fun disabledRecordRoundtripAndNoOtherPreferencesAreWritten() {
  prefs.edit().putString("unrelated","kept").commit()
  assertTrue(store.write(PinAccess.State(null,null)))
  assertNull(store.read()!!.pin);assertEquals("kept",prefs.getString("unrelated",null))
 }
 @Test fun malformedTruncatedOrUnknownSchemaDoesNotBecomeDisabled() {
  base.parentFile!!.mkdirs()
  for(text in listOf("", "{}", "{\"version\":2}", "x".repeat(4097))) {
   base.writeText(text)
   try{store.read();fail("corrupt record must fail closed")}catch(_:Exception){}
  }
 }
 @Test fun kdfRecordAndCounterRoundtrip() {
  val record=PinVerifier().create("123456".toCharArray(),PinVerifier.Purpose.PIN)
  assertTrue(store.write(PinAccess.State(record.encoded,null,5,1000,30000,"boot")))
  val read=store.read()!!;assertEquals(5,read.failures);assertEquals(30000L,read.cooldownDuration)
  assertEquals(PinVerifier.Verdict.MATCH,PinVerifier().verify("123456".toCharArray(),read.pin!!,PinVerifier.Purpose.PIN))
 }
}
