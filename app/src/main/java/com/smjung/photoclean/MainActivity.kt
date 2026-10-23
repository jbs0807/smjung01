package com.smjung.photoclean

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.provider.MediaStore
import android.widget.*
import android.net.Uri
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.content.ContentUris
import android.database.Cursor
import java.security.MessageDigest

class MainActivity : Activity() {
 private val selected = mutableListOf<Uri>()
 private var duplicateCandidates = listOf<Uri>()
 private lateinit var status: TextView
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val layout = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,48,32,24) }
  layout.addView(TextView(this).apply { text="사진깔끔"; textSize=28f })
  status=TextView(this).apply { text="사진을 선택하여 중복 후보를 검토하세요."; textSize=16f }
  layout.addView(status)
  layout.addView(Button(this).apply { text="휴대폰 사진 전체 검색"; setOnClickListener { scanPhotos() } })
  layout.addView(Button(this).apply { text="사진 여러 장 선택"; setOnClickListener {
   val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true) }
   startActivityForResult(intent,1)
  } })
  layout.addView(Button(this).apply { text="완전 중복 분석"; setOnClickListener { analyze() } })
  layout.addView(Button(this).apply { text="중복 후보 휴지통 이동"; setOnClickListener { requestTrash() } })
  setContentView(layout)
 }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
  super.onActivityResult(requestCode,resultCode,data)
  if(requestCode==1 && resultCode==RESULT_OK) {
   selected.clear(); duplicateCandidates=emptyList()
   data?.clipData?.let { clip -> for(i in 0 until clip.itemCount) selected.add(clip.getItemAt(i).uri) }
   data?.data?.let { if(!selected.contains(it)) selected.add(it) }
   status.text="${selected.size}장 선택됨"
  }
 }
 private fun scanPhotos() {
  val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
  if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
   requestPermissions(arrayOf(permission), 10)
   return
  }
  Thread {
   try {
    val found = mutableListOf<Uri>()
    val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { cursor ->
     val idx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
     while(cursor.moveToNext()) found.add(ContentUris.withAppendedId(base,cursor.getLong(idx)))
    }
    runOnUiThread {
     selected.clear(); selected.addAll(found); duplicateCandidates=emptyList()
     status.text="${found.size}장 검색 완료. '완전 중복 분석'을 누르세요."
    }
   } catch(e:Exception) { runOnUiThread { status.text="사진 검색 실패: ${e.message}" } }
  }.start()
 }
 override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
  super.onRequestPermissionsResult(requestCode,permissions,grantResults)
  if(requestCode==10) {
   if(grantResults.isNotEmpty() && grantResults[0]==PackageManager.PERMISSION_GRANTED) scanPhotos()
   else status.text="사진 접근 권한이 필요합니다. 설정에서 사진 접근을 허용하세요."
  }
 }
 private fun analyze() {
  if(selected.isEmpty()) { status.text="먼저 '휴대폰 사진 전체 검색'을 실행하세요."; return }
  status.text="중복 분석 중..."
  Thread { analyzeSelected() }.start()
 }
 private fun analyzeSelected() {
  val groups=selected.groupBy { uri ->
   try { contentResolver.openInputStream(uri)?.use { stream ->
    val md=MessageDigest.getInstance("SHA-256")
    val buffer=ByteArray(8192)
    while(true) { val n=stream.read(buffer); if(n<0) break; md.update(buffer,0,n) }
    md.digest().joinToString("") { "%02x".format(it) }
   } ?: uri.toString() } catch(e:Exception) { uri.toString() }
  }.values.filter { it.size>1 }
  duplicateCandidates=groups.flatMap { it.drop(1) }
  runOnUiThread { status.text="완전 중복 ${groups.size}그룹, 휴지통 후보 ${duplicateCandidates.size}장. 각 그룹 첫 번째 사진은 보존합니다." }
 }
 private fun requestTrash() {
  if(duplicateCandidates.isEmpty()) { Toast.makeText(this,"중복 분석 후 후보를 확인하세요",Toast.LENGTH_SHORT).show(); return }
  AlertDialog.Builder(this).setTitle("휴지통 이동 확인").setMessage("완전 중복으로 판정된 ${duplicateCandidates.size}장을 휴지통으로 이동하시겠습니까? 각 그룹의 첫 사진은 남습니다.").setNegativeButton("취소",null).setPositiveButton("이동 요청") { _,_ ->
   if(Build.VERSION.SDK_INT>=30) {
    try { startIntentSenderForResult(MediaStore.createTrashRequest(contentResolver,duplicateCandidates,true).intentSender,2,null,0,0,0) }
    catch(e:Exception) { status.text="휴지통 이동 불가: ${e.message}" }
   } else status.text="이 기기의 Android 버전에서는 이 방식의 휴지통 이동을 지원하지 않습니다."
  }.show()
 }
}
