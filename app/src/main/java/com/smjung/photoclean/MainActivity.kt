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

 private fun shape(color:Int, radius:Float=24f):GradientDrawable =
  GradientDrawable().apply { setColor(color); cornerRadius=radius }
 private fun text(value:String,size:Float,color:Int,bold:Boolean=false)=TextView(this).apply {
  text=value; textSize=size; setTextColor(color); if(bold) setTypeface(null,Typeface.BOLD)
 }
 private fun button(title:String, action:()->Unit)=Button(this).apply {
  text=title; isAllCaps=false; textSize=15f; setTextColor(Color.WHITE)
  background=shape(navy,22f); setOnClickListener { if(!busy) action() }
 }
 private fun add(container:LinearLayout, view:View, bottom:Int=12) {
  container.addView(view,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=bottom })
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  window.statusBarColor=navy
  window.navigationBarColor=navy
  val scroll=ScrollView(this).apply { setBackgroundColor(Color.rgb(244,248,251)) }
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(22,30,22,36) }
  scroll.addView(root)
  val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(22,24,22,22); background=shape(navy) }
  add(header,text("✦ 사진깔끔",29f,Color.WHITE,true),6)
  add(header,text("내 사진을 똑똑하게 정리하는 공간",14f,Color.rgb(185,224,229)),0)
  add(root,header,20)
  add(root,text("사진 정리 도구",20f,navy,true),12)
  add(root,button("▣  휴대폰 전체 사진 불러오기") { scanPhotos() })
  add(root,button("▤  폴더별 사진 선택") { chooseFolders() })
  add(root,button("＋  사진 직접 선택") { pickPhotos() })
  add(root,button("⌕  완전 중복 분석 시작") { analyze() })
  add(root,button("▧  대용량 사진 찾기 (10MB 이상)") { showLargePhotos() })
  add(root,button("▣  스크린샷만 선택") { selectScreenshots() })
  add(root,button("⚙  설치 앱 관리 열기") { startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS)) },20)
  val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,22,20,22); background=shape(Color.WHITE) }
  add(panel,text("작업 진행 상황",19f,navy,true),12)
  status=text("준비 완료 · 사진을 불러와 주세요",15f,navy,true)
  add(panel,status,8)
  details=text("선택한 사진 0장",13f,Color.DKGRAY)
  add(panel,details,14)
  progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100; progress=0; progressTintList=android.content.res.ColorStateList.valueOf(teal) }
  add(panel,progress,8)
  percent=text("0%",14f,teal,true)
  add(panel,percent,14)
  resultBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
  add(panel,resultBox,12)
  add(panel,button("휴지통 이동 후보 확인") { requestTrash() },0)
  add(root,panel)
  setContentView(scroll)
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
