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

class MainActivity : Activity() {
 private val selected = mutableListOf<Uri>()
 private val selectedVideos = mutableListOf<Uri>()
 private var candidates = listOf<Uri>()
 private val checkedCandidates = linkedSetOf<Uri>()
 private var busy = false
 private val mediaDates = java.util.concurrent.ConcurrentHashMap<Uri,Long>()
 private val mediaSizes = java.util.concurrent.ConcurrentHashMap<Uri,Long>()
 private val mediaLocations = java.util.concurrent.ConcurrentHashMap<Uri,Pair<String,String>>()
 private val mediaRevision=java.util.concurrent.atomic.AtomicLong()
 private data class MediaCache(val revision:Long,val volumes:Set<String>,val items:List<Pair<Uri,String>>)
 private val mediaCache=mutableMapOf<Boolean,MediaCache>()
 private val mediaObserver=object:android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
  override fun onChange(selfChange:Boolean) { mediaRevision.incrementAndGet() }
 }
 private class FolderNode(val name:String,val parent:FolderNode?=null,val document:Uri?=null) {
  val children=linkedMapOf<String,FolderNode>()
  val photos=mutableListOf<Uri>()
  var count=0
  var loaded=false
 }
 private var closing = false
 private val previewTasks = mutableSetOf<Task>()
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
  contentResolver.registerContentObserver(Uri.parse("content://media"),true,mediaObserver)
  window.statusBarColor=navy
  window.navigationBarColor=navy
  val scroll=ScrollView(this).apply { background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(222,239,255),Color.WHITE,Color.rgb(223,252,247))) }
  pageScroll=scroll
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(20)) }
  scroll.addView(root)
  val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(16),dp(18),dp(16)); background=shape(navy,dp(16).toFloat()) }
  val titleRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
  titleRow.addView(text("중복사진 깔끔 정리 툴",20f,Color.WHITE,true),LinearLayout.LayoutParams(0,-2,1f))
  val close=TextView(this).apply {
   text="×"; textSize=20f; setTextColor(Color.WHITE); gravity=android.view.Gravity.CENTER
   contentDescription="앱 종료"; background=shape(Color.rgb(210,48,66),dp(4).toFloat())
   isFocusable=true; setOnClickListener { exitImmediately() }
  }
  titleRow.addView(close,LinearLayout.LayoutParams(dp(24),dp(24)))
  add(header,titleRow,6)
  add(header,text("내 사진을 똑똑하게 정리하는 공간",12f,Color.rgb(185,224,229)),0)

  add(root,text("사진 정리 도구",18f,navy,true),12)
  val actions=listOf(
   Triple("동영상 폴더",1,{ chooseVideoFolders() }),
   Triple("전체 사진",0,{ scanPhotos() }),
   Triple("대용량 사진",2,{ showLargePhotos() }),
   Triple("사진 폴더",0,{ chooseFolders() }),
   Triple("스크린샷",0,{ selectScreenshots() }),
   Triple("선택 사진 보기",0,{ reviewSelected() })
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
  runButton.visibility=View.VISIBLE
  stopButton=bottomAction("작업 중지",Color.rgb(237,65,89)) { stopWork() }
  bottom.addView(runButton,LinearLayout.LayoutParams(0,dp(60),1f).apply { rightMargin=dp(6) })
  bottom.addView(stopButton,LinearLayout.LayoutParams(0,dp(60),1f).apply { leftMargin=dp(6) })
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
   runButton.text=if(busy) "작업 중" else "실행"
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
  if(!force && now-t.lastReport<150) return
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
     if(task!==t || closing || isFinishing || isDestroyed) return@runOnUiThread
     if(t.cancelled) finishStopped(t) else {
      try { complete() } finally { task=null; busy=false; progress.isIndeterminate=false; refreshControls() }
     }
    }
   } catch(e:Exception) {
    runOnUiThread {
     if(task!==t || closing || isFinishing || isDestroyed) return@runOnUiThread
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
 private fun exitImmediately() {
  closing=true
  stopWork()
  previewTasks.forEach { it.cancelled=true; it.thread?.interrupt(); it.signal.cancel() }
  previewTasks.clear()
  deletionRunning=false; pendingDelete.clear()
  finishAndRemoveTask()
 }
 override fun onResume() {
  super.onResume()
  mediaRevision.incrementAndGet()
 }
 override fun onDestroy() {
  contentResolver.unregisterContentObserver(mediaObserver)
  previewTasks.forEach { it.cancelled=true; it.thread?.interrupt(); it.signal.cancel() }
  previewTasks.clear()
  task?.let { it.cancelled=true; it.thread?.interrupt(); it.signal.cancel() }
  task=null; super.onDestroy()
 }
 private fun pickPhotos() {
  val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true) }
  startActivityForResult(i,1)
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==21 && resultCode==RESULT_OK) {
   data?.data?.let { tree ->
    try { contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_:SecurityException) { }
    val id=android.provider.DocumentsContract.getTreeDocumentId(tree)
    val doc=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,id)
    showFolderBrowser(FolderNode(id.substringAfter(':').ifEmpty { "선택한 저장소" },document=doc),false)
   }
   return
  }
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
  val volumes=MediaStore.getExternalVolumeNames(this).toSet()
  val revision=mediaRevision.get()
  mediaCache[video]?.takeIf { it.revision==revision && it.volumes==volumes }?.let {
   checkTask(t); return it.items
  }
  val items=mutableListOf<Pair<Uri,String>>()
  val base=if(video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  val projection=arrayOf("_id","bucket_display_name","datetaken","date_added","_size","volume_name","relative_path")
  contentResolver.query(base,projection,null,null,null,t.signal)?.use { cursor ->
   val total=cursor.count
   while(cursor.moveToNext()) {
    checkTask(t)
    val volume=cursor.getString(5) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
    val volumeBase=if(video) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
    val uri=ContentUris.withAppendedId(volumeBase,cursor.getLong(0))
    val bucket=cursor.getString(1) ?: "기타"
    val path=cursor.getString(6) ?: "$bucket/"
    mediaDates[uri]=cursor.getLong(2).takeIf { it>0 } ?: (cursor.getLong(3)*1000L)
    mediaSizes[uri]=if(cursor.isNull(4)) -1L else cursor.getLong(4)
    mediaLocations[uri]=volume to path
    items.add(uri to bucket)
    if(items.size%256==0 || items.size==total) report(t,"${t.label} · 목록 읽는 중…",items.size,total)
   }
  }
  checkTask(t)
  if(mediaRevision.get()==revision) mediaCache[video]=MediaCache(revision,volumes,items)
  return items
 }
 private fun selectPhotos(items:List<Uri>,message:String) {
  selected.clear(); selected.addAll(items); candidates=emptyList(); checkedCandidates.clear()
  resultBox.removeAllViews(); details.text="선택한 사진 ${selected.size}장"
  update(message,selected.size,selected.size)
 }
 private fun scanPhotos() {
  if(!permitted()) return
  work("전체 사진") { t ->
   val all=loadMedia(t).map { it.first }
   val complete:()->Unit={ selectPhotos(all,"전체 사진 불러오기 완료"); reviewMedia(all,false,"전체 사진") }; complete
  }
 }
 private fun chooseFolders() { chooseMediaFolders(false) }
 private fun chooseVideoFolders() { chooseMediaFolders(true) }
 private fun chooseMediaFolders(video:Boolean) {
  val permission=if(video && Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_VIDEO else if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
  if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(permission),10); return }
  work(if(video) "동영상 폴더" else "사진 폴더") { t ->
   val all=loadMedia(t,video)
   val root=FolderNode("저장소 선택")
   val storage=getSystemService(android.os.storage.StorageManager::class.java).storageVolumes
   MediaStore.getExternalVolumeNames(this).sorted().forEach { volume ->
    val info=storage.firstOrNull { if(volume==MediaStore.VOLUME_EXTERNAL_PRIMARY) it.isPrimary else it.uuid?.equals(volume,true)==true }
    val label=if(volume==MediaStore.VOLUME_EXTERNAL_PRIMARY) "내부 저장소" else info?.getDescription(this) ?: "SD 카드 · $volume"
    root.children[volume]=FolderNode(label,root)
   }
   all.forEach { (uri,_) ->
    checkTask(t)
    val (volume,path)=mediaLocations.getValue(uri)
    var node=root.children.getOrPut(volume) { FolderNode(volume,root) }
    root.count++; node.count++
    path.split('/').filter { it.isNotEmpty() }.forEach { name ->
     val parent=node
     node=parent.children.getOrPut(name) { FolderNode(name,parent) }
     node.count++
    }
    node.photos.add(uri)
   }
   val complete:()->Unit={ update("저장소와 하위 폴더 준비 완료",all.size,all.size); showFolderBrowser(root,video) }; complete
  }
 }
 private fun readDocumentFolder(t:Task,node:FolderNode) {
  if(node.loaded) return
  val doc=node.document ?: return
  val query=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(doc,android.provider.DocumentsContract.getDocumentId(doc))
  val children=linkedMapOf<String,FolderNode>()
  val photos=mutableListOf<Uri>()
  contentResolver.query(query,arrayOf("document_id","_display_name","mime_type","_size","last_modified"),null,null,null,t.signal)?.use { cursor ->
   while(cursor.moveToNext()) {
    checkTask(t)
    val id=cursor.getString(0); val name=cursor.getString(1) ?: id
    val uri=android.provider.DocumentsContract.buildDocumentUriUsingTree(doc,id)
    val mime=cursor.getString(2) ?: ""
    if(mime==android.provider.DocumentsContract.Document.MIME_TYPE_DIR) children[id]=FolderNode(name,node,uri)
    else if(mime.startsWith("image/")) {
     val mediaUri=try { MediaStore.getMediaUri(this,uri) } catch(_:Exception) { null }
     val photo=mediaUri ?: uri
     photos.add(photo); mediaSizes[photo]=if(cursor.isNull(3)) -1L else cursor.getLong(3)
     if(mediaUri==null || !mediaDates.containsKey(photo)) mediaDates[photo]=cursor.getLong(4)
    }
   }
  }
  checkTask(t)
  node.children.clear(); node.children.putAll(children)
  node.photos.clear(); node.photos.addAll(photos); node.loaded=true; node.count=photos.size
 }
 private fun collectFolder(t:Task,node:FolderNode,includeChildren:Boolean):List<Uri> {
  val found=mutableListOf<Uri>()
  val pending=java.util.ArrayDeque<FolderNode>(); pending.add(node)
  val visited=hashSetOf<String>()
  while(pending.isNotEmpty()) {
   checkTask(t)
   val next=pending.removeFirst()
   if(next.document!=null && !visited.add(next.document.toString())) continue
   readDocumentFolder(t,next)
   found.addAll(next.photos)
   if(includeChildren) pending.addAll(next.children.values)
   report(t,"폴더 읽는 중 · ${found.size}장",found.size,0)
  }
  return found.distinct()
 }
 private fun showFolderBrowser(root:FolderNode,video:Boolean) {
  var current=root
  val chosen=linkedSetOf<FolderNode>()
  var entries=listOf<FolderNode>()
  val wrap=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(8)) }
  val location=text("",14f,navy,true)
  val up=button("상위 폴더") {}
  add(wrap,location,6); add(wrap,up,6)
  add(wrap,text(if(root.document==null) "사진·동영상이 있는 하위 폴더 표시" else "폴더를 눌러 하위 폴더로 이동 · 날짜는 파일 수정일",11f,Color.DKGRAY),6)
  val grid=GridView(this).apply { numColumns=3; horizontalSpacing=dp(8); verticalSpacing=dp(8); stretchMode=GridView.STRETCH_COLUMN_WIDTH }
  val includeChildren=CheckBox(this).apply { text="하위 폴더까지 포함"; isChecked=true }
  val openPhotos=button(if(video) "이 폴더 동영상 보기" else "이 폴더 사진 보기") {}
  val loading=text("",13f,teal)
  wrap.addView(grid,LinearLayout.LayoutParams(-1,0,1f))
  add(wrap,loading,2); add(wrap,includeChildren,2); add(wrap,openPhotos,4)
  val dialog=AlertDialog.Builder(this).setTitle(if(video) "동영상 폴더" else "사진 폴더").setView(wrap)
   .setNegativeButton("닫기",null).apply {
    if(!video) setNeutralButton("다른 폴더 열기") { _,_ ->
     try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
     },21) } catch(_:Exception) { Toast.makeText(this@MainActivity,"폴더 선택 앱을 열 수 없습니다",Toast.LENGTH_SHORT).show() }
    }
   }.create()
  val adapter=object:BaseAdapter() {
   override fun getCount()=entries.size
   override fun getItem(position:Int)=entries[position]
   override fun getItemId(position:Int)=position.toLong()
   override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup?):View {
    val node=entries[position]
    val cell=LinearLayout(this@MainActivity).apply {
     orientation=LinearLayout.VERTICAL; gravity=android.view.Gravity.CENTER
     setPadding(dp(4),dp(8),dp(4),dp(8)); background=shape(Color.rgb(244,247,250),dp(10).toFloat())
    }
    val icon=object:View(this@MainActivity) {
     private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
     override fun onDraw(canvas:android.graphics.Canvas) {
      val w=width.toFloat(); val h=height.toFloat()
      val drive=node.parent===root && root.document==null
      paint.color=if(drive) Color.rgb(91,112,148) else Color.rgb(210,157,71)
      if(!drive) canvas.drawRoundRect(w*0.1f,h*0.1f,w*0.53f,h*0.45f,dp(5).toFloat(),dp(5).toFloat(),paint)
      if(!drive) paint.color=Color.rgb(237,184,94)
      canvas.drawRoundRect(w*0.06f,h*0.28f,w*0.94f,h*0.94f,dp(6).toFloat(),dp(6).toFloat(),paint)
      if(drive) { paint.color=Color.rgb(100,230,150); canvas.drawCircle(w*0.2f,h*0.76f,dp(3).toFloat(),paint) }
     }
    }
    cell.addView(icon,LinearLayout.LayoutParams(dp(64),dp(56)))
    add(cell,text(node.name,14f,navy,true).apply { gravity=android.view.Gravity.CENTER; maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END },2)
    add(cell,text(if(node.document!=null && !node.loaded) "열기" else "${node.count}개",12f,Color.DKGRAY).apply { gravity=android.view.Gravity.CENTER },0)
    cell.addView(CheckBox(this@MainActivity).apply {
     text="선택"; textSize=12f; isFocusable=false; isChecked=node in chosen
     setOnCheckedChangeListener { _,checked ->
      if(checked) chosen.add(node) else chosen.remove(node)
      openPhotos.text=if(chosen.isEmpty()) "이 폴더 ${if(video) "동영상" else "사진"} 보기" else "선택한 ${chosen.size}개 폴더 보기"
      openPhotos.isEnabled=chosen.isNotEmpty() || current!==root || root.document!=null
     }
    })
    return cell
   }
  }
  grid.adapter=adapter
  fun render(node:FolderNode) {
   current=node
   entries=node.children.values.sortedBy { it.name.lowercase(java.util.Locale.KOREA) }
   val parts=mutableListOf<String>(); var n:FolderNode?=node
   while(n!=null) { parts.add(n.name); n=n.parent }
   location.text=parts.reversed().joinToString(" / ")
   up.isEnabled=node.parent!=null
   openPhotos.isEnabled=chosen.isNotEmpty() || node!==root || root.document!=null
   loading.text=if(entries.isEmpty() && node.photos.isEmpty()) "표시할 사진이나 하위 폴더가 없습니다" else "현재 폴더 ${node.photos.size}개 · 하위 폴더 ${entries.size}개"
   adapter.notifyDataSetChanged(); grid.setSelection(0)
  }
  fun open(node:FolderNode) {
   if(node.document!=null && !node.loaded) {
    loading.text="폴더 읽는 중…"
    work("폴더 탐색") { t ->
     readDocumentFolder(t,node)
     val complete:()->Unit={ if(dialog.isShowing) render(node) }; complete
    }
   } else render(node)
  }
  grid.setOnItemClickListener { _,_,position,_ -> if(!busy) open(entries[position]) }
  up.setOnClickListener { if(!busy) current.parent?.let { open(it) } }
  openPhotos.setOnClickListener {
   if(!busy) {
    val node=current; val recursive=includeChildren.isChecked
    val targets=if(chosen.isEmpty()) listOf(node) else chosen.toList()
    work("폴더 사진 불러오기") { t ->
     val photos=targets.flatMap { collectFolder(t,it,recursive) }.distinct()
     val complete:()->Unit={
      if(dialog.isShowing) {
       if(video) { selectedVideos.clear(); selectedVideos.addAll(photos) }
       else selectPhotos(photos,"${node.name} · ${photos.size}장 선택")
       reviewMedia(photos,video,node.name)
      }
     }; complete
    }
   }
  }
  dialog.setOnDismissListener { if(task?.label in listOf("폴더 탐색","폴더 사진 불러오기")) stopWork() }
  dialog.setOnShowListener {
   dialog.window?.setLayout(-1,(resources.displayMetrics.heightPixels*0.86f).toInt())
   // The parent work callback releases busy before this posted navigation runs.
   wrap.post { if(dialog.isShowing) open(root) }
  }
  dialog.show()
 }
 private fun thumbnail(t:Task,uri:Uri,edge:Int):Bitmap? {
  checkTask(t)
  try { return contentResolver.loadThumbnail(uri,android.util.Size(edge,edge),t.signal) }
  catch(_:Exception) { checkTask(t) }
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
   val result=DuplicateFinder.find(items,
    sizeOf={ uri ->
     mediaSizes[uri] ?: try {
      contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.SIZE),null,null,null,t.signal)?.use { c ->
       if(c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
      }
     } catch(_:Exception) { checkTask(t); null }
    },
    open={ uri -> contentResolver.openInputStream(uri) },
    check={ checkTask(t) },
    progress={ stage,done,total,duplicates,groups ->
     t.duplicates=duplicates; t.groups=groups
     report(t,"${stage} · ${done}/${total}장",done,total)
    })
   val groups=result.groups
   t.errors=result.errors
   t.groups=groups.size; t.duplicates=groups.sumOf { it.size-1 }
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
    details.text="검사 ${items.size}장 · 정밀 비교 ${result.verified}장 · 중복 ${t.duplicates}장 · 읽기 실패 ${t.errors}장"
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
   val complete:()->Unit={ selectPhotos(found,"스크린샷 선택 완료"); reviewMedia(found,false,"스크린샷 보기") }; complete
  }
 }
 private fun showLargePhotos() {
  if(!permitted()) return
  work("대용량 사진") { t ->
   val found=mutableListOf<Pair<Uri,Long>>()
   val base=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
   contentResolver.query(base,arrayOf("_id","_size","datetaken","date_added"),"_size >= ?",arrayOf((10L*1024*1024).toString()),"_size DESC",t.signal)?.use { cursor ->
    val id=cursor.getColumnIndexOrThrow("_id"); val size=cursor.getColumnIndexOrThrow("_size")
    val total=cursor.count
    while(cursor.moveToNext()) {
     checkTask(t)
     val uri=ContentUris.withAppendedId(base,cursor.getLong(id))
     mediaDates[uri]=cursor.getLong(2).takeIf { it>0 } ?: (cursor.getLong(3)*1000L)
     mediaSizes[uri]=cursor.getLong(size)
     found.add(uri to cursor.getLong(size))
     report(t,"대용량 사진 검색 중…",found.size,total)
    }
   }
   val complete:()->Unit={
    selectPhotos(found.map { it.first },"10MB 이상 사진 검색 완료")
    reviewMedia(found.map { it.first },false,"대용량 사진 보기")
   }; complete
  }
 }
 private fun reviewSelected() = reviewMedia(selected.toList(),false,"선택된 사진 확인")
 private fun reviewMedia(photos:List<Uri>,video:Boolean,title:String) {
  if(photos.isEmpty()) { Toast.makeText(this,"표시할 항목이 없습니다",Toast.LENGTH_SHORT).show(); return }
  val pageSize=20
  val columns=if(video) 2 else 4
  var newestFirst=true
  var ordered=photos.sortedWith(compareByDescending<Uri> { mediaDates[it] ?: 0L }.thenBy { it.toString() })
  val dateFormat=java.text.SimpleDateFormat("yyyy.MM.dd",java.util.Locale.KOREA)
  val pageCount=(photos.size+pageSize-1)/pageSize
  val wrap=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(8),dp(8),dp(8)) }
  val pageLabel=text("",15f,navy,true)
  val gridScroll=ScrollView(this)
  val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
  gridScroll.addView(column)
  val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
  val previous=button("이전 페이지") {}
  val next=button("다음 페이지") {}
  nav.addView(previous,LinearLayout.LayoutParams(0,dp(48),1f).apply { rightMargin=dp(4) })
  nav.addView(next,LinearLayout.LayoutParams(0,dp(48),1f).apply { leftMargin=dp(4) })
  add(wrap,text("${if(video) "동영상" else "사진"} ${photos.size}개 · 한 줄 ${columns}개",16f,navy,true),6)
  val sortRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
  val newest=button("최신순") {}
  val oldest=button("오래된순") {}
  sortRow.addView(newest,LinearLayout.LayoutParams(0,dp(48),1f).apply { rightMargin=dp(4) })
  sortRow.addView(oldest,LinearLayout.LayoutParams(0,dp(48),1f).apply { leftMargin=dp(4) })
  add(wrap,sortRow,4)
  add(wrap,text("작성 날짜: 촬영일 우선 · 없으면 기기 등록일",11f,Color.DKGRAY),4)
  add(wrap,pageLabel,6)
  wrap.addView(gridScroll,LinearLayout.LayoutParams(-1,0,1f))
  add(wrap,nav,0)
  val dialog=AlertDialog.Builder(this).setTitle(title).setView(wrap)
   .setNegativeButton("닫기",null).setPositiveButton(if(video) "확인" else "중복 분석") { _,_ ->
    if(!video) { selectPhotos(ordered,"사진 선택 완료"); analyze() }
   }.create()
  var page=0
  var previewTask:Task?=null
  fun showPage(index:Int) {
   previewTask?.let { it.cancelled=true; it.thread?.interrupt(); it.signal.cancel(); previewTasks.remove(it) }
   page=index.coerceIn(0,pageCount-1)
   val current=page
   val offset=current*pageSize
   val items=ordered.subList(offset,minOf(offset+pageSize,ordered.size))
   newest.text=if(newestFirst) "✓ 최신순" else "최신순"
   oldest.text=if(newestFirst) "오래된순" else "✓ 오래된순"
   newest.alpha=if(newestFirst) 1f else 0.55f
   oldest.alpha=if(newestFirst) 0.55f else 1f
   pageLabel.text="${current+1} / ${pageCount} 페이지 · ${offset+1}~${offset+items.size} / ${photos.size}장"
   previous.isEnabled=current>0
   next.isEnabled=current<pageCount-1
   column.removeAllViews()
   gridScroll.scrollTo(0,0)
   val available=wrap.width.takeIf { it>0 } ?: (resources.displayMetrics.widthPixels-dp(48))
   val side=maxOf(dp(32),(available-dp(16)-dp(6)*columns)/columns)
   val thumbs=mutableListOf<Pair<Uri,ImageView>>()
   items.chunked(columns).forEachIndexed { rowIndex,pair ->
    val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
    pair.forEachIndexed { colIndex,uri ->
     val cell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(2),dp(2),dp(2),dp(4)); background=shape(Color.rgb(238,246,255),12f) }
     val date=mediaDates[uri]?.takeIf { it>0 }?.let { dateFormat.format(java.util.Date(it)) } ?: "날짜 없음"
     val thumb=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; contentDescription="${if(video) "동영상" else "사진"} · $date · 눌러서 크게 보기" }
     thumbs.add(uri to thumb)
     cell.addView(thumb,LinearLayout.LayoutParams(-1,side))
     if(video) add(cell,text("▶ 동영상 ${offset+rowIndex*columns+colIndex+1}",12f,navy,true),0)
     add(cell,text(date,10f,navy,true).apply {
      gravity=android.view.Gravity.CENTER; maxLines=1
      setAutoSizeTextTypeUniformWithConfiguration(8,11,1,android.util.TypedValue.COMPLEX_UNIT_SP)
     },0)
     cell.setOnClickListener {
      try { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri,if(video) "video/*" else "image/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) }
      catch(e:Exception) { Toast.makeText(this,"미디어 열기 실패",Toast.LENGTH_SHORT).show() }
     }
     line.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(1); rightMargin=dp(1) })
    }
    repeat(columns-pair.size) { line.addView(View(this),LinearLayout.LayoutParams(0,1,1f).apply { leftMargin=dp(1); rightMargin=dp(1) }) }
    add(column,line,8)
   }
   val t=Task("사진 페이지 미리보기"); previewTask=t; previewTasks.add(t)
   t.thread=Thread {
    thumbs.forEachIndexed { i,(uri,view) ->
     if(t.cancelled) return@Thread
     val bitmap=try {
      thumbnail(t,uri,side.coerceAtMost(300))
     } catch(e:Exception) { null }
     runOnUiThread {
      if(previewTask===t && !t.cancelled && !closing && !isDestroyed && dialog.isShowing) {
       view.setImageBitmap(bitmap)
       pageLabel.text="${current+1}/${pageCount} 페이지 · ${offset+1}~${offset+items.size}/${photos.size}장 · ${i+1}/${items.size} 로딩"
      } else bitmap?.recycle()
     }
    }
   }.apply { start() }
  }
  fun changeSort(newestOrder:Boolean) {
   if(newestFirst==newestOrder) return
   newestFirst=newestOrder
   val comparator=compareBy<Uri> { mediaDates[it] ?: 0L }.thenBy { it.toString() }
   ordered=photos.sortedWith(if(newestFirst) comparator.reversed() else comparator)
   showPage(0)
  }
  newest.setOnClickListener { changeSort(true) }
  oldest.setOnClickListener { changeSort(false) }
  previous.setOnClickListener { showPage(page-1) }
  next.setOnClickListener { showPage(page+1) }
  dialog.setOnDismissListener { previewTask?.let { it.cancelled=true; it.thread?.interrupt(); it.signal.cancel(); previewTasks.remove(it) }; previewTask=null }
  dialog.setOnShowListener {
   dialog.window?.setLayout(-1,(resources.displayMetrics.heightPixels*0.82f).toInt())
   wrap.post { if(dialog.isShowing) showPage(0) }
  }
  dialog.show()
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
  if(checkedCandidates.isEmpty()) { Toast.makeText(this,"선택된 삭제대상이 없습니다",Toast.LENGTH_SHORT).show(); return }
  val count=checkedCandidates.size
  val legacy=Build.VERSION.SDK_INT<30
  // 실행 버튼은 앱 자체의 재확인 창을 띄우지 않습니다.
  // Android가 요구하는 시스템 권한 승인 창은 생략할 수 없습니다.
  if(!legacy) {
   try { startIntentSenderForResult(MediaStore.createTrashRequest(contentResolver,checkedCandidates.toList(),true).intentSender,2,null,0,0,0) }
   catch(e:Exception) { update("휴지통 이동 오류: ${e.message}") }
  } else {
   pendingDelete=checkedCandidates.toMutableList()
   deletedCount=0; deleteFailed=0; deleteTotal=pendingDelete.size; deletionRunning=true
   continueLegacyDelete()
  }
 }
}
