package com.example.smarthomedashboard
import android.widget.Button
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
@LooperMode(LooperMode.Mode.PAUSED)
class PinDialogIntegrationTest {
 // Production dialog controller and worker/cancellation path; programmatic views because JVM resources are disabled.
 private fun content(dialog:PinDialog) {
  val root=android.widget.LinearLayout(dialog.context).apply{orientation=android.widget.LinearLayout.VERTICAL}
  root.addView(EditText(dialog.context).apply{id=R.id.pinInput})
  root.addView(Button(dialog.context).apply{id=R.id.btnConfirm})
  root.addView(Button(dialog.context).apply{id=R.id.btnCancel})
  dialog.setContentView(root)
 }
 private fun activity():MainActivity {
  val a=Robolectric.buildActivity(MainActivity::class.java).get();weatherLive(a)
  return a
 }
 private fun disabled(read:()->PinAccess.State?={PinAccess.State(null,null)})=PinAccess(
 object:PinAccess.Storage{override fun read()=read();override fun write(state:PinAccess.State)=true},
 object:PinAccess.Legacy{override fun read():String?=null;override fun remove()=true},
 clock=object:PinAccess.Clock{override fun elapsed()=0L;override fun boot()="test"},worker={check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())})
 @Test fun dialogControllerUsesWorkerAndNeverSavesInputInHierarchy() {
  val a=activity();var granted=false;val dialog=PinDialog(a,{granted=true},disabled(),::content);dialog.show()
  val input=dialog.findViewById<EditText>(R.id.pinInput);input.setText("FAKE-INPUT")
  assertFalse(input.isSaveEnabled);assertFalse(input.isSaveFromParentEnabled)
  val state=android.util.SparseArray<android.os.Parcelable>();input.saveHierarchyState(state)
  assertEquals(0,state.size())
  dialog.findViewById<Button>(R.id.btnConfirm).performClick()
  weatherAwait{granted};assertFalse(dialog.isShowing);assertEquals("",input.text.toString())
 }
 @Test fun cancellingDuringReadNeverGrantsOrRestoresEnteredInput() {
  val a=activity();var granted=false
  val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
  val access=disabled{entered.countDown();release.await(4,java.util.concurrent.TimeUnit.SECONDS);PinAccess.State(null,null)}
  val dialog=PinDialog(a,{granted=true},access,::content);dialog.show();val input=dialog.findViewById<EditText>(R.id.pinInput)
  input.setText("FAKE-CANCELLED");dialog.findViewById<Button>(R.id.btnConfirm).performClick()
  weatherAwait{entered.count==0L};dialog.cancel();release.countDown()
  org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
  assertFalse(granted);assertEquals("",input.text.toString())
 }
 @Test fun storageFailureDoesNotGrantAccess() {
  val a=activity();var granted=false;val dialog=PinDialog(a,{granted=true},disabled{error("artificial storage error")},::content);dialog.show()
  val button=dialog.findViewById<Button>(R.id.btnConfirm);button.performClick();weatherAwait{button.isEnabled}
  assertFalse(granted);assertTrue(dialog.isShowing);dialog.dismiss()
 }
 @Test fun slowVerificationShowsProgressAndLeavesMainLooperResponsive() {
  val a=activity()
  val entered=java.util.concurrent.CountDownLatch(1)
  val release=java.util.concurrent.CountDownLatch(1)
  val access=disabled {
   entered.countDown()
   check(release.await(4,java.util.concurrent.TimeUnit.SECONDS))
   PinAccess.State(null,null)
  }
  val dialog=PinDialog(a,{},access,::content)
  dialog.show()
  try {
   val button=dialog.findViewById<Button>(R.id.btnConfirm)
   button.performClick()
   weatherAwait{entered.count==0L}
   val progress=dialog.window!!.decorView.findViewWithTag<android.widget.ProgressBar>("pinVerificationProgress")
   assertEquals(android.view.View.VISIBLE,progress.visibility)
   assertTrue(progress.isIndeterminate)
   assertFalse(button.isEnabled)
   assertEquals("Проверка…",button.text.toString())
   var mainCallbackRan=false
   android.os.Handler(android.os.Looper.getMainLooper()).post{mainCallbackRan=true}
   org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
   assertTrue(mainCallbackRan)
   assertEquals(1L,release.count)
   release.countDown()
   weatherAwait{!dialog.isShowing}
   assertEquals(android.view.View.GONE,progress.visibility)
  } finally {
   release.countDown()
   dialog.dismiss()
  }
 }

}
