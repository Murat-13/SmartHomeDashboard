package com.example.smarthomedashboard

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

internal class PinDialog(context:Context,private val onSuccess:()->Unit,private val access:PinAccess,private val content:((PinDialog)->Unit)?=null):Dialog(context) {
 constructor(context:Context,onSuccess:()->Unit):this(context,onSuccess,AndroidPinAccess.create(context))
 private val owner=context as? AppCompatActivity
 private val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_PAUSE)dismiss()}
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState);requestWindowFeature(Window.FEATURE_NO_TITLE);if(content==null)setContentView(R.layout.dialog_pin)else content.invoke(this)
  owner?.lifecycle?.addObserver(observer)
  window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  val input=findViewById<EditText>(R.id.pinInput)
  input.inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
  input.filters=arrayOf(android.text.InputFilter.LengthFilter(256));input.isSaveEnabled=false;input.isSaveFromParentEnabled=false;input.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
  val progress=ProgressBar(context).apply {
   tag="pinVerificationProgress"
   isIndeterminate=true
   contentDescription="Проверка PIN"
   visibility=View.GONE
  }
  (input.parent as LinearLayout).addView(progress)
  val confirm=findViewById<Button>(R.id.btnConfirm)
  val confirmLabel=confirm.text.toString()
  confirm.setOnClickListener {
   val chars=input.text.toString().toCharArray();confirm.isEnabled=false;input.isEnabled=false;confirm.text="Проверка…";progress.visibility=View.VISIBLE
   scope.launch {
    val result=try{withContext(Dispatchers.IO){access.verify(chars)}}finally{chars.fill(' ')}
    if(!isShowing)return@launch
    progress.visibility=View.GONE;confirm.isEnabled=true;confirm.text=confirmLabel;input.isEnabled=true;input.text.clear()
    if(result.status in listOf(PinAccess.Status.SUCCESS,PinAccess.Status.DISABLED)){
     if(result.status==PinAccess.Status.SUCCESS)PinSecurityUi.session.grant()
     onSuccess();dismiss()
    }else Toast.makeText(context,PinSecurityUi.message(result),Toast.LENGTH_LONG).show()
   }
  }
  findViewById<Button>(R.id.btnCancel).setOnClickListener{cancel()}
  val activity=owner
  if(activity!=null){
   val recover=Button(context).apply{text="Забыли PIN?";setOnClickListener{dismiss();PinSecurityUi.recover(activity,onSuccess)}}
   (input.parent as? LinearLayout)?.addView(recover)
  }
 }
 override fun dismiss(){owner?.lifecycle?.removeObserver(observer);scope.cancel();findViewById<EditText>(R.id.pinInput)?.text?.clear();super.dismiss()}
}
