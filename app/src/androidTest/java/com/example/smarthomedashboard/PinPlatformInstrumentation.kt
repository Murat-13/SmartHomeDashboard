package com.example.smarthomedashboard

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import java.io.File
import java.util.UUID

/** Artificial PIN data in a random isolated context. Never opens production Activity or network. */
class PinPlatformInstrumentation:Instrumentation() {
 private var probe=false
 override fun onCreate(arguments:Bundle?){super.onCreate(arguments);probe=arguments?.getString("mode")=="providers";start()}
 override fun onStart(){
  if(probe){
   val result=Bundle()
   for(algorithm in listOf("PBKDF2WithHmacSHA512","PBKDF2WithHmacSHA256","PBKDF2WithHmacSHA1"))for(provider in java.security.Security.getProviders()) {
    try{
     val factory=javax.crypto.SecretKeyFactory.getInstance(algorithm,provider)
     val spec=javax.crypto.spec.PBEKeySpec("FAKE-PROBE".toCharArray(),ByteArray(32){1},10000,256)
     val start=SystemClock.elapsedRealtime();factory.generateSecret(spec);spec.clearPassword()
     result.putLong(algorithm+"-"+provider.name,SystemClock.elapsedRealtime()-start)
    }catch(_:java.security.NoSuchAlgorithmException){}
   }
   finish(Activity.RESULT_OK,result);return
  }

  val id="pin-platform-"+UUID.randomUUID().toString()
  val directory=File(targetContext.noBackupFilesDir,id)
  val owner=object:ContextWrapper(targetContext){
   override fun getApplicationContext():Context=this
   override fun getNoBackupFilesDir()=directory
   override fun getSharedPreferences(name:String,mode:Int)=targetContext.getSharedPreferences(id+"-"+name,mode)
  }
  var step="start";var passed=false;var elapsed=0L;var legacyElapsed=0L
  fun mark(value:String){step=value;sendStatus(1,Bundle().apply{putString("step",value)})}
  try {
   check(owner.applicationInfo.uid==android.os.Process.myUid())
   val prefs=owner.getSharedPreferences("dashboard_prefs",0)
   check(prefs.all.isEmpty());check(!directory.exists())
   check(prefs.edit().putString("admin_pin","1234").putString("unrelated","kept").commit())
   val access=AndroidPinAccess.create(owner)
   mark("native-migration")
   val start=SystemClock.elapsedRealtime()
   check(access.verify("1234".toCharArray()).status==PinAccess.Status.SUCCESS)
   elapsed=SystemClock.elapsedRealtime()-start
   check(!prefs.contains("admin_pin"));check(prefs.getBoolean("pin_verifier_initialized",false))
   check(prefs.getString("unrelated",null)=="kept")
   mark("new-instance")
   check(AndroidPinAccess.create(owner).verify("1234".toCharArray()).status==PinAccess.Status.SUCCESS)
   mark("replacement")
   val changed=access.change("1234".toCharArray(),"246802".toCharArray())
   check(changed.status==PinAccess.Status.SUCCESS);val code=checkNotNull(changed.recoveryCode)
   mark("one-time-recovery")
   val recovered=access.recover(code,"135791".toCharArray())
   check(recovered.status==PinAccess.Status.SUCCESS)
   check(access.recover(code,"111111".toCharArray()).status==PinAccess.Status.WRONG)
   code.fill(' ');recovered.recoveryCode!!.fill(' ')
   mark("fresh-auth-disable")
   check(access.disable("135791".toCharArray()).status==PinAccess.Status.DISABLED)
   check(AndroidPinAccess.create(owner).status().status==PinAccess.Status.DISABLED)
   mark("missing-initialized-record")
   check(directory.deleteRecursively())
   check(access.status().status==PinAccess.Status.ERROR)
   mark("api24-compatible-provider")
   val verifier=PinVerifier();val t=SystemClock.elapsedRealtime()
   val record=verifier.create("123456".toCharArray(),PinVerifier.Purpose.PIN,legacyApi24=true)
   check(verifier.verify("123456".toCharArray(),record.encoded,PinVerifier.Purpose.PIN)==PinVerifier.Verdict.MATCH)
   legacyElapsed=SystemClock.elapsedRealtime()-t
   passed=true
  }catch(_:Exception){}catch(_:AssertionError){}
  finally {directory.deleteRecursively();targetContext.deleteSharedPreferences(id+"-dashboard_prefs")}
  finish(if(passed)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply{
   putBoolean("passed",passed);putString("step",step);putLong("migrationMillis",elapsed);putLong("legacyProviderRoundtripMillis",legacyElapsed)
  })
 }
}
