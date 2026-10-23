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
import android.graphics.drawable.BitmapDrawable
import android.graphics.Typeface
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
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(20)) }
  scroll.addView(root)
  val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(16),dp(18),dp(16)); background=shape(navy,dp(16).toFloat()) }
  add(header,text("사진깔끔",24f,Color.WHITE,true),6)
  add(header,text("내 사진을 똑똑하게 정리하는 공간",12f,Color.rgb(185,224,229)),0)
  add(root,header,20)
  add(root,text("사진 정리 도구",18f,navy,true),12)
  val actions=listOf(
   Triple("전체 사진",0,{ scanPhotos() }),
   Triple("사진 폴더",0,{ chooseFolders() }),
   Triple("동영상 폴더",1,{ chooseVideoFolders() }),
   Triple("선택 사진 보기",0,{ reviewSelected() }),
   Triple("중복 분석",2,{ analyze() }),
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
     background=GradientDrawable(GradientDrawable.Orientation.TL_BR,palette).apply {
      cornerRadius=dp(14).toFloat(); setStroke(dp(1),Color.argb(110,255,255,255))
     }
     elevation=dp(3).toFloat()
     setShadowLayer(2f,1f,2f,Color.argb(70,0,0,0))
     setOnClickListener { if(!busy) item.third() }
    }
    line.addView(tile,LinearLayout.LayoutParams(0,dp(56),1f).apply {
     if(index==0) rightMargin=dp(6) else leftMargin=dp(6)
    })
   }
   add(root,line,12)
  }
  val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)); background=shape(Color.WHITE,dp(16).toFloat()) }
  add(panel,text("작업 진행 상황",18f,navy,true),12)
  status=text("준비 완료 · 사진을 불러와 주세요",14f,navy,true)
  add(panel,status,8)
  details=text("선택한 사진 0장",13f,Color.DKGRAY)
  add(panel,details,10)
  progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=0; progressTintList=android.content.res.ColorStateList.valueOf(teal) }
  add(panel,progress,8)
  percent=text("0%",14f,teal,true)
  add(panel,percent,8)
  resultBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
  add(panel,resultBox,12)
  add(panel,button("휴지통 이동 후보 확인") { requestTrash() },0)
  add(root,panel,24)
  val bottom=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER; setPadding(dp(16),dp(12),dp(16),dp(12)) }
  fun bottomAction(label:String,color:Int,action:()->Unit):TextView = TextView(this).apply {
   text=label; textSize=20f; setTypeface(null,Typeface.BOLD)
   setTextColor(Color.WHITE); gravity=android.view.Gravity.CENTER
   setPadding(dp(12),dp(10),dp(12),dp(10)); maxLines=1
   setAutoSizeTextTypeUniformWithConfiguration(16,20,1,android.util.TypedValue.COMPLEX_UNIT_SP)
   background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(lighten(color),color)).apply { cornerRadius=dp(16).toFloat(); setStroke(dp(1),Color.WHITE) }; elevation=dp(4).toFloat()
   setOnClickListener { action() }
  }
  val run=bottomAction("실행",Color.rgb(0,176,118)) { if(!busy) analyze() }
  val exit=bottomAction("종료",Color.rgb(237,65,89)) {
   AlertDialog.Builder(this@MainActivity).setTitle("사진깔끔 종료").setMessage("앱을 종료하시겠습니까?")
    .setNegativeButton("취소",null).setPositiveButton("종료") { _,_ -> finishAffinity() }.show()
  }
  bottom.addView(run,LinearLayout.LayoutParams(0,dp(60),1f).apply { rightMargin=dp(6) })
  bottom.addView(exit,LinearLayout.LayoutParams(0,dp(60),1f).apply { leftMargin=dp(6) })
  val shell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); fitsSystemWindows=true }
  shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  shell.addView(bottom,LinearLayout.LayoutParams(-1,-2))
  setContentView(shell)
 }
 private fun update(message:String,done:Int=0,total:Int=0) {
  runOnUiThread {
   status.text=message
   progress.progress=if(total>0) (done*100L/total).toInt() else 0
   percent.text=if(total>0) "${progress.progress}% · ${done}/${total}장" else "0%"
  }
 }
 private fun pickPhotos() {
  val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true) }
  startActivityForResult(i,1)
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
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
 private fun loadPhotos():List<Pair<Uri,String>> {
  val items=mutableListOf<Pair<Uri,String>>()
  val base=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
  val projection=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
  contentResolver.query(base,projection,null,null,null)?.use { cursor ->
   val id=cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
   val bucket=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
   while(cursor.moveToNext()) items.add(ContentUris.withAppendedId(base,cursor.getLong(id)) to (cursor.getString(bucket) ?: "기타"))
  }
  return items
 }
 private fun scanPhotos() {
  if(!permitted()) return
  busy=true; update("휴대폰 사진 목록 읽는 중...")
  Thread {
   try {
    val all=loadPhotos()
    runOnUiThread {
     selected.clear(); selected.addAll(all.map { it.first }); candidates=emptyList(); checkedCandidates.clear()
     details.text="전체 사진 ${selected.size}장"
     resultBox.removeAllViews()
     busy=false; update("전체 사진 불러오기 완료",all.size,all.size)
    }
   } catch(e:Exception) { busy=false; update("검색 오류: ${e.message}") }
  }.start()
 }
 private fun chooseFolders() {
  if(!permitted()) return
  busy=true; update("폴더 목록 불러오는 중...")
  Thread {
   try {
    val all=loadPhotos()
    val folders=all.groupBy { it.second }.toSortedMap()
    val names=folders.keys.toTypedArray()
    val checked=BooleanArray(names.size)
    runOnUiThread {
     busy=false
     if(names.isEmpty()) { update("사진 폴더가 없습니다"); return@runOnUiThread }
     update("${names.size}개 폴더 발견")
     AlertDialog.Builder(this).setTitle("분석할 폴더 선택")
      .setMultiChoiceItems(names.map { "${it} (${folders[it]?.size ?: 0}장)" }.toTypedArray(),checked) { _,which,value -> checked[which]=value }
      .setNegativeButton("취소",null)
      .setPositiveButton("선택 완료") { _,_ ->
       selected.clear()
       names.forEachIndexed { index,name -> if(checked[index]) selected.addAll(folders[name]!!.map { it.first }) }
       candidates=emptyList(); checkedCandidates.clear(); resultBox.removeAllViews()
       details.text="선택한 폴더 ${checked.count { it }}개 · 사진 ${selected.size}장"
       update("폴더 선택 완료",selected.size,selected.size)
      }.show()
    }
   } catch(e:Exception) { busy=false; update("폴더 검색 오류: ${e.message}") }
  }.start()
 }
 private fun chooseVideoFolders() {
  if(!permitted()) return
  busy=true; update("동영상 폴더 검색 중...")
  Thread {
   try {
    val base=MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val items=mutableListOf<Pair<Uri,String>>()
    val columns=arrayOf(MediaStore.Video.Media._ID,MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
    contentResolver.query(base,columns,null,null,null)?.use { cursor ->
     val id=cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
     val folder=cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
     while(cursor.moveToNext()) items.add(ContentUris.withAppendedId(base,cursor.getLong(id)) to (cursor.getString(folder) ?: "기타"))
    }
    val groups=items.groupBy { it.second }.toSortedMap()
    val names=groups.keys.toTypedArray()
    val flags=BooleanArray(names.size)
    runOnUiThread {
     busy=false
     if(names.isEmpty()) { update("동영상 폴더가 없습니다"); return@runOnUiThread }
     update("동영상 폴더 ${names.size}개 검색 완료")
     AlertDialog.Builder(this).setTitle("동영상 폴더 선택")
      .setMultiChoiceItems(names.map { "${it} (${groups[it]?.size ?: 0}개)" }.toTypedArray(),flags) { _,i,v -> flags[i]=v }
      .setNegativeButton("취소",null)
      .setPositiveButton("선택 완료") { _,_ ->
       selectedVideos.clear()
       names.forEachIndexed { i,name -> if(flags[i]) selectedVideos.addAll(groups[name]!!.map { it.first }) }
       details.text="선택된 동영상 ${selectedVideos.size}개 · 사진 ${selected.size}장"
       resultBox.removeAllViews()
       add(resultBox,text("동영상 ${selectedVideos.size}개 선택됨 · 삭제하지 않습니다.",14f,navy,true),8)
       update("동영상 폴더 선택 완료",selectedVideos.size,selectedVideos.size)
      }.show()
    }
   } catch(e:Exception) { busy=false; update("동영상 폴더 오류: ${e.message}") }
  }.start()
 }
 private fun analyze() {
  if(selected.isEmpty()) { update("먼저 사진이나 폴더를 선택하세요"); return }
  val items=selected.toList()
  busy=true; candidates=emptyList(); checkedCandidates.clear(); resultBox.removeAllViews()
  update("중복 사진 비교 시작",0,items.size)
  Thread {
   val hashes=linkedMapOf<String,MutableList<Uri>>()
   var errors=0
   items.forEachIndexed { index,uri ->
    try {
     val md=MessageDigest.getInstance("SHA-256")
     contentResolver.openInputStream(uri)?.use { stream ->
      val buffer=ByteArray(65536)
      while(true) { val n=stream.read(buffer); if(n<0) break; md.update(buffer,0,n) }
      val hash=md.digest().joinToString("") { "%02x".format(it) }
      hashes.getOrPut(hash) { mutableListOf() }.add(uri)
     } ?: run { errors++ }
    } catch(e:Exception) { errors++ }
    if(index%5==0 || index==items.lastIndex) update("사진 비교 중...",index+1,items.size)
   }
   val groups=hashes.values.filter { it.size>1 }
   val found=groups.flatMap { it.drop(1) }
   runOnUiThread {
    candidates=groups.take(80).flatMap { it.drop(1) }; busy=false
    update("분석 완료 · 중복 ${groups.size}그룹",items.size,items.size)
    details.text="검사 ${items.size}장 · 중복 후보 ${found.size}장 · 읽기 실패 ${errors}장"
    resultBox.removeAllViews()
    add(resultBox,text(if(found.isEmpty()) "중복된 사진이 없습니다." else "중복 후보 ${found.size}장 발견 · 각 그룹 첫 사진은 보존",15f,navy,true),8)
    groups.take(80).forEachIndexed { index,group ->
     val groupCard=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(10,12,10,12); background=shape(Color.rgb(241,247,253),18f) }
     add(groupCard,text("중복 그룹 ${index+1} · ${group.size}장",15f,navy,true),6)
     group.forEachIndexed { i,uri ->
      val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
      val preview=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
      line.addView(preview,LinearLayout.LayoutParams(72,72))
      try {
       val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true }
       contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,opts) }
       val sample=maxOf(1,maxOf(opts.outWidth,opts.outHeight)/160)
       val small=BitmapFactory.Options().apply { inSampleSize=sample }
       contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,small) }?.let { preview.setImageBitmap(it) }
      } catch(_:Exception) {}
      val check=CheckBox(this).apply {
       text=if(i==0) "원본 보존 (선택 불가)" else "중복 후보 · 휴지통 선택"
       isEnabled=i!=0
       isChecked=i!=0
       if(i!=0) checkedCandidates.add(uri)
       setOnCheckedChangeListener { _,value -> if(value) checkedCandidates.add(uri) else checkedCandidates.remove(uri) }
      }
      line.addView(check,LinearLayout.LayoutParams(0,-2,1f))
      add(groupCard,line,6)
     }
     add(resultBox,groupCard,12)
    }
    if(groups.size>80) add(resultBox,text("표시 성능을 위해 첫 80개 그룹만 표시합니다. 나머지 후보는 자동 선택되지 않습니다.",13f,Color.DKGRAY),8)
   }
  }.start()
 }
 private fun selectScreenshots() {
  if(!permitted()) return
  busy=true; update("스크린샷 검색 중...")
  Thread {
   try {
    val found=loadPhotos().filter { it.second.contains("screenshot",true) || it.second.contains("스크린샷",true) }
    runOnUiThread {
     selected.clear(); selected.addAll(found.map { it.first }); candidates=emptyList(); checkedCandidates.clear()
     resultBox.removeAllViews(); busy=false
     details.text="스크린샷 ${selected.size}장"
     update("스크린샷 선택 완료",selected.size,selected.size)
    }
   } catch(e:Exception) { busy=false; update("스크린샷 오류: ${e.message}") }
  }.start()
 }
 private fun showLargePhotos() {
  if(!permitted()) return
  busy=true; update("대용량 사진 검색 중...")
  Thread {
   try {
    val base=MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val found=mutableListOf<Pair<Uri,Long>>()
    val columns=arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.SIZE)
    contentResolver.query(base,columns,null,null,MediaStore.Images.Media.SIZE+" DESC")?.use { cursor ->
     val id=cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
     val size=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
     while(cursor.moveToNext()) {
      val bytes=cursor.getLong(size)
      if(bytes>=10L*1024*1024) found.add(ContentUris.withAppendedId(base,cursor.getLong(id)) to bytes)
     }
    }
    runOnUiThread {
     busy=false; selected.clear(); selected.addAll(found.map { it.first }); candidates=emptyList(); checkedCandidates.clear()
     resultBox.removeAllViews()
     details.text="10MB 이상 사진 ${found.size}장"
     update("대용량 사진 검색 완료",found.size,found.size)
     found.take(40).forEach { item ->
      add(resultBox,text("사진 · %.1f MB".format(item.second/1048576.0),14f,navy),6)
     }
     add(resultBox,text("대용량 목록은 정보 확인용입니다. 자동 삭제하지 않습니다.",13f,Color.DKGRAY),8)
    }
   } catch(e:Exception) { busy=false; update("대용량 검색 오류: ${e.message}") }
  }.start()
 }
 private fun reviewSelected() {
  if(selected.isEmpty()) { Toast.makeText(this,"선택된 사진이 없습니다",Toast.LENGTH_SHORT).show(); return }
  val photos=selected.toList()
  val wrap=ScrollView(this)
  val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(12,12,12,12) }
  wrap.addView(column)
  add(column,text("선택된 사진 ${photos.size}장 · 한 줄 2장씩 표시",16f,navy,true),12)
  val width=(resources.displayMetrics.widthPixels-100)/2
  val side=maxOf(110,width)
  photos.take(100).chunked(2).forEachIndexed { rowIndex,pair ->
   val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
   pair.forEachIndexed { colIndex,uri ->
    val cell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(4,4,4,8); background=shape(Color.rgb(238,246,255),12f) }
    val thumb=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(220,232,245)) }
    cell.addView(thumb,LinearLayout.LayoutParams(-1,side))
    try {
     val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
     contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
     val sample=maxOf(1,maxOf(bounds.outWidth,bounds.outHeight)/360)
     val options=BitmapFactory.Options().apply { inSampleSize=sample }
     contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }?.let { thumb.setImageBitmap(it) }
    } catch(_:Exception) {}
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
   .setNegativeButton("닫기",null)
   .setPositiveButton("중복 분석") { _,_ -> if(!busy) analyze() }.show()
 }
 private fun requestTrash() {
  if(checkedCandidates.isEmpty()) { Toast.makeText(this,"선택된 중복 후보가 없습니다",Toast.LENGTH_SHORT).show(); return }
  AlertDialog.Builder(this).setTitle("휴지통 이동 확인")
   .setMessage("중복 후보 ${candidates.size}장을 휴지통으로 이동할까요? 각 그룹 첫 번째 사진은 보존합니다.")
   .setNegativeButton("취소",null).setPositiveButton("계속") { _,_ ->
    if(Build.VERSION.SDK_INT>=30) {
     try { startIntentSenderForResult(MediaStore.createTrashRequest(contentResolver,checkedCandidates.toList(),true).intentSender,2,null,0,0,0) }
     catch(e:Exception) { update("휴지통 이동 오류: ${e.message}") }
    } else update("Android 10에서는 시스템 휴지통 이동 API가 지원되지 않습니다. 사진을 삭제하지 않았습니다.")
   }.show()
 }
}
