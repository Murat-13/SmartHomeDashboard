package com.example.smarthomedashboard
import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import java.io.File

/** Real APK update, isolated artificial legacy prefs. Shared emulator data never modified. */
class PinUpgradeInstrumentation:Instrumentation() {
 private var mode=""
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);mode=arguments?.getString("mode").orEmpty();start()}
 override fun onStart(){var step="owner";var passed=false
 try{
  val owner=object:ContextWrapper(targetContext){
   override fun getApplicationContext():Context=this
   override fun getSharedPreferences(name:String,flags:Int)=targetContext.getSharedPreferences("pin-upgrade-v1-"+name,flags)
   override fun getNoBackupFilesDir()=File(targetContext.noBackupFilesDir,"pin-upgrade-v1")
  }
  check(owner.applicationInfo.uid==android.os.Process.myUid())
  val prefs=owner.getSharedPreferences("dashboard_prefs",0)
  val tiles=owner.getSharedPreferences("tiles_prefs",0)
  val base=mapOf<String,Any>("pin_session_minutes" to 10,"unrelated" to "FAKE-KEPT")
  val legacy=base+mapOf("admin_pin" to "1234","last_auth_time" to 123L)
  val secure=base+mapOf("pin_verifier_initialized" to true)
  check(tiles.all==mapOf("tiles" to "FAKE-TILES-PRESERVED"))
  check(!PinSecurityUi.session.isValid(10))
  if(mode=="migrate")check(prefs.all==legacy)else check(prefs.all==secure)
  step=mode
  val access=AndroidPinAccess.create(owner)
  val pin=if(mode=="newRestart")"246802" else "1234"
  check(access.verify(pin.toCharArray()).status==PinAccess.Status.SUCCESS)
  if(mode=="change"){
   val changed=access.change("1234".toCharArray(),"246802".toCharArray())
   check(changed.status==PinAccess.Status.SUCCESS);check(changed.recoveryCode!=null)
   changed.recoveryCode!!.fill(' ')
  }
  check(prefs.all==secure);check(tiles.all==mapOf("tiles" to "FAKE-TILES-PRESERVED"))
  check(File(owner.noBackupFilesDir,"pin-security/record.json").isFile)
  step="verified";passed=true
 }catch(_:Exception){}catch(_:AssertionError){}
 finish(if(passed)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{putBoolean("passed",passed);putString("step",step)})
 }
}
