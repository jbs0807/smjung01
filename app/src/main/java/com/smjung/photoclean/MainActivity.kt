package com.smjung.photoclean

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.provider.Settings
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.os.CancellationSignal
import java.util.concurrent.CancellationException
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import java.security.MessageDigest

class MainActivity : Activity() {
 private val selected = mutableListOf<Uri>()
 private val selectedVideos = mutableListOf<Uri>()
 private var candidates = listOf<Uri>()
 private val checkedCandidates = linkedSetOf<Uri>()
 private var busy = false
 private class Task(val label:String) {
  @Volatile var cancelled=false
  val signal=CancellationSignal()
  var thread:Thread?=null
  var done=0; var total=0; var duplicates=0; var groups=0; var errors=0
  var lastReport=0L
 }
 private var task:Task?=null
 private var lastAction=""
 private var pendingDelete=mutableListOf<Uri>()
 private var deletedCount=0
 private var deleteFailed=0
 private var awaitingDeletePermission=false
 private var deletionRunning=false
 private var deleteTotal=0
 private val tiles=linkedMapOf<String,TextView>()
 private lateinit var runButton:TextView
 private lateinit var stopButton:TextView
 private lateinit var trashButton:Button
 private lateinit var workingSpinner:ProgressBar
 private lateinit var pageScroll:ScrollView
 private lateinit var status: TextView
 private lateinit var details: TextView
 private lateinit var progress: ProgressBar
 private lateinit var percent: TextView
 private lateinit var resultBox: LinearLayout
 private val navy = Color.rgb(18,35,65)
 private val teal = Color.rgb(0,156,157)
 private val blue = Color.rgb(15,105,245)

 private fun dp(value:Int):Int = (value*resources.displayMetrics.density+0.5f).toInt()
 private fun lighten(color:Int):Int = Color.rgb((Color.red(color)+55).coerceAtMost(255),(Color.green(color)+55).coerceAtMost(255),(Color.blue(color)+55).coerceAtMost(255))
 private fun shape(color:Int, radius:Float=24f):GradientDrawable =
  GradientDrawable().apply { setColor(color); cornerRadius=radius }
 private fun text(value:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply {
  text=value; textSize=size; setTextColor(color); if(bold) setTypeface(null,Typeface.BOLD)
 }
 private fun button(title:String, action:()->Unit)=Button(this).apply {
  text=title; isAllCaps=false; textSize=15f; setTextColor(Color.WHITE)
  minHeight=dp(52); minimumHeight=dp(52); setPadding(dp(12),dp(10),dp(12),dp(10))
  background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(blue,teal)).apply { cornerRadius=28f }; elevation=6f; setOnClickListener { if(!busy) action() }
 }
 private fun add(container:LinearLayout, view:View, bottom:Int=12) {
  container.addView(view,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(bottom) })
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  window.statusBarColor=navy
  window.navigationBarColor=navy
  val scroll=ScrollView(this).apply { background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(222,239,255),Color.WHITE,Color.rgb(223,252,247))) }
  pageScroll=scroll
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(20)) }
  scroll.addView(root)
  val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(16),dp(18),dp(16)); background=shape(navy,dp(16).toFloat()) }
  val titleRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
  titleRow.addView(text("사진깔끔",24f,Color.WHITE,true),LinearLayout.LayoutParams(0,-2,1f))
  val close=TextView(this).apply {
   text="×"; textSize=28f; setTextColor(Color.WHITE); gravity=android.view.Gravity.CENTER
   contentDescription="앱 종료"; background=shape(Color.rgb(210,48,66),dp(4).toFloat())
   isFocusable=true; setOnClickListener { confirmExit() }
  }
  titleRow.addView(close,LinearLayout.LayoutParams(dp(48),dp(48)))
  add(header,titleRow,6)
  add(header,text("내 사진을 똑똑하게 정리하는 공간",12f,Color.rgb(185,224,229)),0)

  add(root,text("사진 정리 도구",18f,navy,true),12)
  val actions=listOf(
   Triple("전체 사진",0,{ scanPhotos() }),
   Triple("사진 폴더",0,{ chooseFolders() }),
   Triple("동영상 폴더",1,{ chooseVideoFolders() }),
   Triple("선택 사진 보기",0,{ reviewSelected() }),
   Triple("대용량 사진",2,{ showLargePhotos() }),
   Triple("스크린샷",0,{ selectScreenshots() }),
   Triple("앱 관리",3,{ startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS)) })
  )
  val colors=arrayOf(
   intArrayOf(Color.rgb(19,111,244),Color.rgb(4,160,208)),
   intArrayOf(Color.rgb(125,71,235),Color.rgb(188,71,217)),
   intArrayOf(Color.rgb(237,139,28),Color.rgb(247,91,93)),
   intArrayOf(Color.rgb(15,160,119),Color.rgb(10,127,161))
  )
  actions.chunked(2).forEach { pair ->
   val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
   pair.forEachIndexed { index,item ->
    val palette=colors[item.second]
    val tile=TextView(this).apply {
     text=item.first; textSize=16f; setTypeface(null,Typeface.BOLD)
     setTextColor(Color.WHITE); gravity=android.view.Gravity.CENTER
     setPadding(dp(8),dp(10),dp(8),dp(10)); maxLines=2
     setAutoSizeTextTypeUniformWithConfiguration(12,16,1,android.util.TypedValue.COMPLEX_UNIT_SP)
     background=StateListDrawable().apply {
      addState(intArrayOf(android.R.attr.state_activated),shape(Color.rgb(210,100,0),dp(14).toFloat()))
      addState(intArrayOf(android.R.attr.state_selected),GradientDrawable().apply { setColor(Color.rgb(11,64,118)); cornerRadius=dp(14).toFloat(); setStroke(dp(3),Color.rgb(105,240,174)) })
      addState(intArrayOf(android.R.attr.state_pressed),shape(Color.rgb(11,64,118),dp(14).toFloat()))
      addState(intArrayOf(),GradientDrawable(GradientDrawable.Orientation.TL_BR,palette).apply { cornerRadius=dp(14).toFloat(); setStroke(dp(1),Color.argb(110,255,255,255)) })
     }
     elevation=dp(3).toFloat()
     setShadowLayer(2f,1f,2f,Color.argb(70,0,0,0))
     isFocusable=true
     setOnClickListener {
      if(!busy) { lastAction=item.first; refreshControls(); item.third() }
     }
    }
    tiles[item.first]=tile
    line.addView(tile,LinearLayout.LayoutParams(0,dp(56),1f).apply {
     if(index==0) rightMargin=dp(6) else leftMargin=dp(6)
    })
   }
   add(root,line,12)
  }
  val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)); background=shape(Color.WHITE,dp(16).toFloat()) }
  val progressTitle=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
  progressTitle.addView(text("작업 진행 상황",16f,navy,true),LinearLayout.LayoutParams(0,-2,1f))
  workingSpinner=ProgressBar(this).apply { isIndeterminate=true; visibility=View.GONE; contentDescription="작업 진행 중" }
  progressTitle.addView(workingSpinner,LinearLayout.LayoutParams(dp(22),dp(22)))
  add(panel,progressTitle,6)
  status=text("준비 완료 · 사진을 불러와 주세요",14f,navy,true)
  add(panel,status,8)
  details=text("선택한 사진 0장",13f,Color.DKGRAY)
  add(panel,details,10)
  progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=0; progressTintList=android.content.res.ColorStateList.valueOf(teal) }
  add(panel,progress,8)
  percent=text("0%",14f,teal,true)
  add(panel,percent,8)
  resultBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
  add(root,resultBox,12)
  trashButton=button("삭제") { requestTrash() }
  trashButton.visibility=View.GONE
  val bottom=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER; setPadding(dp(16),dp(12),dp(16),dp(12)) }
  fun bottomAction(label:String,color:Int,action:()->Unit):TextView = TextView(this).apply {
   text=label; textSize=20f; setTypeface(null,Typeface.BOLD)
   setTextColor(Color.WHITE); gravity=android.view.Gravity.CENTER
   setPadding(dp(12),dp(10),dp(12),dp(10)); maxLines=1
   setAutoSizeTextTypeUniformWithConfiguration(16,20,1,android.util.TypedValue.COMPLEX_UNIT_SP)
   background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(lighten(color),color)).apply { cornerRadius=dp(16).toFloat(); setStroke(dp(1),Color.WHITE) }; elevation=dp(4).toFloat()
   setOnClickListener { action() }
  }
  runButton=bottomAction("실행",Color.rgb(0,176,118)) { if(!busy) { if(candidates.isNotEmpty()) requestTrash() else analyze() } }
  runButton.visibility=View.GONE
  stopButton=bottomAction("작업 중지",Color.rgb(237,65,89)) { stopWork() }
  bottom.addView(runButton,LinearLayout.LayoutParams(0,dp(60),1f).apply { rightMargin=dp(6) })
  bottom.addView(stopButton,LinearLayout.LayoutParams(-1,dp(60)))
  val shell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); fitsSystemWindows=true }
  shell.addView(header,LinearLayout.LayoutParams(-1,-2))
  shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  shell.addView(panel,LinearLayout.LayoutParams(-1,-2))
  shell.addView(bottom,LinearLayout.LayoutParams(-1,-2))
  refreshControls()
  setContentView(shell)
 }
 private fun refreshControls() {
  tiles.forEach { (name,tile) ->
   tile.isSelected=name==lastAction
   tile.isActivated=busy && name==task?.label
   tile.isEnabled=!busy
   tile.alpha=if(busy && !tile.isActivated) 0.5f else 1f
   tile.contentDescription=name+if(tile.isActivated) " · 작업 중" else if(tile.isSelected) " · 선택됨" else ""
  }
  if(::runButton.isInitialized) {
   workingSpinner.visibility=if(busy) View.VISIBLE else View.GONE
   runButton.isEnabled=!busy
   val runColor=if(busy) Color.rgb(210,100,0) else Color.rgb(0,176,118)
   runButton.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(lighten(runColor),runColor)).apply { cornerRadius=dp(16).toFloat(); setStroke(dp(1),Color.WHITE) }
   runButton.text=if(busy) "작업 중" else if(candidates.isNotEmpty()) "선택 사진 삭제" else "실행"
   stopButton.isEnabled=busy && task?.cancelled==false
   stopButton.alpha=if(stopButton.isEnabled) 1f else 0.4f
   stopButton.text=if(task?.cancelled==true) "중지 중…" else "작업 중지"
   trashButton.isEnabled=!busy
  }
 }
 private fun update(message:String,done:Int=0,total:Int=0) {
  status.text=message; progress.isIndeterminate=false
  progress.progress=if(total>0) (done*100L/total).toInt().coerceIn(0,100) else 0
  percent.text=if(total>0) "${progress.progress}% · ${done}/${total}개" else "대기"
 }
 private fun checkTask(t:Task) {
  if(t.cancelled || Thread.currentThread().isInterrupted) throw CancellationException()
 }
 private fun report(t:Task,message:String,done:Int=0,total:Int=0,force:Boolean=false) {
  checkTask(t); t.done=done; t.total=total
  val now=android.os.SystemClock.elapsedRealtime()
  if(!force && now-t.lastReport<100 && done!=total) return
  t.lastReport=now
  val duplicates=t.duplicates; val groups=t.groups
  runOnUiThread {
   if(task!==t || t.cancelled || isDestroyed) return@runOnUiThread
   update(message,done,total)
   progress.isIndeterminate=total<=0
   if(t.label=="중복 분석") percent.append("\n중복 사진 ${duplicates}장 · ${groups}그룹 (보존 사진 제외)")
  }
 }
 private fun work(label:String,action:(Task)->(() -> Unit)) {
  if(busy) return
  val t=Task(label); task=t; busy=true; lastAction=label; refreshControls()
  update("${label} 준비 중…"); progress.isIndeterminate=true
  val worker=Thread {
   try {
    checkTask(t)
    val complete=action(t)
    checkTask(t)
    runOnUiThread {
     if(task!==t || isDestroyed) return@runOnUiThread
     if(t.cancelled) finishStopped(t) else {
      try { complete() } finally { task=null; busy=false; progress.isIndeterminate=false; refreshControls() }
     }
    }
   } catch(e:Exception) {
    runOnUiThread {
     if(task!==t || isDestroyed) return@runOnUiThread
     if(t.cancelled || e is CancellationException) finishStopped(t) else {
      update("${label} 오류: ${e.message}",t.done,t.total)
      task=null; busy=false; refreshControls()
     }
    }
   }
  }
  t.thread=worker; worker.start()
 }
 private fun stopWork() {
  val t=task ?: return
  t.cancelled=true; t.thread?.interrupt(); t.signal.cancel()
  status.text="${t.label} 중지 중…"; refreshControls()
 }
 private fun finishStopped(t:Task) {
  update("${t.label} 중지됨 · 다시 실행할 수 있습니다",t.done,t.total)
  if(t.label=="중복 분석") percent.append("\n중복 사진 ${t.duplicates}장 발견 · ${t.groups}그룹 (중간 결과)")
  task=null; busy=false; refreshControls()
 }
 private fun confirmExit() {
  AlertDialog.Builder(this).setTitle("사진깔끔 종료")
   .setMessage(if(busy) "진행 중인 작업을 중지하고 종료할까요?" else "앱을 종료하시겠습니까?")
   .setNegativeButton("취소",null).setPositiveButton("종료") { _,_ -> stopWork(); finishAffinity() }.show()
 }
 override fun onDestroy() {
  task?.let { it.cancelled=true; it.thread?.interrupt(); it.signal.cancel() }
  task=null; super.onDestroy()
 }
 private fun pickPhotos() {
  val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true) }
  startActivityForResult(i,1)
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==3) {
   awaitingDeletePermission=false
   if(resultCode==RESULT_OK) continueLegacyDelete() else {
    val remaining=pendingDelete.size
    pendingDelete.clear(); deletionRunning=false
    update("삭제 승인 거부 · 나머지 ${remaining}장 중단",deletedCount+deleteFailed,deleteTotal)
    details.text="삭제 성공 ${deletedCount}장 · 실패 ${deleteFailed}장 · 미처리 ${remaining}장"
    refreshControls()
   }
   return
  }
  if(requestCode==2) {
   if(resultCode==RESULT_OK) { update("시스템 휴지통 요청 승인됨 · 사진 목록을 다시 확인하세요"); checkedCandidates.clear(); candidates=emptyList(); resultBox.removeAllViews() }
   else update("휴지통 이동 취소됨 · 사진은 보존되었습니다")
   return
  }
  if(requestCode==1 && resultCode==RESULT_OK) {
   val found=mutableListOf<Uri>()
   data?.clipData?.let { c -> for(i in 0 until c.itemCount) found.add(c.getItemAt(i).uri) }
   data?.data?.let { if(!found.contains(it)) found.add(it) }
   selected.clear(); selected.addAll(found); candidates=emptyList(); checkedCandidates.clear()
   details.text="선택한 사진 ${found.size}장"
   update("사진 선택 완료",found.size,found.size)
   resultBox.removeAllViews()
  }
 }
 private fun permitted():Boolean {
  val permission=if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
  if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(permission),10); return false }
  return true
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==10) update(if(grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED) "권한 허용됨 · 다시 검색을 눌러주세요" else "사진 접근 권한이 필요합니다")
 }
 private fun loadMedia(t:Task,video:Boolean=false):List<Pair<Uri,String>> {
  val items=mutableListOf<Pair<Uri,String>>()
  val base=if(video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  val projection=arrayOf("_id","bucket_display_name")
  contentResolver.query(base,projection,null,null,null,t.signal)?.use { cursor ->
   val id=cursor.getColumnIndexOrThrow("_id")
   val bucket=cursor.getColumnIndexOrThrow("bucket_display_name")
   val total=cursor.count
   while(cursor.moveToNext()) {
    checkTask(t)
    items.add(ContentUris.withAppendedId(base,cursor.getLong(id)) to (cursor.getString(bucket) ?: "기타"))
    report(t,"${t.label} · 목록 읽는 중…",items.size,total)
   }
  }
  checkTask(t); return items
 }
 private fun selectPhotos(items:List<Uri>,message:String) {
  selected.clear(); selected.addAll(items); candidates=emptyList(); checkedCandidates.clear()
  resultBox.removeAllViews(); details.text="선택한 사진 ${selected.size}장"
  update(message,selected.size,selected.size)
  if(selected.isNotEmpty()) analyze()
 }
 private fun scanPhotos() {
  if(!permitted()) return
  work("전체 사진") { t ->
   val all=loadMedia(t).map { it.first }
   val complete:()->Unit={ selectPhotos(all,"전체 사진 불러오기 완료") }; complete
  }
 }
 private fun chooseFolders() { chooseMediaFolders(false) }
 private fun chooseVideoFolders() { chooseMediaFolders(true) }
 private fun chooseMediaFolders(video:Boolean) {
  val permission=if(video && Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_VIDEO else if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
  if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(permission),10); return }
  val label=if(video) "동영상 폴더" else "사진 폴더"
  work(label) { t ->
   val folders=loadMedia(t,video).groupBy { it.second }.toSortedMap()
   val names=folders.keys.toTypedArray(); val checked=BooleanArray(names.size)
   val complete:()->Unit={
    update("${label} ${names.size}개 검색 완료",t.done,t.total)
    if(names.isNotEmpty()) AlertDialog.Builder(this).setTitle("${label} 선택")
     .setMultiChoiceItems(names.map { "${it} (${folders[it]?.size ?: 0}개)" }.toTypedArray(),checked) { _,i,value -> checked[i]=value }
     .setNegativeButton("취소") { _,_ -> update("폴더 선택 취소 · 기존 선택 유지") }
     .setPositiveButton("선택 완료") { _,_ ->
      val chosen=names.indices.filter { checked[it] }.flatMap { folders[names[it]]!!.map { pair -> pair.first } }
      if(video) {
       selectedVideos.clear(); selectedVideos.addAll(chosen)
       details.text="선택된 동영상 ${chosen.size}개 · 사진 ${selected.size}장"
       update("동영상 선택 완료 · 실행은 사진 중복 분석입니다",chosen.size,chosen.size)
      } else selectPhotos(chosen,"사진 폴더 선택 완료")
     }.show()
   }; complete
  }
 }
 private fun thumbnail(t:Task,uri:Uri,edge:Int):Bitmap? {
  checkTask(t)
  return try {
   val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
   contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
   checkTask(t)
   var sample=1
   while(maxOf(bounds.outWidth,bounds.outHeight)/sample>edge) sample*=2
   val options=BitmapFactory.Options().apply { inSampleSize=sample }
   contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }
  } catch(e:Exception) { checkTask(t); null }
 }
 private fun analyze() {
  if(busy) return
  if(selected.isEmpty()) { refreshControls(); update("먼저 사진이나 폴더를 선택하세요"); return }
  val items=selected.toList()
  candidates=emptyList(); checkedCandidates.clear(); resultBox.removeAllViews()
  work("중복 분석") { t ->
   val hashes=linkedMapOf<String,MutableList<Uri>>()
   report(t,"중복 사진 비교 시작",0,items.size,true)
   val buffer=ByteArray(65536)
   items.forEachIndexed { index,uri ->
    checkTask(t)
    try {
     val md=MessageDigest.getInstance("SHA-256")
     contentResolver.openInputStream(uri)?.use { stream ->
      while(true) {
       checkTask(t); val n=stream.read(buffer); if(n<0) break
       md.update(buffer,0,n)
       report(t,"사진 비교 중 · ${index+1}번째 읽는 중…",index,items.size)
      }
      checkTask(t)
      val hash=md.digest().joinToString("") { "%02x".format(it) }
      val group=hashes.getOrPut(hash) { mutableListOf() }
      if(group.isNotEmpty()) { t.duplicates++; if(group.size==1) t.groups++ }
      group.add(uri)
     } ?: run { t.errors++ }
    } catch(e:Exception) { checkTask(t); t.errors++ }
    report(t,"사진 비교 중…",index+1,items.size)
   }
   val groups=hashes.values.filter { it.size>1 }
   // Bound UI creation and memory, even when a single group contains thousands of copies.
   var remaining=160
   val shown=mutableListOf<List<Uri>>()
   for(group in groups.take(80)) {
    checkTask(t); if(remaining<2) break
    val part=group.take(remaining); shown.add(part); remaining-=part.size
   }
   val previews=linkedMapOf<Uri,Bitmap?>()
   val visible=shown.flatten()
   visible.forEachIndexed { index,uri ->
    checkTask(t)
    report(t,"결과 미리보기 준비 ${index+1}/${visible.size}개",items.size,items.size)
    previews[uri]=thumbnail(t,uri,160)
   }
   val complete:()->Unit={
    candidates=shown.flatMap { it.drop(1) }
    update("분석 완료 · 중복 ${t.groups}그룹",items.size,items.size)
    percent.append("\n중복 사진 ${t.duplicates}장 · ${t.groups}그룹 (보존 사진 제외)")
    details.text="검사 ${items.size}장 · 중복 ${t.duplicates}장 · 읽기 실패 ${t.errors}장"
    renderResults(shown,previews,t.duplicates)
    refreshControls()
    pageScroll.post { pageScroll.smoothScrollTo(0,resultBox.top) }
   }; complete
  }
 }
 private fun renderResults(groups:List<List<Uri>>,previews:Map<Uri,Bitmap?>,duplicateCount:Int) {
  resultBox.removeAllViews()
  add(resultBox,text(if(duplicateCount==0) "중복된 사진이 없습니다." else "중복 ${duplicateCount}장 발견 · 각 그룹 첫 사진은 보존",15f,navy,true),8)
  groups.forEachIndexed { index,group ->
   val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(8)); background=shape(Color.rgb(241,247,253),18f) }
   add(card,text("중복 그룹 ${index+1} · ${group.size}장 표시",15f,navy,true),6)
   group.forEachIndexed { i,uri ->
    val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
    val preview=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setImageBitmap(previews[uri]) }
    line.addView(preview,LinearLayout.LayoutParams(dp(64),dp(64)))
    val check=CheckBox(this).apply {
     text=if(i==0) "원본 보존 (선택 불가)" else "중복됨 · 삭제대상"
     isEnabled=i!=0; isChecked=i!=0
     if(i!=0) checkedCandidates.add(uri)
     setOnCheckedChangeListener { _,value -> if(value) checkedCandidates.add(uri) else checkedCandidates.remove(uri) }
    }
    line.addView(check,LinearLayout.LayoutParams(0,-2,1f)); add(card,line,6)
   }
   add(resultBox,card,12)
  }
  if(candidates.size<duplicateCount) add(resultBox,text("화면에는 최대 80그룹·160장만 표시합니다. 표시하지 않은 사진은 휴지통 대상으로 선택되지 않습니다.",13f,Color.DKGRAY),8)
 }
 private fun selectScreenshots() {
  if(!permitted()) return
  work("스크린샷") { t ->
   val found=loadMedia(t).filter { it.second.contains("screenshot",true) || it.second.contains("스크린샷",true) }.map { it.first }
   val complete:()->Unit={ selectPhotos(found,"스크린샷 선택 완료") }; complete
  }
 }
 private fun showLargePhotos() {
  if(!permitted()) return
  work("대용량 사진") { t ->
   val found=mutableListOf<Pair<Uri,Long>>()
   val base=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
   contentResolver.query(base,arrayOf("_id","_size"),"_size >= ?",arrayOf((10L*1024*1024).toString()),"_size DESC",t.signal)?.use { cursor ->
    val id=cursor.getColumnIndexOrThrow("_id"); val size=cursor.getColumnIndexOrThrow("_size")
    val total=cursor.count
    while(cursor.moveToNext()) {
     checkTask(t); found.add(ContentUris.withAppendedId(base,cursor.getLong(id)) to cursor.getLong(size))
     report(t,"대용량 사진 검색 중…",found.size,total)
    }
   }
   val complete:()->Unit={
    selectPhotos(found.map { it.first },"10MB 이상 사진 검색 완료")
    found.take(40).forEach { item -> add(resultBox,text("사진 · %.1f MB".format(item.second/1048576.0),14f,navy),6) }
    add(resultBox,text("10MB 이상 사진 ${found.size}장 선택됨 · 실행으로 중복을 분석할 수 있습니다.",14f,navy),8)
   }; complete
  }
 }
 private fun reviewSelected() {
  if(selected.isEmpty()) { Toast.makeText(this,"선택된 사진이 없습니다",Toast.LENGTH_SHORT).show(); return }
  val photos=selected.toList()
  work("선택 사진 보기") { t ->
   val shown=photos.take(100)
   val previews=linkedMapOf<Uri,Bitmap?>()
   shown.forEachIndexed { index,uri ->
    previews[uri]=thumbnail(t,uri,300)
    report(t,"선택 사진 미리보기 준비 중…",index+1,shown.size)
   }
   val complete:()->Unit={
    update("선택 사진 미리보기 준비 완료",shown.size,shown.size)
    val wrap=ScrollView(this)
    val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(8)) }
    wrap.addView(column)
    add(column,text("선택된 사진 ${photos.size}장 · 한 줄 2장씩 표시",16f,navy,true),12)
    val side=maxOf(dp(100),(resources.displayMetrics.widthPixels-dp(72))/2)
    shown.chunked(2).forEachIndexed { rowIndex,pair ->
     val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
     pair.forEachIndexed { colIndex,uri ->
      val cell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(4,4,4,8); background=shape(Color.rgb(238,246,255),12f) }
      val thumb=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setImageBitmap(previews[uri]) }
      cell.addView(thumb,LinearLayout.LayoutParams(-1,side))
      add(cell,text("사진 ${rowIndex*2+colIndex+1}",12f,navy,true),0)
      cell.setOnClickListener {
       try { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri,"image/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
       catch(e:Exception) { Toast.makeText(this,"사진 열기 실패",Toast.LENGTH_SHORT).show() }
      }
      line.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply { if(colIndex==0) rightMargin=6 else leftMargin=6 })
     }
     if(pair.size==1) line.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
     add(column,line,8)
    }
    if(photos.size>100) add(column,text("처음 100장만 표시합니다. 나머지 사진도 분석 대상에 포함됩니다.",13f,Color.DKGRAY),8)
    AlertDialog.Builder(this).setTitle("선택된 사진 확인").setView(wrap)
     .setNegativeButton("닫기",null).setPositiveButton("중복 분석") { _,_ -> analyze() }.show()
   }; complete
  }
 }
 private fun continueLegacyDelete() {
  if(Build.VERSION.SDK_INT>=30 || awaitingDeletePermission || !deletionRunning) return
  while(pendingDelete.isNotEmpty()) {
   val uri=pendingDelete.first()
   try {
    val rows=contentResolver.delete(uri,null,null)
    if(rows>0) { deletedCount++; checkedCandidates.remove(uri) } else deleteFailed++
    pendingDelete.removeAt(0)
   } catch(e:android.app.RecoverableSecurityException) {
    try {
     awaitingDeletePermission=true
     update("Android 10 시스템 승인 대기 · ${deletedCount+deleteFailed+1}/${deleteTotal}장",deletedCount+deleteFailed,deleteTotal)
     startIntentSenderForResult(e.userAction.actionIntent.intentSender,3,null,0,0,0)
     return
    } catch(ex:Exception) {
     awaitingDeletePermission=false; deleteFailed++; pendingDelete.removeAt(0)
    }
   } catch(e:Exception) {
    deleteFailed++; pendingDelete.removeAt(0)
   }
   update("사진 삭제 처리 중 · 성공 ${deletedCount}장 · 실패 ${deleteFailed}장",deletedCount+deleteFailed,deletedCount+deleteFailed+pendingDelete.size)
  }
  deletionRunning=false
  update("삭제 완료 · 성공 ${deletedCount}장 · 실패 ${deleteFailed}장",deleteTotal,deleteTotal)
  details.text="Android 10 영구 삭제 결과 · 성공 ${deletedCount}장 · 실패 ${deleteFailed}장"
  candidates=emptyList(); resultBox.removeAllViews()
  Toast.makeText(this,"삭제 ${deletedCount}장, 실패 ${deleteFailed}장",Toast.LENGTH_LONG).show()
 }
 private fun requestTrash() {
  if(checkedCandidates.isEmpty()) { Toast.makeText(this,"선택된 중복 후보가 없습니다",Toast.LENGTH_SHORT).show(); return }
  val count=checkedCandidates.size
  val legacy=Build.VERSION.SDK_INT<30
  AlertDialog.Builder(this).setTitle(if(legacy) "영구 삭제 확인 (Android 10)" else "휴지통 이동 확인")
   .setMessage(if(legacy) "Android 10에서는 시스템 휴지통을 지원하지 않습니다. 선택한 중복 후보 ${count}장을 영구 삭제합니다. 복구할 수 없습니다. 각 그룹 첫 사진은 보존합니다. 계속할까요?" else "중복 후보 ${count}장을 시스템 휴지통으로 이동할까요? 각 그룹 첫 사진은 보존합니다.")
   .setNegativeButton("취소",null).setPositiveButton(if(legacy) "영구 삭제" else "계속") { _,_ ->
    if(!legacy) {
     try { startIntentSenderForResult(MediaStore.createTrashRequest(contentResolver,checkedCandidates.toList(),true).intentSender,2,null,0,0,0) }
     catch(e:Exception) { update("휴지통 이동 오류: ${e.message}") }
    } else {
     pendingDelete=checkedCandidates.toMutableList()
     deletedCount=0; deleteFailed=0; deleteTotal=pendingDelete.size; deletionRunning=true
     continueLegacyDelete()
    }
   }.show()
 }
}
